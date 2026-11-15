# Trip review, export and deletion checks

Date: 2026-11-15. OpenSpec block 8, Android **0.1.3 (4)**.
Reference device: Pixel 6a / Android 16, API 36.
Bundle: unchanged YOLO weights, experimental config v2, 3 hits / 3000 ms.
Opt-in sampling still produces its explicit per-session effective config.

## Delivered behavior

Outside recognition, **Trips** lists closed/interrupted trips and pending deletion
cleanup. Details distinguish complete event traces from interruption, loss and
unknown tails. Model/config/labels/app/device provenance is retained; event
cards show source causes and full JSON. Existing saved images can be previewed.
Images default to unselected for export and remain unreviewed observations.

The document picker chooses the ZIP destination. All recorded events and only
checked images are exported with full provenance. Streaming hashes and a final
completion marker detect partial/corrupt archives; the verifier also replays the
production reducer. A complete ZIP can deliberately contain an incomplete trip;
those are separate states. There is no automatic upload or training import.

Deletion first journals intent, removes owned images, then cascades database
rows. A failed deletion retains retry metadata; startup/review can finish it.
Previously exported copies and unrelated trips remain intact. Export failure
likewise attempts cleanup of its newly created document and retains a warning
if the provider refuses cleanup. See [format and handoff](TRIP_EXPORT_FORMAT.md).

## Checks performed

- **54 JVM tests passed**, including six archive scenarios and two access-gate
  scenarios. Archive fixtures verify exact final replay and every recorded audit
  state, full config/provenance, selected/no-image exports, gaps/interruption and
  missing tails, corrupt or mismatched selected images, omitted/tampered entries,
  ZIP truncation and failing output. The gate prevents recognition during review
  and during an operation still finishing after its Activity owner closes.
- **16 tests passed on the physical Pixel**, in **23.836 s**:
  `TripReviewTest` (4), `TripReviewUiTest` (1), `SampleSessionTest` (1),
  `SampleStorageTest` (4), `EvidenceStorageTest` (4), `ForegroundSessionTest` (2).
- The new storage checks exercise complete/interrupted review, ordered causes,
  export with and without selected images, exact exported replay, missing
  selection/path rejection, failed-delete status retained then cleaned on
  restart, unrelated-trip preservation, successful external-copy preservation,
  failed-export cleanup and a crash-left destination journal.
- Activity/ViewModel integration checks default-empty image selection, decoded
  upright synthetic JPEG preview, Activity recreation preserving review,
  export of one chosen image and deletion of only its generated fixture trip.
  The session test additionally rejects opening review during active inference.
  Existing real-camera start/stop/reset/background/diagnostics/recovery tests
  pass with the access gate and Room recording enabled.
- Debug APK, instrumentation APK and lint passed: **0 errors, 19 warnings**.
  Remaining warnings include dependency updates, existing platform/style notices
  and synchronous preference-commit style suggestions. The export journal uses
  checked commits on its background worker to persist cleanup intent.
- Installed app/test updates with `adb install -r`, preserving existing trips.
  USB screen-stay-awake was temporarily enabled for tests and restored afterward.

The tests create synthetic RGB, isolated databases/cache directories and one
uniquely named synthetic trip in the app database, deleting only their fixtures.
They do not export, inspect or delete the user's recorded walking images.
Archive failure and deletion failure are injected fixtures, not destructive
full-disk/power-loss tests. Export delivery fixtures use file URIs; cloud and
third-party document-provider behavior has not been exhaustively tested. The
production UI uses Android's standard CreateDocument contract.

## Remaining acceptance

These checks complete the evidence-management block, not model-quality or
Stage 1 acceptance. The outdoor 30-sign issue remains; no retraining is included
in 0.1.3. Block 9 still requires frozen calibration/acceptance thresholds,
independent held-out scenes, the sustained 20-minute run and annotated drives.
Unreviewed model predictions must be manually checked before any ML dataset step.
