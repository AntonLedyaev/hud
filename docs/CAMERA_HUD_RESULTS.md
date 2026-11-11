# Camera and HUD integration checks

Date: 2026-11-11. Scope: OpenSpec block 6, **not Stage 1 acceptance**.

## Provisioned configuration

- Pixel 6a, Android 16 / API 36, rear camera.
- App 0.1.0, CameraX 1.5.1, LiteRT 1.4.0, validated CPU backend.
- Model `gtsdb-yolo11n-640-v2`, FP32 RGB/NHWC 640x640.
- Labels v1, SHA-256 `e069dcb4ed2c1efb37a1eb8abaf7a613c171333116cd4883b4b1779efdb027b6`.
- Bundle effective config v1, including 3 hits within 1000 ms, 60 s estimate age
  and 3 s STOP display. No acceptance thresholds were changed in this block.
- Camera requests 1280x960 RGBA; actual crop and rotation come from ImageProxy.
- Pixel sensor timestamps use the realtime clock, matching elapsedRealtimeNanos.
- Warm-up `[0, 5000)` ms from session creation; bounded 512-sample rolling
  steady-state latency quantiles; queue depth at most one pending copied frame.

## Automated evidence

`PipelineTest` adds eight JVM checks to the existing domain/inference suite:
slow detector/latest-frame replacement and exactly-once close, stop and pending
release, fatal-worker cleanup, live/replay equality, explicit tick expiry,
stop/reset/restart token rejection, pre-reset capture rejection, RGBA stride/crop
and channel order, bounded metrics, and camera timestamp origin mapping.

`ForegroundSessionTest` runs on the actual phone. It covers repeated real-camera
inference in driving and diagnostic modes, reset while inference is asynchronous,
background/resume with explicit restart, clearing estimates, fatal coordinator
error and explicit recovery. The existing adapter fault-injection test separately
checks an actual failing TensorRunner closes its native resources exactly once.
An empty camera-selection fixture exercises CameraX's unavailable-camera path.
Permission denial/revocation is exercised in separate processes: revocation can
terminate the target process and its instrumentation. Camera tests require an
awake, unlocked phone. A locked/Dozing Pixel can accept CameraX binding without
producing analysis frames; the no-results watchdog ends that session safely.

The diagnostic geometry probe compares the full ROI and two asymmetric boxes
with CameraX ImageProxy-to-PreviewView transforms on the phone. A short observed
run measured **0.125 px** maximum edge error (2 px gate). This verifies preview
coordinates, independently of whether the room contains recognizable road signs.
Existing inference fixtures also cover rotation, letterboxing and inverse mapping.

Example short diagnostic sample (8 post-warm-up frames, not sustained performance):

| Metric | Observed value |
|---|---:|
| Total analyzed frames | 14 |
| Warm-up analyzed frames | 6 |
| Steady analyzed FPS | 1.55 |
| Copied frames replaced in app queue | 25 |
| Inference p50 / p95, including preprocessing/decode | 718 / 758 ms |
| Sensor capture-to-result p50 / p95 | 1285 / 1440 ms |
| Thermal status at sample | 0 (none) |

FPS counts the full steady interval; quantiles contain only the latest 512
steady samples. Queue drops exclude unreported CameraX-internal drops. Latency
includes frame age from backpressure. These numbers vary with device load and
must not replace block 9's 20-minute sustained/thermal measurements.

## Operating limits and remaining work

### Developer-reported outdoor check

Reported 2026-11-11 using the current application: camera and diagnostic mode
worked well outdoors, but speed-limit-30 detections were very unstable and no
30 km/h estimate was confirmed on the HUD. This is a qualitative user report,
not an annotated drive or a measured acceptance result. No recording, per-frame
confidence/association trace, outdoor FPS, distance or lighting metadata was
provided, so the exact rejection reason cannot be established from this check.

The observation is consistent with the known timing mismatch below. Additional
missed detections and unstable box association can also prevent confirmation.
A useful next diagnostic is the number of associated hits within the active
window, with explicit rejection/expiry reasons; candidate visibility alone does
not establish that the temporal confirmation requirements were met.

### Integration verification and acceptance limits

