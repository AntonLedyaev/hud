"""Diagnose confidence on saved provisional validation predictions; never freeze acceptance."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path

from evaluate import summarize
from train import ROOT, canonical, digest


def validate_predictions(images, class_count, floor):
    names = [image["source_name"] for image in images]
    if not names or any(not isinstance(name, str) or not name for name in names):
        raise ValueError("validation scenes need source names")
    if len(set(names)) != len(names):
        raise ValueError("duplicate validation scene")
    for image in images:
        for kind in ("truth", "predictions"):
            for item in image[kind]:
                class_id = item["class_id"]
                if type(class_id) is not int or not 0 <= class_id < class_count:
                    raise ValueError("class outside canonical mapping")
                box = item["box"]
                if (len(box) != 4 or not all(math.isfinite(v) and v >= 0 for v in box)
                        or box[0] >= box[2] or box[1] >= box[3]):
                    raise ValueError("invalid box")
                if kind == "predictions":
                    score = item["confidence"]
                    if not math.isfinite(score) or not floor <= score <= 1:
                        raise ValueError("prediction confidence below recorded floor or invalid")


def sweep(images, classes, thresholds, floor, match_iou=0.5):
    if not math.isfinite(floor) or not 0 <= floor <= 1:
        raise ValueError("invalid saved confidence floor")
    validate_predictions(images, len(classes), floor)
    if not thresholds or any(not math.isfinite(t) or not floor <= t <= 1 for t in thresholds):
        raise ValueError("cannot sweep below the saved confidence floor")
    rows = []
    for threshold in sorted(set(thresholds)):
        filtered = [{**image, "predictions": [p for p in image["predictions"]
                                              if p["confidence"] >= threshold]} for image in images]
        result = summarize(filtered, len(classes), match_iou)
        for item, label in zip(result["per_class"], classes, strict=True):
            item.update(id=label["id"], name=label["name"])
            item["instances"] = item["tp"] + item["fn"]
        tp = sum(c["tp"] for c in result["per_class"])
        fp = sum(c["fp"] for c in result["per_class"])
        fn = sum(c["fn"] for c in result["per_class"])
        result.update(confidence=threshold, tp=tp, fp=fp, fn=fn,
                      precision=tp / max(1, tp + fp), recall=tp / max(1, tp + fn),
                      micro_f1=2 * tp / max(1, 2 * tp + fp + fn))
        rows.append(result)
    return rows


def class_failures(rows, ap_rows, targets):
    """Diagnostic comparison only: small/absent classes must never disappear in an aggregate."""
    failures = []
    for row, ap in zip(rows, ap_rows, strict=True):
        if row["id"] != ap["id"]:
            raise ValueError("report class order differs from canonical mapping")
        reasons = []
        for key, observed, required in (
            ("instances", row["instances"], targets["minimum_instances"]),
            ("precision", row["precision"], targets["minimum_precision"]),
            ("recall", row["recall"], targets["minimum_recall"]),
            ("map_50_95", ap["map_50_95"], targets["minimum_map_50_95"]),
        ):
            if observed is None or not math.isfinite(observed) or observed < required:
                reasons.append(key)
        if reasons:
            failures.append({"id": row["id"], "name": row["name"], "failed": reasons})
    return failures


def diagnose(evaluation, bundle, dataset, sheet, thresholds):
    report_path = evaluation / "report.json"
    predictions_path = evaluation / "predictions.json"
    manifest_path = bundle / "manifest.json"
    report = json.loads(report_path.read_text(encoding="utf-8"))
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    dataset_manifest = json.loads(dataset.read_text(encoding="utf-8"))
    targets = json.loads(sheet.read_text(encoding="utf-8"))
    labels, _ = canonical()
    classes = [{"id": c["id"], "name": c["name"]} for c in labels["classes"]]
    if targets["status"] != "draft_not_frozen":
        raise ValueError("this diagnostic command only supports draft targets")
    if (manifest["labels"]["classes"] != classes
            or manifest["labels"]["sha256"] != digest(ROOT / "shared/labels.yaml")
            or dataset_manifest["labels_sha256"] != manifest["labels"]["sha256"]):
        raise ValueError("labels provenance mismatch")
    source_provenance = manifest["provenance"]
    if (report["checkpoint_sha256"] != source_provenance["checkpoint_sha256"]
            or report["dataset_manifest_sha256"] != digest(dataset)
            or report["dataset_manifest_sha256"] != source_provenance["dataset_manifest_sha256"]):
        raise ValueError("checkpoint or dataset provenance mismatch")
    if digest(bundle / "model.tflite") != manifest["model_sha256"]:
        raise ValueError("bundle model checksum mismatch")
    if not report["provisional"] or report["final_acceptance"]:
        raise ValueError("only provisional validation is allowed; do not tune on final tests")
    if report["split_policy"] != dataset_manifest["split_policy"]:
        raise ValueError("split provenance mismatch")
    images = json.loads(predictions_path.read_text(encoding="utf-8"))
    expected = {r["source_name"] for r in dataset_manifest["records"] if r["split"] == "val"}
    if {image["source_name"] for image in images} != expected:
        raise ValueError("predictions do not match validation membership")
    floor = report["fixed_thresholds"]["confidence"]
    rows = sweep(images, classes, sorted(set([floor, *thresholds])), floor,
                 report["fixed_thresholds"]["match_iou"])
    baseline = rows[0]
    if (baseline["scene_count"] != report["scene_count"]
            or baseline["negative_scene_count"] != report["negative_scene_count"]):
        raise ValueError("prediction counts differ from recorded report")
    for actual, recorded in zip(baseline["per_class"], report["per_class"], strict=True):
        if any(actual[key] != recorded[key] for key in ("id", "tp", "fp", "fn")):
            raise ValueError("saved predictions do not reproduce baseline counts")
    for row in rows:
        row["draft_class_failures"] = class_failures(
            row["per_class"], report["per_class"], targets["per_class"])
    best = max(rows, key=lambda row: row["micro_f1"])
    return {
        "schema_version": 1, "status": "diagnostic_only", "final_acceptance": False,
        "calibration_complete": False, "candidate_model_version": manifest["model_version"],
        "model_sha256": manifest["model_sha256"], "effective_config": manifest["config"],
        "labels_sha256": manifest["labels"]["sha256"],
        "provenance": {"report_sha256": digest(report_path),
                       "predictions_sha256": digest(predictions_path),
                       "bundle_manifest_sha256": digest(manifest_path),
                       "dataset_manifest_sha256": digest(dataset),
                       "draft_sheet_sha256": digest(sheet), "tool_sha256": digest(__file__)},
        "inference_path": "Saved source-model predictions, not Android/LiteRT inference",
        "saved_confidence_floor": floor, "sweep": rows,
        "best_micro_f1_confidence_for_comparison_only": best["confidence"],
        "blocked_final_gates": [
            "No independently annotated temporal validation drives for confirmation/expiry",
            "Provisional GTSDB validation is not an independent final test corpus",
            "Draft acceptance targets/model/config have not been frozen",
            "No frozen-pair sustained device run or annotated final-drive evaluation",
            "Saved source predictions do not establish full-corpus Android postprocessing parity",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evaluation", type=Path, required=True)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--dataset-manifest", type=Path, required=True)
    parser.add_argument("--sheet", type=Path,
                        default=Path(__file__).with_name("acceptance-draft.json"))
    parser.add_argument("--thresholds", nargs="+", type=float, default=[0.25, 0.35, 0.5, 0.65, 0.8])
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = diagnose(args.evaluation, args.bundle, args.dataset_manifest,
                      args.sheet, args.thresholds)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(result, indent=2, allow_nan=False) + "\n")
    print(json.dumps({"status": result["status"], "thresholds": len(result["sweep"]),
                      "final_acceptance": False, "output": str(args.output)}))


if __name__ == "__main__":
    main()
