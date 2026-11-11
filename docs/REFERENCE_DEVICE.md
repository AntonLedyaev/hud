# Stage 1 reference device

- Device: Google Pixel 6a (developer-confirmed, 2026-11-11).
- Android: 16, API level 36 (queried from the physical device via ADB).
- Build: `google/bluejay/bluejay:16/CP1A.260405.005/15001963:user/release-keys`.
- ADB access: authorized USB connection verified after replacing the cable.
- CPU/GPU inference, parity, thermal behavior and sustained latency/FPS: unmeasured.
- Acceptance limits and minimum class sample counts: not yet calibrated/frozen.

Connect the unlocked device with USB debugging enabled and authorize the computer.
From a terminal with Android platform-tools available:

```powershell
adb devices
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.fingerprint
```

Repeat these queries if the device or OS changes. Do not store the device serial.
Emulator runs do not satisfy physical-device acceptance.
