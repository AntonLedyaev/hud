# Local evidence storage checks

Date: 2026-11-11. Scope: OpenSpec block 7; apps 0.1.1–0.1.2.
Room 2.8.5 / KSP 2.2.21-2.0.5, Pixel 6a / Android 16.
Installed comparison: unchanged YOLO weights, experimental config v2 (3 hits / 3 s).

## Recorded data

Session metadata retains app/model/config/backend, canonical labels, the original
model manifest, wall-clock start/end and reason. Each event retains identity,
monotonic offset, sequence, full config and payload. Confirmed batches retain
frame IDs, classes, confidence and boxes. Audit transitions/conflicts retain
source sequences and resulting state JSON. They do not make additional domain
decisions. Explicit ticks, resets and errors remain replay inputs.

The writer FIFO is bounded by the effective config (256 by default). It never
waits for disk on producers. Overflow drops new records and updates loss counters;
resumed or final writes add a gap marker at the first missing sequence. Gap
generation follows the last committed generation, so a lost Reset cannot hide
the gap from the preceding state. Missing intervals invalidate replay estimates
and prevent full-trip completeness claims.

Storage checks account for DB/WAL/SHM and, since 0.1.2, sample files under the configured 100 MB budget, with
128 KiB reserved for SQLite overhead/footer. A failed writer disables further
event writes, reports incomplete evidence and does not repeatedly retry inputs.
No existing session or event is deleted to make space. If a final footer cannot
be saved, the OPEN session becomes INTERRUPTED on the next process startup.
Its true end time stays unknown; recovery time is a separate field.

## Verification

- Six JVM writer scenarios: blocked disk and bounded producer latency, overflow
  followed by recovery, trailing gaps, budget failure with ongoing live
  recognition, database-open failure, executor rejection and a gap spanning a
  lost Reset. Existing domain/inference tests also pass with audit events.
- Four on-device Room scenarios: every input/audit kind and source link with
  exact live/replay equality; committed events surviving SQLite close/reopen and
  interruption recovery; exhausted budget retaining prior evidence; explicit
  gaps preventing a false complete-replay claim.
- Both foreground camera tests passed with Room recording (the combined six-test
  device run took 25.675 s). Repeated start/stop/reset, diagnostic snapshots,
  background/resume and fatal-error recovery also verify that normal closed
  real-camera sessions contain events and replay as complete.
- Final checks: 41 JVM tests passed; build/lint passed (0 errors). The final four
  Room tests also passed after gap-generation hardening (0.309 s).
- Schema v1 is generated under `android/app/schemas/` for version control;
  no destructive migration fallback is configured.

This is not a sustained 20-minute storage/thermal run or a physical full-disk
destructive test. Disk failure, overflow and budget conditions use deterministic
fixtures. The initial 7.1/7.2 implementation wrote no images. The follow-up below
adds explicit bounded sampling/recovery; trip review/export/deletion and final
Stage 1 acceptance remain open. Candidate-progress diagnostics are not a durable
per-frame detector trace.

## Sampling and recovery follow-up (0.1.2)

Selection is default-off and requires activation before each trip. Effective
config records 1000 ms minimum capture interval and a 10,000,000-byte JPEG budget.
The encoder holds one current and one pending copied frame. Rate skips, queue
drops, saved bytes, budget stop and failure are accounted separately. Encoding
failure or an image limit never stops inference; the HUD reports sampling status.
The global storage budget includes sample files. A complete event log does not
imply every camera frame was saved.

JPEGs are upright, linked to session/generation/frame/capture time, observation,
model/labels, effective config and source event sequence. SHA-256/size are
verified on fixtures. Every sample is unreviewed; model boxes/confidence and empty
predictions are not ground-truth annotations. Manual review/annotation and an
explicit data-preparation step are required before ML use.

Migration 1→2 was validated against both generated Room schemas, retaining a
legacy session and event. Simulated termination leaves an OPEN session, a
committed image/event, one partial image and one renamed image without metadata.
After database reopen/recovery, the trip is interrupted, committed evidence
survives, only incomplete/uncommitted image files are removed, and new estimates
start unknown. This fixture models a process interruption; it is not a power-loss
or filesystem-corruption guarantee.

Five additional JVM sampling tests cover no-copy/no-worker when off, slow encoder
backpressure, rate/byte caps, failure without retries and worker rejection.
Four image/storage/migration tests plus one full session/model test use synthetic
RGB. The session test proves opt-in persists a source-linked image, Stop flushes
it, and the next trip saves no images without another activation. Together with
four existing Room and two foreground tests, the Pixel run passed **11 tests**
in **27.799 s**. Build/lint and all JVM tests passed.
The final JVM total is **46**. A follow-up device provenance check passed all
four event/replay tests (0.377 s) and printed the verified source links below.

## Example transition provenance and completeness

`EvidenceStorageTest.completeTripStoresAllInputAndAuditKindsAndReplaysExactly`
builds a controlled trip, verifies each audit's input sequence and full
model/labels/config snapshot, and compares the stored replay with live state.
The fixture uses minimum_hits=1 and short expiry to exercise transitions; these
are test inputs, not production calibration.

| Audit sequence | Source sequence | Reason | Source payload |
|---:|---:|---|---|
| 1 | 0 | speed_confirmed | confirmed_batch |
| 5 | 4 | speed_conflict | confirmed_batch |
| 9 | 8 | speed_confirmed | confirmed_batch |
| 13 | 12 | speed_stale | tick |
| 17 | 16 | recognition_error | error |

The selected model ID and complete effective config are present on every event
and in session metadata. Audit details include resulting state; sign batches
retain class/confidence/box and source frame. The test also checks source links
never point forward or to another audit. This demonstrates traceability without
claiming a real outdoor drive or all-class quality.

For complete closed event traces, paginated replay equals the uninterrupted live
state. A gap, discontinuous sequence, failed footer/unknown tail or interruption
prevents that completeness claim and invalidates estimates across missing input.
Image limits/drop counters and unreviewed status are separate from this event
completeness flag. Selected images cannot reconstruct every missed candidate or
serve as complete drive ground truth. No automatic upload or training import is
performed. Review/export/deletion are the next block.

Commands and UI status semantics: [Android README](../android/README.md#local-event-journal-011).
