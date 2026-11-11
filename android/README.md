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
