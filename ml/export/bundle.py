"""Export the trained baseline with explicit preprocessing and parity evidence."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

os.environ.setdefault("YOLO_AUTOINSTALL", "false")
os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "shared"))
from contracts import load_labels, validate  # noqa: E402


def sha(path):
    import hashlib
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    checkpoint, output = args.checkpoint.resolve(), args.output.resolve()
    if output.exists():
        raise ValueError("output exists; select a new versioned bundle")
    output.mkdir(parents=True)
    report = {"status": "started", "checkpoint_sha256": sha(checkpoint),
              "final_acceptance": False, "operating_envelope": "provisional GTSDB baseline"}
    try:
        import numpy as np
        import onnxruntime as ort
        import torch
        from ai_edge_litert.interpreter import Interpreter
        from PIL import Image
        from ultralytics import YOLO
        from ultralytics.utils.export.tensorflow import onnx2saved_model

        run = json.loads((checkpoint.parents[1] / "run-manifest.json").read_text())
        labels = load_labels()
        local_checkpoint = output / "checkpoint.pt"
        shutil.copyfile(checkpoint, local_checkpoint)
        model = YOLO(str(local_checkpoint))
        if model.names != {c["id"]: c["name"] for c in labels["classes"]}:
            raise ValueError("checkpoint class mismatch")
        os.chdir(output)
        onnx = model.export(format="onnx", imgsz=640, opset=17, nms=False,
                            simplify=True, dynamic=False, device="cpu")
        onnx2saved_model(str(onnx), output / "saved_model")
        candidates = list((output / "saved_model").glob("*_float32.tflite"))
        if len(candidates) != 1:
            raise ValueError("expected exactly one FP32 TFLite model")
        shutil.copyfile(candidates[0], output / "model.tflite")
        interpreter = Interpreter(model_path=str(output / "model.tflite"))
        interpreter.allocate_tensors()
        session = ort.InferenceSession(onnx, providers=["CPUExecutionProvider"])
        model.model.eval()
        fixture_dir = output / "fixtures"
        fixture_dir.mkdir()
        errors = []
        # Fixed validation corpus, retained as provisional parity data, never a test claim.
        for name in ("00605", "00611", "00613"):
            image_path = ROOT / f"ml/data/gtsdb-official-v1/images/val/{name}.png"
            with Image.open(image_path) as image:
                image = image.convert("RGB")
                width, height = image.size
                scale = min(640 / width, 640 / height)
                rgb = np.asarray(image, dtype=np.uint8)
                rw, rh = round(width * scale), round(height * scale)
                xs = (((np.arange(rw) + 0.5) * width) / rw).astype(np.int64)
                ys = (((np.arange(rh) + 0.5) * height) / rh).astype(np.int64)
                canvas = np.full((640, 640, 3), 114, dtype=np.uint8)
                left, top = (640 - rw) // 2, (640 - rh) // 2
                canvas[top:top + rh, left:left + rw] = rgb[ys[:, None], xs[None, :]]
                rgb.tofile(fixture_dir / f"{name}.rgb")
                nhwc = canvas.astype(np.float32)[None] / 255.0
            nchw = nhwc.transpose(0, 3, 1, 2).copy()
            with torch.no_grad():
                source = model.model(torch.from_numpy(nchw))[0].numpy()
            exported = session.run(None, {session.get_inputs()[0].name: nchw})[0]
            interpreter.set_tensor(interpreter.get_input_details()[0]["index"], nhwc)
            interpreter.invoke()
            mobile = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
            np.testing.assert_allclose(exported, source, rtol=1e-4, atol=0.01)
            np.testing.assert_allclose(mobile, source, rtol=1e-4, atol=0.01)
            nhwc.astype("<f4").tofile(fixture_dir / f"{name}.input")
            source.astype("<f4").tofile(fixture_dir / f"{name}.expected")
            errors.append({"name": name, "width": width, "height": height,
                           "onnx_max_error": float(np.max(np.abs(exported - source))),
                           "tflite_max_error": float(np.max(np.abs(mobile - source)))})
        config = {"version": 1, "confidence_threshold": 0.25, "minimum_hits": 3,
                  "confirmation_window_ms": 1000, "association_iou": 0.5,
                  "rearm_absence_ms": 2000, "estimate_max_age_ms": 60000,
                  "stop_display_ms": 3000, "sampling_enabled": False,
                  "sample_interval_ms": 1000, "sample_budget_bytes": 0,
                  "event_queue_capacity": 256, "storage_budget_bytes": 100000000}
        manifest = {"schema_version": 1, "model_version": "gtsdb-yolo11n-640-v2",
                    "model_sha256": sha(output / "model.tflite"), "labels": labels,
                    "provenance": {"run_id": checkpoint.parents[1].name,
                                   "checkpoint_sha256": sha(checkpoint),
                                   "config_sha256": run["config_file_sha256"],
                                   "dataset_manifest_sha256": run["dataset_manifest_sha256"],
                                   "tools": run["tools"]},
                    "input": {"shape": [1, 640, 640, 3], "dtype": "float32", "quantization": None},
                    "preprocessing": {"channel_order": "RGB", "layout": "NHWC",
                                      "normalization": {"scale": 1 / 255, "offset": 0},
                                      "resize": "letterbox", "padding_value": 114,
                                      "rotation": "upright_before_resize"},
                    "output": {"tensor": {"shape": [1, 16, 8400], "dtype": "float32",
                                           "quantization": None}, "layout": "BCN",
                               "box_format": "cxcywh", "coordinates": "input_pixels",
                               "score_decoding": "class_probability", "nms_owner": "consumer",
                               "confidence_threshold": 0.25, "nms_iou": 0.45}, "config": config}
        validate("model-manifest", manifest)
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        # Named preprocessing profile makes interpolation explicit without silently
        # claiming that every possible resize algorithm is supported by Android.
        (output / "preprocessing-profile.json").write_text(json.dumps({
            "version": 1, "interpolation": "nearest_pixel_centers", "rounding": "ties_to_even",
            "input_dtype": "RGB888", "manifest_sha256": sha(output / "manifest.json")}))
        report.update(status="passed", fixtures=errors, absolute_tolerance=0.01,
                      relative_tolerance=0.0001, output_shape=[1, 16, 8400])
    except Exception as error:
        report.update(status="failed", error_type=type(error).__name__, error=str(error))
        raise
    finally:
        (output / "parity.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
