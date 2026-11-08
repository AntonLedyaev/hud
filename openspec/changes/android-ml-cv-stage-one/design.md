# Design

## Context

See [proposal.md](proposal.md) for motivation, capability boundaries and delivery order. Android currently has a README only; ML has an environment and smoke test, not a trained road-sign detector. The five capability specs describe the required behavior.

The developer selected simple Android layers and a YOLO baseline on 2026-11-06. This design spans training, model packaging, camera processing, state and persistence, so a shared contract is necessary even though the app remains a single Gradle module.

## Goals / Non-Goals

**Goals:** Keep the first implementation understandable, run the same state logic on live and replayed events, make model/export errors diagnosable, and keep slow inference/storage off the UI thread.

**Non-Goals:** A reusable ML framework, a generic plugin system, multiple Gradle feature modules, Kotlin Multiplatform, or an accelerator-specific hardware design. Extract modules only when actual coupling or build costs justify it.

## Decisions

### 1. One Android app module with package-level layers

Use one `:app` module under `android/`, with these responsibilities:

| Package | Responsibility | Dependency boundary |
|---|---|---|
| `ui` | Compose screens, ViewModel, immutable display state | Calls session actions; observes state, never camera buffers |
| `session` | Start/stop/reset, pipeline lifecycle and ordered event delivery | Coordinates camera, inference, domain and evidence |
| `camera` | CameraX binding, frame ownership and geometry | Supplies frames and transforms; knows no restriction rules |
| `inference` | Bundle validation, LiteRT adapter, preprocessing and YOLO decoding | Emits domain observations; knows no UI or database |
| `domain` | Temporal confirmation and pure restriction-state reducer | Kotlin data/functions; no Android, LiteRT or Room imports |
| `data` | Room, image files, export and evidence budgets | Persists domain events; does not decide restrictions |

Compose renders an immutable `StateFlow` snapshot from the ViewModel. StateFlow is for the latest UI state, not a durable event log: evidence receives ordered events through a separate bounded queue. Wire concrete dependencies in a small application/session composition root; no DI framework is needed initially. Use interfaces at the detector, clock and evidence boundaries where fixtures or replay need substitution.

Alternatives considered: separate Gradle modules improve enforced isolation but add build/configuration overhead; putting everything in an Activity is initially shorter but couples resource ownership, inference and state. Package boundaries retain testable pure Kotlin logic with little infrastructure.

### 2. YOLO as a single-stage baseline

Start with a small YOLO detector that directly predicts the twelve canonical classes. Select and pin the exact YOLO family/version, input resolution and checkpoint in the first training/export experiment; do not imply that a newer or larger model is automatically better. The experiment must prove export compatibility before a long training run.

A generic sign detector plus crop classifier remains a possible later experiment if small-sign recall or class confusion warrants it. It introduces two models, two preprocessing contracts and compounded errors, so it is not the initial implementation. Nano versus small, resolution and precision are benchmark variables within the selected YOLO approach. A change to the two-stage approach requires a new explicit decision.

Prepare full-scene detection data from the sources described in the context, but audit access, annotations and class coverage first. Classification-only crops are not treated as full-scene detection examples. Keep split manifests grouped by drive/sequence, include negative scenes, and freeze validation-selected thresholds before testing. Record configuration, seed, source/checkpoint hashes and tool versions per run.

### 3. Versioned model bundle as the ML/Android boundary

Produce a local bundle containing `model.tflite`, a manifest and a generated labels mapping. The manifest covers the inference contract in `sign-model-bundle/spec.md`, including artifact hashes and explicit output decoding/NMS ownership. Android generates its expected mapping from the canonical YAML during build preparation and rejects incompatible bundles before opening a session. No manually maintained parallel class list is introduced.

Use ONNX as the portable export/interchange artifact for future consumers and evaluate conversion to TFLite in the early export experiment. Pin the converter toolchain and retain parity reports; do not assume an arbitrary ONNX graph is automatically supported by the mobile runtime. Model tensors, color order, resize/padding, normalization and box transforms are checked against shared reference fixtures. Apply NMS exactly once, at the location declared by the bundle.

Bundle the validated runtime and model in the installed APK for offline cold starts. Keep weights outside git and stage them from a checksum-verified local build input; document that input in the Android setup instructions. Debug fixtures use an explicit test/demo entry point and cannot silently substitute for a missing production model.

Use a narrow LiteRT adapter with CPU as the correctness baseline. Evaluate GPU on the reference phone and retain it only if parity and sustained performance pass; fall back to validated CPU execution when acceleration is unavailable. Select exact SDK/minimum Android versions and runtime API in the compatibility experiment and pin them before app integration. Neither NNAPI nor a particular delegate is an architectural dependency.

### 4. One ordered foreground pipeline

```text
CameraX --> frame + transform --> LiteRT/YOLO --> observations
                                                   |
                                                   v
                                      temporal confirmation
                                                   |
                                                   v
actions + clock ticks ----------------------> state reducer
                                                   |
                                  +----------------+----------------+
                                  v                                 v
                           UI StateFlow                      evidence queue
                                  |                                 |
                                  v                                 v
                               Compose                       Room + files
```

