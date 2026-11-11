"""Provisional validation reports; final independent-drive testing is separate."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from train import canonical, check_dataset, digest


def iou(a, b):
    intersection = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(
        0, min(a[3], b[3]) - max(a[1], b[1]))
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - intersection
    return intersection / union if union > 0 else 0


def size_bucket(box):
    area = (box[2] - box[0]) * (box[3] - box[1])
    return "small" if area < 32**2 else "medium" if area < 96**2 else "large"


def summarize(images, class_count, threshold=0.5):
    counts = [{"tp": 0, "fp": 0, "fn": 0} for _ in range(class_count)]
    sizes = {key: {"matched": 0, "samples": 0} for key in ("small", "medium", "large")}
    negatives = 0
    for image in images:
        truth, predictions = image["truth"], image["predictions"]
        matched = set()
        negatives += not truth
        for item in truth:
            sizes[size_bucket(item["box"])]["samples"] += 1
        for prediction in sorted(predictions, key=lambda item: -item["confidence"]):
            class_id = prediction["class_id"]
            if not 0 <= class_id < class_count:
                raise ValueError("prediction class outside canonical mapping")
            candidates = [(iou(prediction["box"], item["box"]), index)
                          for index, item in enumerate(truth)
                          if index not in matched and item["class_id"] == class_id]
            overlap, index = max(candidates, default=(0, -1))
            if overlap >= threshold and index >= 0:
                matched.add(index)
                counts[class_id]["tp"] += 1
                sizes[size_bucket(truth[index]["box"])]["matched"] += 1
            else:
                counts[class_id]["fp"] += 1
        for index, item in enumerate(truth):
            if index not in matched:
                counts[item["class_id"]]["fn"] += 1
    for count in counts:
        count["precision"] = count["tp"] / max(1, count["tp"] + count["fp"])
        count["recall"] = count["tp"] / max(1, count["tp"] + count["fn"])
    for size in sizes.values():
        size["recall"] = size["matched"] / size["samples"] if size["samples"] else None
    return {"per_class": counts, "by_source_pixel_size": sizes,
            "negative_scene_count": negatives, "scene_count": len(images)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--imgsz", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.dataset = args.dataset.resolve()
    args.checkpoint = args.checkpoint.resolve()
    args.output = args.output.resolve()
    manifest, _, coverage = check_dataset(args.dataset)
    if args.output.exists():
        raise ValueError("evaluation output exists; choose a new directory")
    args.output.mkdir(parents=True)
    from ultralytics import YOLO

    model = YOLO(str(args.checkpoint))
    labels, _ = canonical()
    if model.names != {c["id"]: c["name"] for c in labels["classes"]}:
        raise ValueError("checkpoint labels mismatch")
    metrics = model.val(data=str(args.dataset / "dataset.yaml"), split="val", imgsz=args.imgsz,
                        device=0, workers=0, plots=True, project=str(args.output),
                        name="ultralytics", exist_ok=True, verbose=False)
    records = {Path(r["source_name"]).stem: r for r in manifest["records"] if r["split"] == "val"}
    images = []
    for result in model.predict(source=str(args.dataset / "images/val"), imgsz=args.imgsz,
                                conf=0.25, device=0, stream=True, verbose=False):
        record = records[Path(result.path).stem]
        truth = []
        for class_id, cx, cy, width, height in record["labels"]:
            truth.append({"class_id": int(class_id), "box": [
                (cx - width / 2) * record["width"], (cy - height / 2) * record["height"],
                (cx + width / 2) * record["width"], (cy + height / 2) * record["height"]]})
        boxes = result.boxes.cpu()
        predictions = [
            {"class_id": int(class_id), "confidence": float(conf), "box": box.tolist()}
            for class_id, conf, box in zip(boxes.cls, boxes.conf, boxes.xyxy, strict=True)]
        images.append({"source_name": record["source_name"], "truth": truth,
                       "predictions": predictions})
    if len(images) != len(records):
        raise ValueError("evaluation did not cover all validation scenes")
    report = summarize(images, len(labels["classes"]))
    ap_indices = {int(class_id): i for i, class_id in enumerate(metrics.box.ap_class_index)}
    report.update({"provisional": True, "final_acceptance": False,
                   "split_policy": manifest["split_policy"], "imgsz": args.imgsz,
                   "checkpoint_sha256": digest(args.checkpoint),
                   "dataset_manifest_sha256": digest(args.dataset / "manifest.json"),
                   "fixed_thresholds": {"confidence": 0.25, "match_iou": 0.5},
                   "map_50_95": float(metrics.box.map), "map_50": float(metrics.box.map50),
                   "condition_coverage": {"gtsdb_unstratified": len(images),
                                          "led": None, "night": None, "rain": None},
                   "confusion_matrix": metrics.confusion_matrix.matrix.tolist(),
                   "confusion_orientation": "predicted rows, truth columns, background last"})
    for row, count in zip(report["per_class"], coverage, strict=True):
        index = ap_indices.get(count["id"])
        row.update(count)
        row["map_50_95"] = float(metrics.box.ap[index]) if index is not None else None
        row["coverage_warning"] = ("missing" if not count["val"] else
                                   "few_samples" if count["val"] < 10 else None)
    for name, value in (("report.json", report), ("predictions.json", images)):
        (args.output / name).write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
