## Purpose

Maintain a deterministic estimate of observed restrictions while distinguishing uncertain, stale and unsupported road information.

## ADDED Requirements

### Requirement: Temporal confirmation
Observations SHALL require a configurable confidence threshold and repeated spatially associated evidence from distinct frames within a monotonic time window before confirmation. The configuration MUST define minimum hits, window, association tolerance and rearm absence time; repeated frames MUST NOT count twice.

#### Scenario: One-frame false positive
- **WHEN** a sign appears in fewer distinct frames than the minimum hits
- **THEN** it never emits a confirmed sign event or changes restriction state

#### Scenario: Persistent sign
- **WHEN** the same sign remains visible across many frames after confirmation
- **THEN** it emits one event until it has been absent for the configured rearm interval

### Requirement: Explicit initial and reset state
Each new session SHALL start with unknown speed and unknown overtaking status. Manual reset MUST clear state, pending confirmations and transient signs. Previous sessions, out-of-order observations and pre-reset in-flight results MUST NOT restore an old estimate.

#### Scenario: Restart after a known limit
- **WHEN** a session with an estimated limit of 80 ends and a new session starts
- **THEN** the new session displays unknown until a new sign is confirmed

### Requirement: Speed estimate transitions
A confirmed supported speed sign SHALL replace the speed estimate. A confirmed end-of-80 sign MUST clear an estimated 80 but MUST NOT clear another known value. End-of-all-restrictions MUST clear the speed estimate. Clearing MUST yield unknown rather than an inferred legal default or unlimited speed.

#### Scenario: Replacement
- **WHEN** a 50 sign is confirmed after a known 80
- **THEN** the estimate becomes 50 with the new event as its source

#### Scenario: Matching and nonmatching cancellation
- **WHEN** end-of-80 is confirmed
- **THEN** estimated 80 becomes unknown, estimated 50 remains 50, and unknown remains unknown

### Requirement: Overtaking and transient STOP
Confirmed no-overtaking SHALL set overtaking status to observed-restricted. Its end sign or end-of-all-restrictions MUST clear that estimate to unknown. STOP MUST be a transient observation with a configured display expiry and MUST NOT modify speed or overtaking state.

#### Scenario: Independent restrictions
- **WHEN** end-no-overtaking is confirmed while speed is 50 and overtaking is observed-restricted
- **THEN** overtaking becomes unknown and speed stays 50

#### Scenario: STOP expiry
- **WHEN** the configured STOP display interval expires
- **THEN** the STOP indicator disappears without modifying persistent restriction estimates

### Requirement: Ambiguous observations
Simultaneously confirmed incompatible changes to the same restriction field SHALL invalidate that field to unknown and record a conflict. Confidence alone MUST NOT select which sign applies to the vehicle's lane. Unsupported observations MUST NOT create supported restriction transitions.

#### Scenario: Conflicting speed signs
- **WHEN** 50 and 80 are confirmed in the same confirmation batch
- **THEN** speed becomes unknown, the conflict is logged, and unrelated overtaking state is preserved

### Requirement: Freshness and deterministic replay
Known estimates SHALL retain source event and age, become stale after a configured maximum age and cease to appear as current. Empty frames MUST NOT immediately cancel estimates. Ordered events and explicit clock ticks replayed with the same configuration MUST produce identical states and transition reasons.

#### Scenario: No sign in the next frame
- **WHEN** a confirmed sign leaves view before the maximum age
- **THEN** its estimate persists with increasing age

#### Scenario: Stale estimate
- **WHEN** an estimate reaches its maximum age without fresh confirming evidence
- **THEN** it is marked stale and the current-value display becomes unknown while the last observation remains available in diagnostics
