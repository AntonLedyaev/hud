# YOLO11n export compatibility experiment

This directory has its own uv project/lockfile because TensorFlow's NumPy and
protobuf constraints differ from the training environment. It uses the same
pinned torch/CUDA packages but does not modify `ml/.venv`.

Run from the repository root:

```powershell
uv sync --project ml/export --locked
uv run --project ml/export python ml/export/experiment.py
```

The experiment constructs an **untrained** nano YOLO11 detector using the class
count and names from `shared/labels.yaml`, seeds initialization and a synthetic
320x320 input, saves PyTorch outputs and exports ONNX opset 17. It checks ONNX
outputs against PyTorch (absolute tolerance 0.001, relative tolerance 0.0001).

The installed Ultralytics 8.4.120 aliases `format=tflite` to its unified LiteRT
exporter. This script explicitly calls its ONNX-to-SavedModel conversion helper
to exercise the ONNX interchange route, then loads the resulting FP32 TFLite
model for the same fixture check. Output tensors are retained for later Android
parity; this experiment does not establish detection quality or lane applicability.

All artifacts go to ignored `ml/runs/export_experiment/`: checkpoint, model config,
input/output NumPy arrays, ONNX, SavedModel/TFLite and `report.json`. The report
contains label/checkpoint/model hashes, versions, output shapes and numeric
errors. Exceptions mark the run failed; never substitute an earlier artifact
for a failed run. Converter logs identify unsupported operators or platform
limitations. No long training run is accepted before source-to-phone parity.

The converter may download its calibration sample even for FP32 export; that
sample is not a road-sign dataset. Raw models, images and datasets stay outside git.
The application's current shell does not load this untrained model automatically.

Verified locally: ONNX maximum absolute tensor error 3.0517578125e-5; TFLite
maximum absolute tensor error 2.288818359375e-5 against the source fixture.
The Android CPU harness also passed on the reference Pixel after a clean build.

## Trained baseline bundle

From the repository root, with the selected trained checkpoint available:

```powershell
uv sync --project ml/export --locked
uv run --project ml/export python ml/export/bundle.py --checkpoint ml/runs/gtsdb-baseline-640-v2/weights/best.pt --output ml/runs/trained-bundle-v2
```

Use a new output directory when it already exists. This export uses fixed
validation scenes 00605/00611/00613, explicit nearest pixel-center letterboxing,
ONNX opset 17 and FP32 conversion. `parity.json` must report passed. Neither this
corpus nor the earlier untrained test is final independent evaluation.

To regenerate only parity fixtures for the same model/checkpoint hashes:

```powershell
uv run --project ml/export python ml/export/refresh_fixtures.py --bundle ml/runs/trained-bundle-v2
```

Android builds stage only the manifest, model, preprocessing profile and parity
report; source frames/checkpoints are excluded from the application APK. Its
staging validator runs in the training Python environment and rejects mismatched
weights/labels/profile or a failed parity report. Unsupported contracts fail
explicitly rather than being silently converted in the Android loader.
See `../../docs/TRAINED_EXPORT_RESULTS.md` for CPU parity and GPU probe findings.
