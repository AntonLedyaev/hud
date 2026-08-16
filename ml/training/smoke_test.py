"""End-to-end smoke test of the training pipeline (Stage 0 Definition of Done).

Run: uv run training/smoke_test.py

Trains YOLO11-nano for 2 epochs on Ultralytics' built-in `coco8` toy dataset
(8 images, auto-downloaded), then exports the result to ONNX. This proves the
whole chain works on this machine — download → train on GPU → export — before
we invest time in real datasets.

Expected runtime: well under a minute on any recent GPU.
Artifacts land in ml/runs/ (gitignored).
"""

from __future__ import annotations

from pathlib import Path

import torch
from ultralytics import YOLO

ML_ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    device = 0 if torch.cuda.is_available() else "cpu"
    if device == "cpu":
        print("WARNING: CUDA not available, running on CPU — fix env before real training.")

    model = YOLO("yolo11n.pt")  # auto-downloads ~5 MB pretrained weights

    print("\n--- training (2 epochs, coco8 toy dataset) ---")
    model.train(
        data="coco8.yaml",  # built-in toy dataset, auto-downloaded
        epochs=2,
        imgsz=320,
        device=device,
        project=str(ML_ROOT / "runs"),
        name="smoke_test",
        exist_ok=True,
        verbose=False,
    )

    print("\n--- export to ONNX ---")
    onnx_path = model.export(format="onnx", imgsz=320)
    print(f"\nSmoke test passed. ONNX model: {onnx_path}")
    print("The train → export chain works; ready for real datasets (Stage 1).")


if __name__ == "__main__":
    main()
