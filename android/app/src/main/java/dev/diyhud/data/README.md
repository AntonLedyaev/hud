Local trip evidence lives in Room (`hud-evidence.db`, schema v2). The generated
schema is in `app/schemas/`; no destructive migration fallback is enabled.

`SessionHeader` stores identity, wall-clock start, app/model/backend/device,
canonical labels, full effective config and the original model manifest.
`LiveSession` produces an ordered input/audit stream: confirmed batches, resets,
ticks, errors, transitions and conflicts. Audit source sequences identify the
input that changed state; audit details contain the resulting state. Audit
records themselves do not make another restriction decision.

`EvidenceWriter` has one bounded FIFO (bundle default: 256 events). Producers
never wait for disk. Overflow drops new events and keeps counters; resumed writes
insert a `gap` at the first missing sequence. A final dropped interval gets a
tail gap if storage still works. Write failure/budget exhaustion disables further
event writes for that session, with no per-event retry. The UI reports incomplete
evidence while recognition continues. Stop drains queued work asynchronously
before enabling a new session.

Storage budget covers the shared database, WAL, SHM and sample files (default: 100 MB), with
128 KiB reserved for SQLite overhead and the final footer. Previous evidence is
never silently deleted. If the footer cannot be committed, the session remains
open and next startup marks it interrupted. Its true end time is unknown;
`recoveredAtEpochMs` is recorded separately.

`RoomEvidenceStore.replay()` folds paginated events with the production reducer.
It marks traces with gaps, inconsistent source metadata, unknown tails or
interruption incomplete. Only a complete closed trace can claim exact full-trip
reconstruction. Current estimates are never restored from the database.

`SampleWriter` is separately bounded to one current and one pending copied RGB
frame. It is default-off, enforces the effective interval/byte budget, and performs
encoding/disk writes off the coordinator. Sampling failures/limits never change
restriction state. Images are unreviewed observations, not training annotations.

`saveSample` writes/syncs a partial file, renames it, then inserts metadata.
Interruption recovery removes managed files without a committed reference and
keeps referenced JPEGs. Footer sampling status is separate from event replay
completeness. A full event trace can coexist with limited/dropped image samples.
Migration 1→2 preserves existing sessions/events; both schemas are versioned.

Trip review/export/deletion remain separate tasks. No video is recorded.
