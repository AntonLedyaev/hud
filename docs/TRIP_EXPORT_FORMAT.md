# Trip review and export v1

Android 0.1.3, OpenSpec block 8. Format identifier: `diyhud-trip`,
`schemaVersion: 1`. The Kotlin serialization types and streaming writer/verifier
are in `android/app/src/main/java/dev/diyhud/data/TripArchive.kt`.
Event/observation/config v1 contracts reuse `shared/schemas/` and `Contracts.kt`.

## User workflow

After Stop has flushed recording, open **Trips**, then **Open trip**. Interrupted
trips are also available. Review shows start/end/recovery times, end reason,
model/labels/config/app/backend/device, saved images and ordered events.
Transition/conflict cards show the cause and source input, including class and
confidence where applicable. **Event details** exposes the full event and its
config. Clock ticks are hidden initially; **Show clock ticks** includes them.
Trip pages contain at most 50 rows; event pages at most 100 rows.

**View** previews an upright saved JPEG. All images remain unreviewed. Export
starts with no images selected; check individual frames or explicitly use
**Select all images**. **Export ZIP** opens Android's document picker for a
user-chosen destination. Cancelling creates no successful export. The app has
no Internet permission or automatic upload/training-import path. If the user
chooses a cloud document provider, that provider controls delivery to its service.

Review and recognition exclude each other. A process-wide lease also prevents a
new camera session while an export/delete worker finishes after Activity closure.
The document picker can background the Activity without discarding the export
request; ViewModels retain it across Activity recreation. Process death requires
starting the export again, rather than silently resuming copying.

## Archive layout

```text
events.jsonl
images/<sessionId>_<resetGeneration>_<frameId>.jpg  # explicitly selected only
samples.jsonl
manifest.json
complete.json
```

JSON and JSONL use UTF-8. JSONL contains one JSON object per line, with a trailing
newline on every record. Events retain their stored sequence order and original
session/reset identity, monotonic offsets, model/label hashes, effective config
and payload. No event is rewritten to reference an omitted image: event records
reference frames and source event sequences, not JPEG paths.

`manifest.json` uses these fields (camelCase at this archive layer):

| Field | Meaning |
|---|---|
| `schemaVersion`, `format` | `1`, `diyhud-trip` |
| `exportedAtEpochMs` | Export wall-clock time |
| `header` | Original `SessionHeader`: id, start, app/model version, canonical label snapshot/hash, full effective config, original model manifest JSON string, backend and device |
| `end` | Status (`COMPLETED`/`INTERRUPTED`), nullable true end/recovery times, reason, incomplete flag, last attempted sequence, dropped count and optional sampling counters |
| `events` | `{path: "events.jsonl", sha256, count}` |
| `samples` | `{path: "samples.jsonl", sha256, count}` for selected images only |
| `availableSamples` | Number of locally recorded sample rows at export time |
| `allSamplesIncluded` | Selected count equals available sample count |
| `evidenceComplete` | Full, uninterrupted event trace with matching final sequence; independent of image selection |
| `finalState` | Domain state obtained by replaying the included event stream |

The label snapshot is generated from `shared/labels.yaml`, rather than a second
hardcoded class list. The model manifest records bundle metadata/checksums; model
weights are not copied into the archive. Config snapshots must be compared in
full, rather than relying only on the numeric version.

Each `samples.jsonl` object contains:

| Field | Meaning |
|---|---|
| `image` | Relative path of the included JPEG, exactly matching session/generation/frame identity |
| `sha256`, `byteCount` | Hash and size of that complete file |
| `sourceSequence` | State-event position when this frame's observation was accepted |
| `observation` | Full v1 observation with session, generation, frame, capture time, model, labels hash and detections |
| `config` | Complete session effective config |
| `unreviewed` | `true`; predictions are not ground truth |

Observation/config/event field names retain the existing snake_case contract;
they are not converted to archive camelCase. Detections use normalized upright
frame boxes, not boxes on the raw rotated camera buffer. An empty detection list
means the model found no candidates, not that a human confirmed a negative scene.
Sampling is sparse: no archive claims to contain every camera frame or candidate.

