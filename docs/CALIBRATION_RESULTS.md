# Stage 1 calibration preparation

Date: 2026-11-15. OpenSpec **9.1 in progress**, not complete.
The current app remains 0.1.3; no model/config/APK replacement was performed.

## Inputs and reproducibility

The diagnostic uses the original saved source-model predictions on the approved
provisional GTSDB validation split: 300 scenes, 156 canonical sign instances,
179 negative scenes. Sequence independence is unknown. These images were already
used to select the 640 baseline; they cannot become the independent final test.

- Candidate model: `gtsdb-yolo11n-640-v2`, unchanged YOLO11n weights.
- TFLite SHA-256: `9080774bb6f8e0cc704d9d76e7de5f68218e3006a28125b3b36ef57e3bc20060`.
- Checkpoint SHA-256: `00a9cc47de2e7c7168c78609fe546f5cf6482aaff11afa05bb31f308503dac20`.
- Candidate bundle: `ml/runs/trained-bundle-v2-confirmation-v2/`.
- Original report/predictions: `ml/runs/gtsdb-baseline-640-v2/evaluation/`.
- Dataset manifest: `ml/data/gtsdb-official-v1/manifest.json`.
- Diagnostic output: `ml/runs/calibration-v1/confidence-with-provenance.json` (ignored).
- Candidate config: confidence 0.25, 3 hits / 3000 ms, association IoU 0.5,
  rearm absence 2000 ms, estimate expiry 60000 ms, STOP display 3000 ms.

`ml/training/calibrate.py` verifies canonical labels, model checksum, checkpoint
and dataset provenance, validation membership and reproduction of the original
per-class TP/FP/FN before sweeping thresholds. The output hashes its input files,
bundle manifest, tool and draft acceptance sheet, and retains the full candidate
config. It refuses overwriting a previous result or tuning on a final report.

From the repository root, choose a new output filename for each run:

```powershell
ml/.venv/Scripts/python.exe ml/training/calibrate.py --evaluation ml/runs/gtsdb-baseline-640-v2/evaluation --bundle ml/runs/trained-bundle-v2-confirmation-v2 --dataset-manifest ml/data/gtsdb-official-v1/manifest.json --output ml/runs/calibration-v1/confidence-new.json
ml/.venv/Scripts/python.exe -m pytest ml/training/test_calibrate.py ml/training/test_evaluation.py -q
ml/.venv/Scripts/python.exe -m ruff check ml/training/calibrate.py ml/training/test_calibrate.py
```

Sixteen targeted tests pass. They cover matching/duplicate/negative counts,
threshold trade-offs, preserving uncovered classes, insufficient class samples,
missing AP, invalid predictions, input/model corruption, mismatched membership,
count/provenance checks and rejecting final-test tuning. Ruff passes.

## Confidence comparison

One-to-one same-class IoU >= 0.5, as in the original source evaluator:

| Confidence | TP | FP | FN | Precision | Recall | Micro F1 |
|---:|---:|---:|---:|---:|---:|---:|
| 0.25 | 35 | 79 | 121 | 0.307 | 0.224 | 0.259 |
| 0.35 | 26 | 43 | 130 | 0.377 | 0.167 | 0.231 |
| 0.50 | 17 | 22 | 139 | 0.436 | 0.109 | 0.174 |
| 0.65 | 12 | 9 | 144 | 0.571 | 0.077 | 0.136 |
| 0.80 | 3 | 6 | 153 | 0.333 | 0.019 | 0.036 |

For `speed_limit_30` (31 validation instances):

| Confidence | Precision | Recall |
|---:|---:|---:|
| 0.25 | 0.326 | 0.452 |
| 0.35 | 0.400 | 0.452 |
| 0.50 | 0.500 | 0.387 |
| 0.65 | 0.667 | 0.323 |
| 0.80 | 0.429 | 0.097 |

Higher confidence generally reduces false detections while discarding additional
true signs; it cannot recover missed signs or teach cancellation classes. The
best tested global micro F1 is at the existing 0.25, and every threshold fails
at least one draft target for every canonical class. This is a diagnostic result,
not a choice of the final operating point. A different objective can favor a
different threshold. No automatic per-class config was introduced.

