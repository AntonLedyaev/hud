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
