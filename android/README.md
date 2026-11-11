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

One `:app` module contains package-level layers. Only `ui` currently has runtime
code. `domain` must remain plain Kotlin without Android imports. `session`,
`camera`, `inference` and `data` reserve boundaries for subsequent tasks.
Compose `setContent` declares the UI from state; the Activity only hosts it.
The screen displays an unknown estimate and unavailable recognition until a
validated model and camera pipeline are integrated.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Lint report: `app/build/reports/lint-results-debug.html`.
The first build passed with zero lint errors; warnings flag newer dependency
versions, a missing launcher icon and modern backup rules still to be added.
No model assets are bundled yet and no fixture detector is enabled implicitly.

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
