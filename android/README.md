# android/ — MVP app (Stage 1)

Kotlin + Jetpack Compose + CameraX + TFLite (LiteRT) + Room.

The single-module app is initialized and its debug shell has been launched on the
Pixel 6a (Android 16 / API 36). Recognition is not implemented in this shell.

Planned pipeline: CameraX ImageAnalysis → TFLite detector → state logic
(current speed limit / active restrictions) → Compose overlay + Room trip logs.

Class list is derived from `../shared/labels.yaml`.

## Windows build

Open this `android/` directory in Android Studio, or run from this directory:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug
& "$env:ANDROID_HOME\platform-tools\adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
& "$env:ANDROID_HOME\platform-tools\adb.exe" shell am start -W -n dev.diyhud/.ui.MainActivity
```

Use JDK 21 to run Gradle; Java/Kotlin bytecode targets 17. The checked-in wrapper
pins Gradle 8.13 with distribution checksum verification; AGP is 8.13.2, Kotlin
and the Compose compiler plugin are 2.2.21, Compose BOM is 2025.10.01 and Activity
Compose is 1.11.0. Install SDK Platform 36 and Build-Tools 36.1.0 through SDK Manager
(Gradle can install missing SDK components if their licenses are accepted).
The shell currently targets API 36 with minimum API 26; inference compatibility
will be validated separately before runtime integration.

One `:app` module contains package-level layers. `ui`, `domain` and `inference`
now contain runtime code. `domain` remains plain Kotlin without Android imports.
`session`, `camera` and `data` reserve boundaries for subsequent tasks.
Compose `setContent` declares the UI from state; the Activity only hosts it.
The screen displays an unknown estimate and unavailable recognition until a
validated model and camera pipeline are integrated.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Lint report: `app/build/reports/lint-results-debug.html`.
The first build passed with zero lint errors; warnings flag newer dependency
versions, a missing launcher icon and modern backup rules still to be added.
The current build bundles the trained experimental FP32 model and CPU runtime.
Export it first using `../ml/export/README.md`; the ignored local bundle is a build
input and is validated before staging. The startup screen checks the local bundle
on a worker and reports model availability. Camera capture is not connected yet.

## Explicit CPU export parity test

First run the isolated export experiment described in `../ml/export/README.md`.
With an authorized Pixel connected, run from this directory:

```powershell
.\gradlew.bat clean :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest
```

The test stages fixtures from ignored `../ml/runs/export_experiment/` into generated
test assets. No weights enter the production application assets or git. It requires
the report, FP32 TFLite model, NHWC input bytes and PyTorch output bytes; missing
fixtures fail the test build. The report hash is checked against the model.
Android lint also inspects the instrumented-test assets, so run the export
experiment before lint. A shell-only `assembleDebug` requires no model fixtures.

The harness pins bundled LiteRT 1.4.0 with its Interpreter API, using two CPU threads
and optional XNNPACK disabled. It checks tensor shapes, all raw output values
(absolute tolerance 0.001 plus relative tolerance 0.0001), per-candidate top class
and decoded cx/cy/w/h coordinates. The untrained synthetic test passed on Pixel 6a,
Android 16 / API 36. This validates export compatibility, not road-sign accuracy,
performance or the eventual production runtime/backend selection.

Results: `app/build/reports/androidTests/connected/debug/` and
`app/build/outputs/androidTest-results/connected/debug/`.

## Restriction logic and replay

The pure Kotlin domain implements temporal sign confirmation, independent speed
and overtaking estimates, transient STOP, conflict handling, reset generations
and explicit-clock staleness. See `app/src/main/java/dev/diyhud/domain/README.md`
for the event contract, integration requirements and replay commands.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*DomainTest'
```

The domain-only suite needs the ML Python environment to generate canonical labels,
but no camera, connected phone or model weights. Packaging and model-loader tests
use the local bundle separately. Domain replay also writes a synthetic
JSONL stream to `app/build/reports/domain/events.jsonl` for schema validation.

## Trained model and rollback

Default local bundle: `../ml/runs/trained-bundle-v2/`. Both it and the ML Python
environment are required for builds. Asset generation uses checksums and strict
metadata; weights are copied into `app/build/` only and never committed.

```powershell
.\gradlew.bat clean :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:connectedDebugAndroidTest
```

