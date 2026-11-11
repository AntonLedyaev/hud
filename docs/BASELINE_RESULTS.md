# GTSDB provisional baseline comparison

Both candidates used the same pretrained YOLO11n checkpoint, canonical twelve
classes, seed 0, ten epochs and official 600/300 training/validation assignment.
Horizontal and vertical flips were disabled. Sequence independence is unverified.
The 300 validation scenes include 156 target signs and 179 scenes without target
signs. These results are preliminary and do not establish final Stage 1 acceptance.

| Input size | mAP50 | mAP50–95 | Small-sign recall | Medium-sign recall |
|---|---:|---:|---:|---:|
| 320 | 0.1051 | 0.0633 | 0 / 59 | 5 / 97 |
| 640 | 0.1876 | 0.1532 | 5 / 59 | 30 / 97 |

Recall here uses confidence 0.25, same-class IoU >= 0.5, one-to-one matching and
original-image sign area (<32² small, <96² medium). There are no large-sign samples
in this validation set. mAP uses the separate Ultralytics confidence sweep.

## Per-class validation AP50–95

| Class | Train instances | Validation instances | 320 | 640 |
|---|---:|---:|---:|---:|
| speed_limit_30 | 48 | 31 | 0.0765 | 0.3639 |
| speed_limit_50 | 59 | 22 | 0.1961 | 0.2871 |
| speed_limit_60 | 21 | 9 | 0.0086 | 0.0825 |
| speed_limit_70 | 31 | 37 | 0.1061 | 0.1962 |
| speed_limit_80 | 37 | 16 | 0.0955 | 0.3417 |
| speed_limit_100 | 37 | 4 | 0.0781 | 0.0873 |
| speed_limit_120 | 47 | 10 | 0.0869 | 0.1296 |
| end_speed_limit_80 | 17 | 2 | 0.0000 | 0.0072 |
| end_all_restrictions | 3 | 5 | 0.0000 | 0.0000 |
| stop | 22 | 10 | 0.0559 | 0.2513 |
| no_overtaking | 32 | 9 | 0.0558 | 0.0911 |
| end_no_overtaking | 6 | 1 | 0.0000 | 0.0000 |

This table is an evaluation snapshot derived from the generated mappings/reports,
not a maintained class taxonomy. `shared/labels.yaml` remains authoritative.

## Selected experimental candidate

Use the **640** checkpoint for the next export/integration experiment because it
improved aggregate AP and small/medium-sign recall in this bounded comparison.
Selection remains subject to trained-export parity and sustained Pixel benchmarks.

- Checkpoint: `ml/runs/gtsdb-baseline-640-v2/weights/best.pt`.
- SHA-256: `00a9cc47de2e7c7168c78609fe546f5cf6482aaff11afa05bb31f308503dac20`.
- Dataset manifest SHA-256: `7872aca8c883df114266b7828c814eb6192880c90a7ba8a1a9de7ba9344a8105`.
- Run manifests: `ml/runs/gtsdb-baseline-{320,640}-v2/run-manifest.json`.
- Full reports/predictions: `ml/runs/gtsdb-baseline-{320,640}-v2/evaluation/`.
- Reproduction commands and report definitions: `ml/README.md`.

The two-epoch smoke run only verifies training mechanics. The interrupted original
320 run with default flip augmentation is excluded from this comparison.

## Remaining failures and missing evidence

Quality is low: at the measured operating point the 640 model detects only 35 of
156 signs correctly, with substantial class confusion and missed signs. The
cancellation classes remain effectively unlearned; some classes have only one or
two validation examples. This checkpoint is not a reliable driving detector.

Night, rain and LED conditions lack explicit strata, so their metrics are marked
unavailable. No real-drive test, temporal state quality, phone latency or thermal
result is claimed. More representative data, stronger rare-class coverage and
further validation are required before Stage 1 acceptance.
