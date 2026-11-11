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

Static checks: 31 JVM tests passed; Android lint reports 0 errors (16 warnings,
primarily pinned dependency updates and existing manifest/icon recommendations).
The OpenSpec change validates strictly. See the README for rerun commands.

The current model has low detection quality and incomplete class coverage; see
[baseline results](BASELINE_RESULTS.md) and [trained parity](TRAINED_EXPORT_RESULTS.md).
At roughly 1.5 FPS, three observations can span more than the bundled 1000 ms
confirmation window. Consequently a visible sign may never confirm. Calibration
and performance tuning remain required by block 9; current settings are preserved
and no road-use success is claimed from this integration check.

No real drive, LED/night/rain coverage, complete-event evidence, 20-minute thermal
run or final acceptance is claimed here. Block 7 adds durable event storage and
bounded opt-in sampling; this block writes neither camera images nor trip logs.

For reproducible commands and the permission-denial walkthrough, see
[Android README](../android/README.md#camera-verification). Native-model parity and
bundle rejection were verified in block 5 and were not redefined by this change.