The trained device suite checks three source-scene inputs, tensor and decoded
parity, corrupted/missing models, valid reload and explicit CPU fallback. It also
probes a test-only GPU delegate and records why CPU is retained. GPU is not enabled
by successful test execution alone. Current GPU initialization failed for this graph.

To switch or roll back, pass `-PmodelBundleDir=ABSOLUTE_PATH_TO_VALIDATED_BUNDLE`,
rebuild and reinstall. Retain previous validated bundle directories unchanged.
Every startup creates unknown restriction state with a new identity; no estimate
is restored across a model change. Runtime model errors show an unavailable status
and require a valid build. Replacing weights without their matching manifest/hash
fails validation. The current loader supports validated FP32 RGB/NHWC, letterbox,
BCN raw YOLO/class-probability output and consumer NMS; other contracts are rejected.

Instrumented tests may uninstall the app on completion. Install the application
APK again with the command above if it disappears from the launcher. Model weights
loaded successfully are still experimental; see `../docs/TRAINED_EXPORT_RESULTS.md`.

## Foreground camera and HUD

Open **DIY HUD** on the phone. Select **Diagnostics before Start** while idle if
you want a preview; otherwise only the driving HUD is shown. Press **Start** and
allow camera access. Every start validates the bundled model and creates a new
session with unknown estimates. No network or location permission is requested.

The large number is an **observed speed limit**, in km/h. `—` means unknown;
expired estimates also show `—` with a stale message. Overtaking and the transient
STOP observation have independent indicators. No vehicle speed is shown. The
baseline model is experimental and has documented class-coverage failures.

**Reset** clears estimates and pending confirmation without reopening the camera.
Results captured before the reset, including work already in flight, cannot
restore old estimates. **Stop**, leaving the foreground, screen lock and Activity
recreation close the session. Returning to the app requires **Start** again.
The screen stays awake only during an active session.

If permission is denied, use **App settings**, allow Camera, return and press
**Start**. A system/AppOps block is reported separately from bundle validation or
engine failure. If the camera cannot be selected/opened, close other camera apps
and check the camera privacy control. Ten seconds without inference results also
ends the session with an actionable error. Recovery always begins with unknown
state; the app never substitutes fixture detections.

Diagnostics shows generated class names, raw candidate boxes/confidence, candidate
age and analyzed FPS. Boxes disappear after 1500 ms. Preview and analysis share a
CameraX ViewPort; cropped RGBA frames are copied before ImageProxy.close(), rotated
upright, letterboxed and mapped back to the shared preview. An initial geometry
probe compares three rectangles against CameraX transforms and reports pixel error.
Camera resolution requests 1280x960; actual dimensions/crop are supplied by CameraX.

Metrics exclude `[0, 5000)` ms from steady-state latency samples. FPS covers the
whole steady interval; p50/p95 use the latest **512** samples (nearest rank).
Inference latency includes rotation, preprocessing, native inference and decoding.
Capture-to-result includes pending work. REALTIME sensor timestamps use Android's
elapsed-realtime clock; other cameras use a first-frame anchor and explicitly
report estimated latency. Queue drops count copied frames superseded by the app;
CameraX's internal drops are not exposed by this API. Thermal values use Android
PowerManager status codes (0 = none; -1 = unavailable). The panel also identifies
device/OS, app/model/labels/config, input resolution, duration and warm-up frames.

One single-thread executor owns model initialization, inference and close. A
separate camera executor copies frames, and the app retains only one in-flight
frame plus the newest pending copy. A short synchronized section orders domain
events and publishes immutable StateFlow snapshots; native inference never holds
that lock. Explicit 250 ms ticks expire state even when detections are empty.
`StateFlow` carries the latest screen state. Since app 0.1.1, a separate bounded
writer persists trip events in Room; see Local event journal below. Since 0.1.2,
individual images can be saved by explicit pre-session opt-in. Video is not recorded.

### Camera verification

With an unlocked Pixel attached, run from `android/`:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=dev.diyhud.ForegroundSessionTest'
```

This exercises real-camera start/stop/reset, diagnostics geometry/metrics,
background/resume, an unavailable-camera selection fixture and
fatal-error recovery. The slow-worker JVM fixture checks bounded work, frame
release and reset/session isolation; live/replay results must agree.

Permission denial needs a separate invocation: revoking permission kills the
target process, so it cannot be done inside that process's running test. After
building both APKs, from `android/`:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm revoke dev.diyhud android.permission.CAMERA
adb shell pm clear-permission-flags dev.diyhud android.permission.CAMERA user-set user-fixed
adb shell am instrument -w -e class dev.diyhud.PermissionSessionTest dev.diyhud.test/androidx.test.runner.AndroidJUnitRunner
```

