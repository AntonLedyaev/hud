"""Refresh trained-bundle parity on fixed validation scenes with actual detections."""

import argparse
import json
from pathlib import Path

import numpy as np
import torch
from ai_edge_litert.interpreter import Interpreter
from bundle import ROOT, sha
from PIL import Image
from ultralytics import YOLO


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", type=Path, required=True)
    args = parser.parse_args()
    root = args.bundle.resolve()
    manifest = json.loads((root / "manifest.json").read_text())
    report = json.loads((root / "parity.json").read_text())
    if sha(root / "model.tflite") != manifest["model_sha256"]:
        raise ValueError("model hash mismatch")
    if sha(root / "checkpoint.pt") != manifest["provenance"]["checkpoint_sha256"]:
        raise ValueError("checkpoint hash mismatch")
    model = YOLO(str(root / "checkpoint.pt"))
    model.model.eval()
    interpreter = Interpreter(model_path=str(root / "model.tflite"))
    interpreter.allocate_tensors()
    evidence = []
    report["status"] = "refreshing"
    (root / "parity.json").write_text(json.dumps(report, indent=2))
    for name in ("00605", "00611", "00613"):
        with Image.open(ROOT / f"ml/data/gtsdb-official-v1/images/val/{name}.png") as image:
            rgb = np.asarray(image.convert("RGB"), dtype=np.uint8)
        height, width = rgb.shape[:2]
        scale = min(640 / width, 640 / height)
        rw, rh = round(width * scale), round(height * scale)
        xs = (((np.arange(rw) + 0.5) * width) / rw).astype(np.int64)
        ys = (((np.arange(rh) + 0.5) * height) / rh).astype(np.int64)
        canvas = np.full((640, 640, 3), 114, dtype=np.uint8)
        left, top = (640 - rw) // 2, (640 - rh) // 2
        canvas[top:top + rh, left:left + rw] = rgb[ys[:, None], xs[None, :]]
        nhwc = canvas.astype(np.float32)[None] / 255.0
        with torch.no_grad():
            expected = model.model(torch.from_numpy(nhwc.transpose(0, 3, 1, 2).copy()))[0].numpy()
        interpreter.set_tensor(interpreter.get_input_details()[0]["index"], nhwc)
        interpreter.invoke()
        actual = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
        np.testing.assert_allclose(actual, expected, rtol=1e-4, atol=0.01)
        fixture = root / "fixtures"
        rgb.tofile(fixture / f"{name}.rgb")
        nhwc.astype("<f4").tofile(fixture / f"{name}.input")
        expected.astype("<f4").tofile(fixture / f"{name}.expected")
        evidence.append({"name": name, "width": width, "height": height,
                         "tflite_max_error": float(np.max(np.abs(actual - expected)))})
    report.update(status="passed", fixtures=evidence)
    (root / "parity.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
