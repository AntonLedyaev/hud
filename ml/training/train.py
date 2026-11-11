"""Train a traceable road-sign baseline; never use validation as final acceptance."""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter
from importlib.metadata import version
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ml/datasets"))
from gtsdb import canonical, digest  # noqa: E402


def check_dataset(directory):
    directory = Path(directory)
    manifest_path = directory / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    source, _ = canonical()
    if manifest["labels_sha256"] != digest(ROOT / "shared/labels.yaml"):
        raise ValueError("dataset uses incompatible canonical labels")
    data = yaml.safe_load((directory / "dataset.yaml").read_text(encoding="utf-8"))
    if data["names"] != {c["id"]: c["name"] for c in source["classes"]}:
        raise ValueError("dataset class mapping mismatch")
    if (Path(data["path"]).resolve() != directory.resolve() or
            data["train"] != "images/train" or data["val"] != "images/val"):
        raise ValueError("dataset YAML paths disagree with verified manifest inputs")
    counts = {split: Counter() for split in ("train", "val", "test")}
    scenes = Counter()
    for record in manifest["records"]:
        split = record["split"]
        scenes[split] += 1
        stem = Path(record["source_name"]).stem
        for category, extension, key in (("images", "png", "image_sha256"),
                                         ("labels", "txt", "label_sha256")):
            path = directory / category / split / f"{stem}.{extension}"
            if digest(path) != record[key]:
                raise ValueError(f"modified dataset input: {path}")
        counts[split].update(int(box[0]) for box in record["labels"])
    if not scenes["train"] or not scenes["val"]:
        raise ValueError("training requires nonempty train and validation scenes")
    coverage = [{"id": c["id"], "name": c["name"],
                 **{split: counts[split][c["id"]] for split in counts}}
                for c in source["classes"]]
    return manifest, dict(scenes), coverage


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--config", type=Path, default=Path(__file__).with_name("baseline.yaml"))
    parser.add_argument("--epochs", type=int)
    parser.add_argument("--imgsz", type=int)
    parser.add_argument("--name", required=True)
    args = parser.parse_args()
    dataset = args.dataset.resolve()
    config_path = args.config.resolve()
    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    for key in ("epochs", "imgsz"):
        if getattr(args, key) is not None:
            config[key] = getattr(args, key)
    if config["epochs"] < 1 or config["imgsz"] < 32 or config["imgsz"] % 32:
        raise ValueError("epochs must be positive; image size must be a positive multiple of 32")
    if Path(args.name).name != args.name or args.name in (".", ".."):
        raise ValueError("run name must be a simple directory name")
    run = ROOT / "ml/runs" / args.name
    if run.exists():
        raise ValueError("run directory exists; select a new name")
    manifest, scenes, coverage = check_dataset(dataset)
    checkpoint = ROOT / "ml" / config.pop("model")
    if not checkpoint.is_file():
        raise ValueError(f"initial checkpoint missing: {checkpoint}")
    report = {
        "status": "started", "split_policy": manifest["split_policy"],
        "sequence_independence_verified": manifest["sequence_independence_verified"],
        "final_acceptance": False, "dataset_manifest_sha256": digest(dataset / "manifest.json"),
        "dataset_yaml_sha256": digest(dataset / "dataset.yaml"),
        "initial_checkpoint_sha256": digest(checkpoint), "config_file_sha256": digest(config_path),
        "effective_config": config, "scenes": scenes, "coverage": coverage,
        "tools": {p: version(p) for p in ("torch", "torchvision", "ultralytics", "numpy")},
    }
    run.mkdir(parents=True)
    report_file = run / "run-manifest.json"

    def save():
        report_file.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")

    save()
    try:
        import torch
        from ultralytics import YOLO

        if not torch.cuda.is_available():
            raise RuntimeError("CUDA required for this baseline configuration")
        report["gpu"] = torch.cuda.get_device_name(0)
        os.chdir(ROOT / "ml")
        model = YOLO(str(checkpoint))
        model.train(data=str(dataset / "dataset.yaml"), project=str(run.parent), name=run.name,
                    exist_ok=True, **config)
        best = run / "weights/best.pt"
        if not best.is_file():
            raise RuntimeError("training did not produce a best checkpoint")
        expected = {c["id"]: c["name"] for c in canonical()[0]["classes"]}
        if YOLO(str(best)).names != expected:
            raise ValueError("trained model class mapping mismatch")
        report.update(status="trained", best_checkpoint_sha256=digest(best))
        report["resolved_training_args"] = yaml.safe_load((run / "args.yaml").read_text())
    except Exception as error:
        report.update(status="failed", error_type=type(error).__name__, error=str(error))
        raise
    finally:
        save()


if __name__ == "__main__":
    main()
