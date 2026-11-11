# ml/ — model training & export

Python subproject: dataset preparation, YOLO training, evaluation, export.

- `datasets/` — download & conversion scripts (GTSRB, GTSDB, Mapillary → YOLO format).
  Raw data lives in `ml/data/` (gitignored).
- `training/` — training configs and scripts (Ultralytics/YOLO, PyTorch).
- `export/` — export pipeline: PyTorch → ONNX → TFLite (accelerator-agnostic; no
  Coral-specific paths until hardware is decided).
- `scripts/` — utilities (environment checks etc.).
- `notebooks/` — experiments.

Class list is derived from `../shared/labels.yaml` — do not hardcode.

## Setup

Requires [uv](https://docs.astral.sh/uv/) and an NVIDIA GPU with a recent driver.

```bash
cd ml
uv sync                          # creates .venv, installs torch/ultralytics/...
uv run scripts/check_env.py      # verifies imports, CUDA, labels.yaml
uv run training/smoke_test.py    # 2-epoch toy training + ONNX export (~1 min)
```

### CUDA on Windows (known pitfall)

PyPI ships CPU-only torch on Windows, so `pyproject.toml` pins torch/torchvision
to the official PyTorch CUDA index (`cu128` by default). Two things that bite:

1. **torch/torchvision must be direct dependencies.** uv applies
   `[tool.uv.sources]` only to direct deps — if torch only came in transitively
   via ultralytics, you silently get `2.x.x+cpu`.
2. **Changing the index requires re-resolving.** `uv sync` installs whatever
   is in `uv.lock`; after editing the index run `rm uv.lock && uv sync`.

Verify with `uv run python -c "import torch; print(torch.__version__)"` —
you want a `+cu128` suffix, not `+cpu`. To pick a different CUDA build, check
`nvidia-smi` (driver's max CUDA) and the variants listed at
pytorch.org/get-started/locally.

If `check_env.py` and `smoke_test.py` both pass, the Stage 0 ML goal is met.

## Verified Stage 1 environment

The environment check and two-epoch COCO8 smoke training completed on the RTX 3060
(12 GiB), with Python 3.12.14, torch 2.11.0+cu128, Ultralytics 8.4.120,
OpenCV 5.0.0, Albumentations 2.0.8, ONNX 1.22.0 and ONNX Runtime 1.28.0.
Training used CUDA; the resulting `runs/smoke_test/weights/best.onnx` exported
successfully at input `[1, 3, 320, 320]`, output `[1, 84, 2100]`, opset 20.

This is an 80-class COCO infrastructure test, not the twelve-class road-sign
model or a recognition-quality result. TFLite conversion and source-to-phone
parity remain separate gates. Reproduce using the two `uv run` commands above;
run outputs and weights stay ignored by git.

## Isolated TFLite export experiment

From the repository root:

```powershell
uv sync --project ml/export --locked
uv run --project ml/export python ml/export/experiment.py
```

See `export/README.md` for the isolated toolchain, fixture files and report format.
The twelve-class untrained nano experiment passed ONNX and TFLite CPU tensor
comparison against PyTorch: observed maximum absolute errors were approximately
3.05e-5 and 2.29e-5 respectively. The corresponding Android CPU parity test also
passed on Pixel 6a; reproduce it from `android/README.md`. Neither the COCO smoke
checkpoint nor the untrained twelve-class checkpoint is a usable road-sign model.
