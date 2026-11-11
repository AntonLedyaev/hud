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
`StateFlow` carries the latest screen state; durable event storage follows in
OpenSpec block 7. Images and trip events are not saved by this block.

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