The saved predictions were generated with confidence >= 0.25. Lower-threshold
analysis requires a new source inference run; the tool rejects inventing it by
filtering these files. Raising the threshold is valid for filtering the saved
post-NMS predictions, but this is not whole-corpus Android/LiteRT inference or
proof that source/runtime preprocessing and NMS are identical. Existing fixed
fixture export parity is documented in `TRAINED_EXPORT_RESULTS.md`.

## Proposed acceptance sheet

Machine-readable targets: `ml/training/acceptance-draft.json`.
Status is explicitly **draft_not_frozen**. These are proposed engineering gates,
not measured calibration outcomes, an approved operating envelope or a model
quality certification. They must be reviewed/fixed before final testing.
Class gates apply separately to every ID generated from `shared/labels.yaml`;
there is no second handwritten taxonomy or aggregate escape hatch.

| Area | Draft gate |
|---|---|
| Every class | >=30 annotated instances, precision >=0.90, recall >=0.80, mAP50–95 >=0.50 |
| Final corpus | >=100 negative scenes, >=5 independent drives, >=60 supported state minutes, sequence-disjoint splits |
| Supported envelope | Printed German/EU canonical signs in daylight; retain small-sign results; no approved size exclusion |
| State | False transitions <=1/hour, p95 confirmation delay <=4000 ms, agreement >=0.90, unknown <=0.10, stale <=0.05 |
| Reference device | Pixel 6a / API 36 / validated CPU backend; >=20 minutes, first 5000 ms warm-up excluded |
| Steady performance | >=1 analyzed FPS, inference p95 <=1000 ms, capture-to-result p95 <=2000 ms |
| Thermal behavior | Last vs first steady window p95 latency ratio <=1.25; report thermal status and failures |
| Pipeline/evidence | <=1 pending frame; offline; no fatal errors or event gaps in the acceptance trace |
| Storage | 256 pending events, 100 MB total evidence; sampling off by default; opt-in 1 JPEG/s, 10 MB/trip |

State metrics use manually annotated intervals where explicit supported signs
establish the reference state; unsupported road-rule intervals must be identified
and excluded with reasons. Unknown time is the UNKNOWN freshness interval;
stale time is the separate STALE interval, so they are not double-counted.
Delay begins when the relevant sign becomes visibly usable, not when the model
first emits a convenient candidate. Unconfirmed encounters must be reported as
misses rather than disappearing from a delay-only percentile. Record agreement,
unknown/stale denominators and false-transition exposure explicitly.

LED/night/rain need separate reports and coverage; daylight success cannot imply
adverse-condition success. Exact counts, uncertainty and class coverage must be
reported even when a numerical threshold passes. Performance alone cannot pass
full Stage 1 when recognition or state gates fail.

## Evidence still needed before freezing

1. A validation-only exported walk/drive with selected samples, complete events
   and human review of which signs/boxes are present and when they become visible.
   Use it to diagnose the outdoor 30 sign, then tune temporal settings/expiry.
2. Representative validation coverage beyond a single sign/walk. Sparse samples
   can reveal errors but cannot recover every unsaved frame or establish exact
   encounter visibility without an appropriate annotated sequence.
3. A reviewed, numerically fixed sheet with the exact app/bundle/config hashes,
   validation corpus hashes, sampling choice, measurement windows and procedure.
4. Independent annotated **test** drives kept out of tuning, with all-class and
   negative coverage, followed by the sustained frozen-pair physical-device run.

User export handoff: **Trips → Open trip → Select all images → Export ZIP**,
then copy the ZIP under ignored `ml/data/trip-exports/`. Image sampling must have
been enabled during that trip; a journal-only export cannot restore unsaved
images. Preserve the full ZIP and provenance. Predictions remain unreviewed and
must not become automatic ground-truth labels. See `TRIP_EXPORT_FORMAT.md`.

The available repository data have no independently annotated temporal drives.
Consequently 9.1 remains unchecked, and 9.2–9.5 cannot be claimed complete from
this diagnostic, past short camera tests or a successful build. No final-test
evaluation or formal sustained acceptance run was started with unfrozen settings.
