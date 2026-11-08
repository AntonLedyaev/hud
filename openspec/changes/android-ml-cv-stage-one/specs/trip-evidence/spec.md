## Purpose

Keep bounded local evidence of recognition and state changes for debugging, evaluation and explicitly selected future annotation.

## ADDED Requirements

### Requirement: Traceable local trip events
The system SHALL persist session identity, wall-clock start/end, end reason, app/model/labels/config versions, confirmed sign events, conflicts, resets, state transitions and errors. Events MUST carry monotonic offset, source identity, class/confidence where applicable and transition reason. Writes MUST NOT block camera analysis.

#### Scenario: Review a limit change
- **WHEN** a completed trip is opened
- **THEN** the user can trace each estimate change to a confirmed event, reset, conflict or expiry and its model/config version

### Requirement: Optional bounded samples
Image sampling SHALL default to off and require explicit activation before a trip. Enabled sampling MUST associate each image with its frame, model/config and observation metadata, use a declared rate and byte limit, and stop at the limit. Samples MUST be marked unreviewed rather than automatically becoming training labels.

#### Scenario: Sampling disabled
- **WHEN** a trip runs with default settings
- **THEN** structured events are stored but no camera images or video are persisted

#### Scenario: Sampling limit reached
- **WHEN** the configured image storage limit is reached
- **THEN** new image writes stop, the condition is recorded and recognition continues

### Requirement: Explicit portable export and deletion
Outside an active session, the user SHALL be able to export a selected trip as a versioned machine-readable manifest and event stream with explicitly selected images, or delete its records and images. Export MUST use an explicit user-selected destination and MUST NOT upload automatically.

#### Scenario: Export for annotation
- **WHEN** the user exports a trip with selected images
- **THEN** exported events retain ordering, timestamps, model/config provenance and valid references to the included images

#### Scenario: Delete local trip
- **WHEN** the user deletes a completed trip
- **THEN** its local events and images are removed and the interface explains that previously exported copies are unaffected

### Requirement: Storage failure and bounded retention
Persistence SHALL use bounded queues and a declared local storage budget. Storage failures or queue overflow MUST be visible and accounted for as evidence gaps, without stalling recognition. The app MUST NOT silently delete user evidence to create space.

#### Scenario: Device storage exhausted
- **WHEN** event or image persistence fails because storage is full
- **THEN** the app reports incomplete evidence, disables failing writes and continues inference without an unbounded retry queue

### Requirement: Interrupted session recovery
On startup, previously open sessions SHALL be marked interrupted and remain reviewable; their recognition state MUST NOT resume automatically. Partial image/export files MUST NOT be advertised as complete evidence.

#### Scenario: Process termination during a trip
- **WHEN** the app restarts after termination without a normal stop
- **THEN** committed events remain available in an interrupted trip and a new trip begins with unknown restriction state
