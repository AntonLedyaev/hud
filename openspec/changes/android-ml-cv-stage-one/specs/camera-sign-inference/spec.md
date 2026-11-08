## Purpose

Turn live camera frames into timestamped sign observations without blocking the interface or accumulating stale work.

## ADDED Requirements

### Requirement: Explicit foreground session
Camera capture and inference SHALL run only during a user-started foreground session with camera permission and a valid model. Leaving the foreground, stopping, permission loss or a fatal engine error MUST stop capture, release resources and invalidate pending observations.

#### Scenario: Camera permission denied
- **WHEN** the user denies camera access
- **THEN** the app stays usable, explains that recognition is unavailable and does not start a trip

#### Scenario: Background and resume
- **WHEN** an active app leaves the foreground and later returns
- **THEN** the old session is closed and recognition requires a new explicit start with unknown restriction state

### Requirement: Bounded offline processing
Recognition SHALL work without network access after installation of a valid bundled model/runtime. Processing MUST bound pending work to the current frame and at most one latest pending frame, drop superseded frames and keep the interface responsive.

#### Scenario: Camera is faster than inference
- **WHEN** frames arrive faster than they can be analyzed
- **THEN** superseded pending frames are discarded rather than queued and all discarded and processed frame resources are released

#### Scenario: Offline cold start
- **WHEN** a provisioned app is started with network access disabled
- **THEN** recognition starts without downloading a runtime or model

### Requirement: Observation geometry and identity
Each observation SHALL carry session/frame identity, monotonic capture time, class ID, confidence and a box in normalized upright source-image coordinates. Preprocessing and preview mapping MUST account for rotation, crop, padding and display scaling.

#### Scenario: Rotated and letterboxed frame
- **WHEN** a known fixture is processed with rotation and letterboxing
- **THEN** the decoded box maps back to the original sign and the preview overlay stays aligned within the fixture tolerance

### Requirement: Failure isolation
Unsupported acceleration SHALL permit a validated CPU fallback and report the active backend. A model or inference failure MUST be visible, produce no fabricated observations and prevent old detections from changing current state.

#### Scenario: Late result after restart
- **WHEN** a result from a stopped session completes after a new session starts
- **THEN** the result is discarded and cannot update its HUD or evidence stream

### Requirement: Reproducible device measurement
The app SHALL report analyzed frames per second, dropped frames, p50/p95 inference and capture-to-result latency, active backend and thermal status for a sustained run. Reports MUST identify device/OS, app/model/config versions, input resolution, warm-up interval and duration.

#### Scenario: Sustained performance evaluation
- **WHEN** a 20-minute session runs on the selected reference phone
- **THEN** its report separates warm-up from steady state and exposes thermal slowdown and failures against predeclared device acceptance thresholds
