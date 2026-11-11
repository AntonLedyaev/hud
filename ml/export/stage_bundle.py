"""Validate and stage only a passed local model bundle for Android assets."""

import argparse
import json
import shutil
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "shared"))
from bundle import sha  # noqa: E402
from contracts import validate  # noqa: E402


def stage(source, destination):
    source, destination = Path(source), Path(destination)
    manifest = json.loads((source / "manifest.json").read_text())
    validate("model-manifest", manifest)
    if (manifest["input"]["dtype"] != "float32" or
            manifest["output"]["tensor"]["dtype"] != "float32" or
            manifest["output"]["nms_owner"] != "consumer" or
            manifest["output"]["score_decoding"] != "class_probability" or
            manifest["preprocessing"]["channel_order"] != "RGB"):
        raise ValueError("unsupported Android detector contract")
    if sha(source / "model.tflite") != manifest["model_sha256"]:
        raise ValueError("model checksum mismatch")
    parity = json.loads((source / "parity.json").read_text())
    if parity["status"] != "passed" or parity["checkpoint_sha256"] != (
            manifest["provenance"]["checkpoint_sha256"]):
        raise ValueError("source export parity is missing or mismatched")
    profile = json.loads((source / "preprocessing-profile.json").read_text())
    if profile != {"version": 1, "interpolation": "nearest_pixel_centers",
                   "rounding": "ties_to_even", "input_dtype": "RGB888",
                   "manifest_sha256": sha(source / "manifest.json")}:
        raise ValueError("unsupported preprocessing profile")
    destination.mkdir(parents=True, exist_ok=True)
    for name in ("manifest.json", "model.tflite", "parity.json", "preprocessing-profile.json"):
        shutil.copyfile(source / name, destination / name)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    stage(args.source, args.destination)