That test clicks **Don't allow** in the real system permission dialog and checks
that no session starts and the app explains recovery. It skips if permission was
already granted. Grant access via the next app permission prompt when finished.
For active-session revocation, start the app normally, press Start and confirm
the camera is active; then run `adb shell pm revoke dev.diyhud android.permission.CAMERA`
from the PC. On the reference Android 16 device this terminates the app process.
Reopen DIY HUD: it must stay idle with unknown estimates and require explicit
Start plus camera permission. After regrant, a fresh real-camera session must work.

For offline cold start, keep USB connected, disable all active phone networks,
force-stop DIY HUD and repeat the foreground camera test. Airplane mode alone
is insufficient if Wi-Fi remains enabled. Verify `adb shell dumpsys connectivity`
reports no active default network before testing, and restore the phone's prior
network and sleep settings afterward. Use an unlocked, awake phone for the test.
Device findings and remaining acceptance work: `../docs/CAMERA_HUD_RESULTS.md`.

### Confirmation diagnostics and the Pixel 6a experiment

In Diagnostics, each candidate shows a track ID, associated hits (`1/3`, `2/3`),
last-seen age and reason. Reasons distinguish a new candidate, no matching box
at the configured IoU, old hits leaving the time window, a missed detection,
low confirmation confidence and an already-confirmed encounter. The overlay also
shows hit progress. The list contains at most six rows from the latest analyzed
frame; its age is visible, and updates do not add hits or change reducer inputs.
Reset clears progress. Already-confirmed signs remain deduplicated until absence
rearms them; a diagnostic hit count alone is not a new restriction event.

The baseline bundle remains unchanged (config v1, 3 hits / 1000 ms). The installed
experimental comparison uses **config v2, 3 hits / 3000 ms**, with identical model
weights, confidence 0.25, association IoU 0.5 and 2000 ms absence rearm. At capture
times 0, 700 and 1400 ms the baseline fails to confirm; the experimental config
confirms and replays to the same 30 km/h state. Missed detections and box movement
can still prevent confirmation. A wider window can accumulate false detections
for longer; this profile has not passed outdoor calibration or final acceptance.

Generate the variant once from the repo root (existing output is never replaced):

```powershell
.\ml\.venv\Scripts\python.exe ml/export/confirmation_variant.py --source ml/runs/trained-bundle-v2 --destination ml/runs/trained-bundle-v2-confirmation-v2 --window-ms 3000 --config-version 2
```

Then build from `android/` with the explicit variant path:

```powershell
.\gradlew.bat :app:assembleDebug '-PmodelBundleDir=C:/Users/9787/IdeaProjects/hud/ml/runs/trained-bundle-v2-confirmation-v2'
```

Change the absolute path for your checkout. Install the resulting APK as usual.
Diagnostics must display **config v2** and **Confirmation window 3000 ms**. The
script validates the source, preserves weights and tensor parity, records variant
provenance and refreshes the manifest checksum in the preprocessing profile. It
does not retrain or claim confirmation-quality parity. To compare/roll back, build
without the property to use the original baseline; every new session starts unknown.

## Local event journal (0.1.1)

Each valid started session records its ID, wall-clock start/end, end reason,
app/model/backend/device, generated labels, original model manifest and complete
effective config. Ordered events contain confirmed batches (including source
frame IDs, boxes and confidence), ticks, resets, errors, conflicts and transitions.
Transition/conflict rows link to the input sequence and include the resulting
state. Raw per-frame candidates are currently visible in Diagnostics only.

The HUD shows **Local events saved: stored/attempted**. If the writer queue fills
or disk writing fails, **Evidence incomplete** shows losses and the failure.
Recognition continues; no modal acknowledgement is required. Stop closes the
camera and drains the writer in the background before Start becomes available.