All block 6 tasks are verified on the final installed build. The phone was
unlocked before the final checks; earlier runs on a locked/Dozing phone are not
counted as successful validation.

| Final check | Result |
|---|---|
| ForegroundSessionTest, both tests | Passed, 25.041 s: real camera, repeated start/stop/reset, diagnostic alignment and metrics, background/resume, unavailable-camera selection, fatal error and explicit recovery |
| PermissionSessionTest | Passed, 1.323 s: actual system dialog denied, no session created, actionable recovery shown |
| Permission revoked during active camera capture | Camera service confirmed the rear-camera client; external `pm revoke` terminated its process. Relaunch showed unknown state, stayed idle and required explicit restart. CAMERA was regranted afterward |
| Recovery after permission regrant | Fresh-process foreground camera test passed, 23.660 s |
| Offline cold start and lifecycle | Passed, 23.468 s: airplane mode enabled, Wi-Fi disabled, `Active default network: none`, app force-stopped before the camera test |
| Device cleanup | Wi-Fi and the prior USB sleep setting restored; original airplane/mobile-data settings preserved. DIY HUD reopened idle |

The final device checks use the local application/test APKs. Repeated camera
reopening succeeded after stop and failure, and native close ownership is also
covered by adapter/slow-worker tests. This is not a sustained memory/leak profile
or the 20-minute thermal acceptance run.

Static checks after the diagnostic follow-up: 35 JVM tests passed; Android lint reports 0 errors (16 warnings,
primarily pinned dependency updates and existing manifest/icon recommendations).
The OpenSpec change validates strictly. See the README for rerun commands.

The current model has low detection quality and incomplete class coverage; see
[baseline results](BASELINE_RESULTS.md) and [trained parity](TRAINED_EXPORT_RESULTS.md).
At roughly 1.5 FPS, three observations can span more than the bundled 1000 ms
confirmation window. Consequently a visible sign may never confirm. Calibration
and performance tuning remain required by block 9. The original config v1 is
preserved; the explicit experimental comparison below uses a wider window.

### Follow-up: confirmation progress and config v2

Diagnostics now shows per-track hit counts, IoU association, time-window expiry,
missed detections, confidence rejection, absence rearming and encounter deduplication.
Snapshots are bounded and represent the latest analyzed capture; Reset/Stop clear
the exposed diagnostic state. No image/event recording is added by this change.

An explicitly selected `trained-bundle-v2-confirmation-v2` keeps the exact same
model weights and uses config v2: **3 hits in 3000 ms**. Confidence, association,
absence, expiry and STOP settings are unchanged. The default Gradle build still
selects the original 1000 ms bundle; variant generation/build/rollback commands
are in the Android README. The variant is not calibrated or frozen for acceptance.

Controlled captures at 0/700/1400 ms demonstrate window expiry under v1 and
confirmation under v2, including live/replay agreement at 30 km/h. These are
synthetic timing fixtures, not evidence of improved outdoor recognition quality.
Bundle tests verify unchanged weights/parity, source immutability, new manifest
hash validation and overwrite rejection. Both trained-model Android parity/loader
tests passed on Pixel with the experimental bundle (2.690 s). The original source
parity remains applicable to identical weights/preprocessing, not to temporal
confirmation quality or false transitions.

Follow-up verification: 35 JVM tests, 8 Python bundle tests and the two Android
trained parity/loader tests passed. Build/lint passed and the experimental APK
was installed on Pixel. The real-camera diagnostic UI subsequently passed both
foreground tests on the unlocked phone (24.364 s), including config/window display,
Reset/Stop and state clearing. Block 9.1 remains open: this experiment does not freeze acceptance settings or establish
outdoor false-transition/confirmation-delay metrics.

No real drive, LED/night/rain coverage, complete-event evidence, 20-minute thermal
run or final acceptance is claimed here. The original block 6 wrote no trip logs;
app 0.1.1 now adds Room event storage in block 7.1/7.2. Image sampling remains a
separate task. See [storage checks](EVIDENCE_STORAGE_RESULTS.md).

For reproducible commands and the permission-denial walkthrough, see
[Android README](../android/README.md#camera-verification). Native-model parity and
bundle rejection were verified in block 5 and were not redefined by this change.
