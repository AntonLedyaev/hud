"""Export an untrained twelve-class YOLO11n; never use it for real recognition."""

from __future__ import annotations

import hashlib
import json
import os
from importlib.metadata import version
from pathlib import Path

os.environ.setdefault("YOLO_AUTOINSTALL", "false")
os.environ.setdefault("TF_ENABLE_ONEDNN_OPTS", "0")

import numpy as np
import onnxruntime as ort
import torch
import yaml
from ultralytics import YOLO

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "ml" / "runs" / "export_experiment"


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    os.chdir(OUTPUT)
    labels_path = ROOT / "shared" / "labels.yaml"
    labels = yaml.safe_load(labels_path.read_text(encoding="utf-8"))
    torch.manual_seed(0)
    model = YOLO("yolo11n.yaml")
    config = dict(model.model.yaml)
    config["nc"] = len(labels["classes"])
    # Ultralytics infers scale from the YAML filename; preserve the nano suffix.
    config_path = OUTPUT / "yolo11n.yaml"
    config_path.write_text(yaml.safe_dump(config), encoding="utf-8")
    model = YOLO(str(config_path), task="detect")
    model.model.names = {item["id"]: item["name"] for item in labels["classes"]}
    checkpoint = OUTPUT / "experiment.pt"
    model.save(str(checkpoint))
    report = {
        "experimental_untrained": True, "seed": 0, "input_shape": [1, 3, 320, 320],
        "labels_sha256": sha256(labels_path), "checkpoint_sha256": sha256(checkpoint),
        "classes": model.model.names,
        "tools": {p: version(p) for p in ("torch", "ultralytics", "onnx", "onnxruntime",
                                         "tensorflow", "onnx2tf", "ai-edge-litert")},
        "status": "started",
    }
    report_path = OUTPUT / "report.json"

    def write_report():
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    write_report()
    try:
        model = YOLO(str(checkpoint))
        tensor = np.random.default_rng(0).random((1, 3, 320, 320), dtype=np.float32)
        np.save(OUTPUT / "input_nchw.npy", tensor)
        np.transpose(tensor, (0, 2, 3, 1)).astype("<f4").tofile(OUTPUT / "input_nhwc.bin")
        model.model.eval()
        with torch.no_grad():
            source = model.model(torch.from_numpy(tensor))[0].numpy()
        np.save(OUTPUT / "source_output.npy", source)
        source.astype("<f4").tofile(OUTPUT / "expected_output.bin")
        onnx = Path(model.export(format="onnx", imgsz=320, opset=17, simplify=True,
                                 dynamic=False, nms=False, device="cpu"))
        session = ort.InferenceSession(str(onnx), providers=["CPUExecutionProvider"])
        actual = session.run(None, {session.get_inputs()[0].name: tensor})[0]
        np.testing.assert_allclose(actual, source, rtol=1e-4, atol=1e-3)
        report.update(onnx_sha256=sha256(onnx), output_shape=list(actual.shape),
                      onnx_max_absolute_error=float(np.max(np.abs(actual - source))))
        write_report()
        # Current Ultralytics aliases format=tflite to another exporter. Use its
        # ONNX conversion helper explicitly to preserve the agreed interchange.
        from ultralytics.utils.export.tensorflow import onnx2saved_model

        saved_model = OUTPUT / "saved_model"
        onnx2saved_model(str(onnx), saved_model)
        candidates = list(saved_model.glob("*_float32.tflite"))
        if len(candidates) != 1:
            raise RuntimeError(f"Expected one FP32 TFLite artifact, found {candidates}")
        tflite = candidates[0]
        from ai_edge_litert.interpreter import Interpreter

        interpreter = Interpreter(model_path=str(tflite))
        interpreter.allocate_tensors()
        input_info = interpreter.get_input_details()[0]
        interpreter.set_tensor(input_info["index"], np.transpose(tensor, (0, 2, 3, 1)))
        interpreter.invoke()
        result = interpreter.get_tensor(interpreter.get_output_details()[0]["index"])
        np.save(OUTPUT / "tflite_output.npy", result)
        comparable = result if result.shape == source.shape else np.transpose(result, (0, 2, 1))
        np.testing.assert_allclose(comparable, source, rtol=1e-4, atol=1e-3)
        report.update(status="converted", tflite_path=str(tflite),
                      tflite_sha256=sha256(tflite), tflite_output_shape=list(result.shape),
                      tflite_max_absolute_error=float(np.max(np.abs(comparable - source))))
    except Exception as error:
        report.update(status="failed", error_type=type(error).__name__, error=str(error))
        raise
    finally:
        write_report()


if __name__ == "__main__":
    main()
