## Purpose

Present recognized sign estimates clearly during a foreground trip without implying complete knowledge of road rules.

## ADDED Requirements

### Requirement: Honest driving display
The driving view SHALL show the estimated speed limit in km/h, observed overtaking restriction, transient STOP observation and recognition availability. Unknown or stale speed MUST display an explicit unknown marker. The interface MUST identify values as camera-derived estimates and MUST NOT show a GPS or vehicle-speed value in this stage.

#### Scenario: No reliable speed estimate
- **WHEN** a session starts, speed expires or a cancellation clears it
- **THEN** the current limit displays an unknown marker rather than zero or unlimited

#### Scenario: Inference unavailable
- **WHEN** the camera or inference engine fails
- **THEN** recognition is visibly unavailable and the last detected limit is not presented as a current estimate

### Requirement: Simple session controls
The app SHALL provide start, stop and reset-estimate controls. Driving view updates MUST NOT trigger sound, modal dialogs or required interactions. Evidence review and export MUST be accessible only outside an active session.

#### Scenario: New sign during driving
- **WHEN** a sign is confirmed
- **THEN** the view updates without a sound alert, dialog or acknowledgement request

### Requirement: Separate diagnostics
A diagnostic view SHALL expose camera preview with correctly mapped boxes, confidence, observation age and pipeline measurements. The driving view MUST remain readable without diagnostic overlays; display mode MUST be selectable before starting a trip.

#### Scenario: Normal driving session
- **WHEN** the user starts in driving mode
- **THEN** raw candidate boxes and rapidly changing confidence values do not clutter the restriction display

### Requirement: Permission and model recovery
The app SHALL distinguish missing permission, camera unavailable, missing/incompatible model and engine failure, with an actionable recovery path outside an active session. A missing model MUST NOT silently enable simulated road recognition.

#### Scenario: Missing model asset
- **WHEN** the app has no compatible model bundle
- **THEN** start is unavailable, the model problem is explained and no real trip is recorded using fixture detections