Bind camera capture to the active foreground session. Use CameraX latest-frame backpressure, one inference worker and no extra unbounded coroutine queue. Release every acquired image in a `finally` path; if a frame is copied, release the camera image after copying and bound the copied buffers too. Maintain upright source coordinates and a separate source-to-preview transform so preview cropping does not alter model coordinates.

The session coordinator owns a session ID and reset generation. Every dispatched frame carries both; the reducer rejects late results from older sessions/generations and out-of-order frame times. Start validates the bundle and permission before activating capture. Stop, backgrounding and fatal errors invalidate the generation first, then unbind capture and close the worker/runtime after outstanding work finishes. A manual reset clears confirmation and state without creating a new trip.

Use a monotonic session-relative timebase for event order, confirmation and expiry; wall time is metadata only. Explicit clock ticks advance expiry even when no detections arrive. Record the mapping from camera timestamps to the session timebase rather than assuming different clock origins match.

### 5. Separate observations, confirmation and estimates

Associate candidates spatially over a bounded time window and count distinct frames only. Configuration contains confidence thresholds, minimum hits, association tolerance, confirmation window, rearm absence and expiry intervals. Version the complete effective configuration and retain it with trip evidence; a version string alone is insufficient for replay.

Emit one confirmed encounter event per continuously visible sign. Deduplication prevents repeated state transitions and STOP alerts from the same encounter. A newly confirmed encounter after rearming can refresh the estimate. Expiry is measured from the last accepted confirmation, without a hidden per-frame refresh that would make event replay diverge.

The pure reducer consumes confirmed batches, reset actions and ticks. It updates speed, overtaking and transient STOP independently according to `restriction-state/spec.md`. Resolve incompatible updates within a batch as unknown; do not pick a lane by highest confidence. Unknown and stale are explicit states with provenance, not special numeric speeds. An ended restriction does not establish a legal default. This is an observation-based estimate, not a road-rule engine.

### 6. Bounded evidence with replayable provenance

Use Room for sessions and append-only ordered events, with a per-session sequence number, monotonic offset and model/config identities. Store transition inputs and reasons, including resets, confirmation batches and expiry-driving ticks needed for replay. Store the effective config and bundle metadata with each session. Record backend changes and evidence gaps explicitly.

Optional image samples are files in app-private storage referenced by metadata rows. Copy selected image data before releasing its frame, encode on a separate bounded worker, and enforce sampling rate and byte budgets. Write via temporary files and only mark images complete after finalization; recover abandoned partial files at next startup. Default sampling is off.

If the evidence queue or storage fills, recognition continues while the UI reports incomplete evidence. Use sequence gaps and dropped-event counts; persist a gap marker once storage recovers. Do not claim deterministic full-trip replay across a missing interval. Mark unfinished sessions interrupted on next startup without restoring their estimates.

Export completed trips to a user-selected destination as a versioned manifest, ordered JSONL event stream and selected image files. Include config snapshots, hashes and a completeness indicator; allow export without images. Local deletion handles both database rows and files with retryable cleanup. It does not imply deletion of previously exported copies.

## Risks / Trade-offs

- Small or distant signs and incomplete class coverage -> audit examples per class, measure by sign size, and treat missing coverage as a failed acceptance gate.
- Adjacent lanes, supplementary plates and unsupported signs -> include negative/conflict fixtures, expose unknown, and document the operating envelope. Temporal agreement alone cannot prove lane applicability.
- Heat, frame drops and quantization error -> run 20-minute device tests and export parity checks; accept accelerators/quantization only on measured evidence.
- Estimated state survives an unseen road-context change -> expose age and stale state; defer map/intersection reasoning rather than inventing defaults.
- Package boundaries are not compiler-enforced modules -> keep domain tests free of Android dependencies and review imports before considering module extraction.
- Raw samples are not training labels -> retain provenance and explicit human annotation as a separate later step.

## Migration Plan

There is no existing Android deployment to migrate. Follow the six delivery milestones in the proposal, beginning with contract fixtures and the YOLO export/compatibility experiment. Each milestone should end with a runnable build, replay check or evaluation report.

Before final evaluation, record a reference-device profile and acceptance sheet with concrete per-class quality, false-transition, confirmation-delay and sustained-latency/FPS thresholds, minimum sample counts and the supported operating envelope. Tune on validation data only. The current planning artifacts do not claim a measured performance result or complete Stage 1 acceptance.

Ship only checksum-verified model/config pairs. For a failed new bundle, retain the previous validated pair for a rebuild; with no previous pair, disable recognition with a clear error. New sessions start unknown after a model change. Future database upgrades require explicit migrations rather than destructive replacement of recorded trips.

## Open Questions

- Reference phone/Android version and its measured CPU/GPU performance: resolve before selecting runtime versions and completing the device acceptance sheet.
- Exact YOLO checkpoint, resolution and export precision: select within the baseline experiment using validation results and parity checks.
- Confirmation, expiry and evidence budget values: calibrate and freeze them in versioned configuration before the final held-out evaluation.

These are compatibility and calibration inputs within the selected architecture; they do not authorize a broader model or app architecture change.

## Technical References

- [CameraX image analysis](https://developer.android.com/media/camera/camerax/analyze): latest-frame backpressure, frame release and lifecycle integration.
- [LiteRT for Android](https://developers.google.com/edge/litert/android): runtime integration options; verify the selected API and device compatibility in the implementation experiment.