Bundle defaults declare **256 pending events** and **100 MB** local storage.
The budget includes database/WAL/SHM and reserves 128 KiB for SQLite overhead and
the final footer. Old records are never silently deleted. Overflow drops new
events, records their count and leaves explicit sequence gaps; a gap marker is
written when the queue drains, including a trailing dropped interval. A failed
writer stops accepting new records for that session, without per-event retries.

The database is app-private `databases/hud-evidence.db` (Room 2.8.5, KSP
2.2.21-2.0.5). Its versioned schema is stored under `app/schemas/`; no
destructive fallback is enabled. Interrupted sessions retain committed events,
have an unknown true end time and are marked interrupted on the next process
startup. Their estimates never resume. The paginated replay runner reports gaps,
unknown tails and interruption as incomplete evidence.

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest '-PmodelBundleDir=C:/Users/9787/IdeaProjects/hud/ml/runs/trained-bundle-v2-confirmation-v2'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class dev.diyhud.EvidenceStorageTest,dev.diyhud.ForegroundSessionTest dev.diyhud.test/androidx.test.runner.AndroidJUnitRunner
```

Unlock the device for the camera tests. The storage-only class also runs while
locked. `EvidenceWriterTest` simulates blocked disk, queue overflow, missing Reset,
budget exhaustion, database-open failure and worker rejection. Storage tests
verify all event kinds/source links, exact replay, SQLite close/reopen, explicit
incompleteness and retention when a budget is reached.

Opt-in sampling and partial-image cleanup are available since 0.1.2. The
review/export/delete screens remain later tasks; there is no automatic upload.
Detailed evidence: `../docs/EVIDENCE_STORAGE_RESULTS.md`.

## Optional sample images (0.1.2)

Before **Start**, enable **Save sample images** if you want local frames for
later inspection/annotation. It defaults to off and returns to off after each
session; it cannot be changed during recognition. Select Diagnostics separately
if you also want preview/confirmation progress. Sampling does not require preview.

The app selects at most **one analyzed frame per second**, including frames with
no detected signs, and saves at most **10,000,000 JPEG bytes per session**. The
panel shows image count/bytes and stopped/failed status. A frame arriving too soon
is skipped; when the next complete JPEG cannot fit, sampling stops for the session.
Recognition and structured event recording continue. Global evidence storage
budget still applies and may stop writes earlier. A slow encoder retains only
one current and one pending copied frame; additional eligible frames are counted
as dropped. There is no unbounded encoding/retry queue.

Images are upright JPEG (quality 85), without HUD overlays, in app-private
`files/evidence-samples/`. Each completed file has a Room row with SHA-256, byte
count, session/reset-generation/frame identity, monotonic capture time, the
source state-event sequence, full observation (model/labels/detections), full
effective config, and **unreviewed = true**. Event loss can make its source
sequence absent; the self-contained observation remains available and the event
trace is incomplete. Automatic predictions, including an empty detection list,
are not training labels. Nothing is automatically imported into ML datasets.

The model manifest remains the validated source bundle. Per-session effective
config records the explicit sampling choice, 1000 ms interval and 10 MB budget;
opt-in increments its config version. Use the full config snapshot for replay,
not the version number alone. With the current experimental bundle the temporal
settings remain 3 hits / 3000 ms, confidence 0.25 and IoU 0.5, whether sampling is
on or off. Selected image observations are not an exhaustive detector trace.

Stop drains selected image work before the event footer is finalized. Saving
uses `.jpg.partial` files, then rename and metadata insertion; only committed
rows advertise completed samples. Startup marks open sessions interrupted and
removes managed partial/uncommitted files, retaining committed images/events.
Recognition and sampling never resume automatically after interruption. Room
schema v2 adds samples/status through the tested 1→2 migration without deleting
v1 sessions or events. Schemas 1 and 2 live under `app/schemas/`.

For device checks after building/installing both APKs:

```powershell
adb shell am instrument -w -e class dev.diyhud.SampleStorageTest,dev.diyhud.SampleSessionTest,dev.diyhud.EvidenceStorageTest,dev.diyhud.ForegroundSessionTest dev.diyhud.test/androidx.test.runner.AndroidJUnitRunner
```

The camera tests require an unlocked phone. Sampling tests use synthetic RGB
fixtures; they cover encoding, source links, byte/rate limits, migration,
interrupted-file recovery, explicit activation and the next trip defaulting off.
Viewing/exporting/deleting trips and selected images will be added in block 8.
