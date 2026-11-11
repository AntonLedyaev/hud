# GTSDB scene preparation

Run from the repository root with the ML environment installed:

```powershell
ml/.venv/Scripts/python.exe ml/datasets/fetch_gtsdb.py
ml/.venv/Scripts/python.exe ml/datasets/gtsdb.py --annotations ml/data/gtsdb-source/gt.txt --images ml/data/gtsdb-source --audit-output ml/runs/dataset-audit/full.json
ml/.venv/Scripts/python.exe -m pytest ml/datasets/test_gtsdb.py -q
```

The download is about 1.66 GB and extraction adds about 2.94 GB of full scenes.
The script records source URL, size, local SHA-256 and license reference in ignored
`provenance.json`. The publisher inventory does not provide a SHA-256 value by
default; local hashes identify the exact inputs but do not authenticate the source.
Archive members are extracted to checked destination paths. Only root full scenes
and metadata are retained; per-class sign crops are excluded.

Use the `gt.txt` **inside the full archive**. The archive's separately linked
`gt.txt` covers images 00000–00599 only, despite the hosting page description.
That file cannot establish whether the remaining scenes are target negatives.

## Conversion inputs

Conversion requires a reviewed JSON mapping for every root scene. Example shape
(illustrative only, not a real split assignment):

```json
{
  "00000.ppm": {
    "sequence_id": "reviewed-drive-or-related-scene-group",
    "split": "train",
    "annotations_complete": true
  }
}
```

Related images must share a sequence/group and a split; valid splits are train,
val and test. Unknown group IDs must not silently become individual filenames.
`annotations_complete` confirms that the supplied annotation source covers that
scene: absence of a row then means no annotated sign, rather than missing labels.
The downloaded GTSDB archive contains no drive/sequence membership metadata;
reviewed grouping is a required external input and has not been fabricated.

```powershell
ml/.venv/Scripts/python.exe ml/datasets/gtsdb.py --annotations ml/data/gtsdb-source/gt.txt --images ml/data/gtsdb-source --splits ml/data/gtsdb-splits.json --output ml/data/gtsdb-yolo-v1
```

The converter rejects missing assignments, missing images, out-of-bounds/empty
boxes, sequence leakage, identical images crossing splits and existing output
directories. It records all source image hashes, annotation/split/labels hashes,
dimensions, exclusions and per-scene split identity in `manifest.json`.
It derives YOLO indices/names from `shared/labels.yaml`, converts full PPM scenes
to lossless RGB PNG, and emits normalized cx/cy/width/height labels plus dataset
YAML. Pixel ROI coordinates are treated as xyxy edges (exclusive upper edges);
this convention is explicit in the manifest. The source ReadMe names left/top/
right/bottom but does not specify an inclusive/exclusive edge convention.

Unsupported classes are recorded as exclusions, not mapped to a target class.
Scenes with zero target signs receive empty labels and remain in the dataset.
Scene crops from GTSRB/class directories cannot be silently used as full-scene
detection data. Neither raw images nor generated datasets are committed.

Read `SOURCE_AUDIT.md` before starting training. The final test split must not be
used to select confidence thresholds, resolution, model size or epochs.

## Approved provisional baseline

The developer approved the official 600/300 train/validation baseline. This is an
explicit exception to reviewed sequence grouping, not evidence of independence.

```powershell
ml/.venv/Scripts/python.exe ml/datasets/gtsdb.py --annotations ml/data/gtsdb-source/gt.txt --images ml/data/gtsdb-source --official-provisional --output ml/data/gtsdb-official-v1
```

This requires all 900 root images, assigns indices below 600 to train and the rest
to validation, records null sequence IDs and the provisional policy, and creates no
test split. Identical-image leakage is still rejected. Each derived image and label
file receives a SHA-256 recorded in the manifest for training input verification.
Final acceptance remains dependent on independent-drive evaluation.