Every included sample must have a valid file, matching size/hash and matching
session/model/labels/config. Missing/corrupt selected images fail export; the app
does not silently omit them. An incomplete event trace may lack a sample's source
event; its observation remains self-contained, and the trace stays incomplete.

## Completeness and failure handling

`complete.json` contains `{schemaVersion: 1, manifestSha256: "..."}`, hashing the
exact manifest bytes. It is written last, after event/sample hashes and every
selected image succeed. Success is reported only after the ZIP stream closes.
The document destination is journaled before writing; failed writes or next
startup/review attempt to delete that newly created partial document. The app
never overwrites an existing user-selected trip export to resume it.

A provider may refuse deletion or lose its temporary URI grant after process
death. Review then reports unfinished-export cleanup, offers retry, and explains
that the user can remove the file in Files before dismissing the warning.
A partial file is never presented by the app as a successful export.

`TripArchive.verify(File)` requires a readable complete ZIP, the completion marker,
matching manifest/event/sample/image hashes and sizes, unique expected paths,
matching provenance and counts, and a replayed state/completeness matching the
manifest. Missing entries, trailing truncation or altered contents fail.
Checksums detect accidental corruption; they are not an authenticity signature.
There is no automatic archive-import feature.

For replay, initialize `RestrictionState` from `header` and fold `events.jsonl`
through the production `RestrictionReducer` in sequence order. Before applying
an explicit gap or sequence discontinuity, invalidate current estimates and mark
the trace incomplete. A missing tail, interrupted trip or incomplete footer also
prevents a complete claim. Audit transition/conflict records preserve source links
and recorded state details; they do not introduce another recognition decision.
Never sort by wall-clock time or replay selected image predictions as confirmed
events. Ticks and resets are required replay inputs.

## Local deletion

**Delete local trip** requires confirmation and explicitly explains that exported
copies are outside the app's control. The app first stores `DELETING`, then
removes only that trip's owned JPEG/partial files, then deletes the session with
cascading events/sample rows. Failed file deletion retains metadata for retry.
Startup/review retries pending deletions; the list also offers **Retry deletion**.
Only a deletion already requested by the user is retried automatically.

SQLite checkpoint/compaction reclaims storage after deletion when possible. A
compaction failure is reported separately from successful evidence removal.
There is no secure-erasure guarantee for flash storage. Other trips and external
exports are retained. Recognition still never deletes old evidence to make space.
Room remains schema v2; this block changes queries/status values, not table shape.

## Annotation handoff

1. Stop, review completeness/provenance, select useful images and export explicitly.
2. Copy the ZIP to the PC under ignored `ml/data/trip-exports/`; retain the original archive and verify it
   before using extracted files. Raw images remain excluded from the repository.
3. Inspect images and manually annotate class IDs/boxes using canonical labels.
   Review missed signs and empty predictions as well as positive detections.
4. Preserve session/frame IDs and the source archive, model/config and labeling
   provenance in annotation metadata. Split by independent trips/scenes to avoid
   near-duplicate leakage; selected frames from one walk are not independent tests.
5. Feed reviewed annotations into an explicit dataset-preparation step. No app
   screen or export automatically promotes predictions to ML training labels.

Complete event evidence proves reproducibility of the recorded assistant state,
not recognition accuracy or complete drive ground truth. Block 9 still requires
calibration, independent held-out data, sustained device testing and annotated
drive acceptance.

## Checks

`TripArchiveTest` covers exact production replay, explicit selection/omission,
interruption/gaps/missing tails, corrupt/mismatched images, missing/tampered ZIP
entries, truncation and output failure. `EvidenceAccessTest` covers recognition
exclusion and finishing-operation leases after owner closure.

`TripReviewTest` covers Room review/source links, closed-vs-active access, export
with/without images, path containment, interrupted recovery, failed deletion and
restart retry, retained unrelated trips/external copies and export-destination
cleanup. `TripReviewUiTest` exercises ViewModels/Activity recreation, synthetic
image preview/selection/export/deletion. `SampleSessionTest` also checks that
review is rejected during an active inference session. See `android/README.md`
for commands and `docs/TRIP_REVIEW_RESULTS.md` for actual run results.
