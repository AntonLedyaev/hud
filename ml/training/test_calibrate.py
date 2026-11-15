import importlib.util
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
spec = importlib.util.spec_from_file_location("calibrate", Path(__file__).with_name("calibrate.py"))
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)


def images():
    truth = {"class_id": 0, "box": [0, 0, 20, 20]}
    return [{"source_name": "positive", "truth": [truth],
             "predictions": [{**truth, "confidence": .7}]},
            {"source_name": "negative", "truth": [],
             "predictions": [{**truth, "confidence": .3}]}]


def test_sweep_accounts_for_precision_recall_tradeoff_and_keeps_uncovered_classes():
    rows = c.sweep(images(), [{"id": 0, "name": "fixture"},
                              {"id": 1, "name": "uncovered"}], [.25, .5, .8], .25)
    assert [(r["tp"], r["fp"], r["fn"]) for r in rows] == [(1, 1, 0), (1, 0, 0), (0, 0, 1)]
    assert rows[1]["micro_f1"] == 1
    assert rows[1]["per_class"][1]["instances"] == 0
    assert rows[1]["per_class"][1]["precision"] == 0


def test_sweep_below_saved_floor_is_rejected_instead_of_inventing_predictions():
    with pytest.raises(ValueError, match="saved confidence floor"):
        c.sweep(images(), [{"id": 0, "name": "fixture"}], [.1], .25)


@pytest.mark.parametrize("mutation", [
    lambda data: data.append(data[0]),
    lambda data: data[0]["predictions"][0].update(confidence=float("nan")),
    lambda data: data[0]["truth"][0].update(class_id=4),
    lambda data: data[0]["truth"][0].update(box=[0, 0, 0, 0]),
])
def test_invalid_or_duplicate_scenes_are_rejected(mutation):
    data = images()
    mutation(data)
    with pytest.raises(ValueError):
        c.sweep(data, [{"id": 0, "name": "fixture"}], [.25], .25)


def test_small_class_and_missing_ap_cannot_pass_by_aggregate():
    rows = c.sweep(images(), [{"id": 0, "name": "fixture"}], [.5], .25)[0]["per_class"]
    failures = c.class_failures(rows, [{"id": 0, "map_50_95": None}],
                               {"minimum_instances": 30, "minimum_precision": .9,
                                "minimum_recall": .8, "minimum_map_50_95": .5})
    assert failures == [{"id": 0, "name": "fixture", "failed": ["instances", "map_50_95"]}]


@pytest.fixture
def diagnostic_inputs(tmp_path):
    def save(path, value):
        path.write_text(json.dumps(value), encoding="utf-8")
    labels, _ = c.canonical()
    classes = [{"id": row["id"], "name": row["name"]} for row in labels["classes"]]
    evaluation = tmp_path / "evaluation"
    bundle = tmp_path / "bundle"
    evaluation.mkdir()
    bundle.mkdir()
    dataset = tmp_path / "dataset.json"
    labels_hash = c.digest(c.ROOT / "shared/labels.yaml")
    records = [{"source_name": i["source_name"], "split": "val"} for i in images()]
    save(dataset, {"labels_sha256": labels_hash, "split_policy": "provisional", "records": records})
    (bundle / "model.tflite").write_bytes(b"synthetic checksum fixture, never executed")
    save(bundle / "manifest.json", {
        "labels": {"classes": classes, "sha256": labels_hash},
        "provenance": {"checkpoint_sha256": "a" * 64,
                       "dataset_manifest_sha256": c.digest(dataset)},
        "model_sha256": c.digest(bundle / "model.tflite"),
        "model_version": "fixture", "config": {"fixture": True},
    })
    baseline = c.sweep(images(), classes, [.25], .25)[0]
    save(evaluation / "report.json", {
        "checkpoint_sha256": "a" * 64, "dataset_manifest_sha256": c.digest(dataset),
        "provisional": True, "final_acceptance": False, "split_policy": "provisional",
        "fixed_thresholds": {"confidence": .25, "match_iou": .5},
        "scene_count": 2, "negative_scene_count": 1,
        "per_class": [{**row, "map_50_95": .5} for row in baseline["per_class"]],
    })
    save(evaluation / "predictions.json", images())
    return evaluation, bundle, dataset, Path(__file__).with_name("acceptance-draft.json")


def test_diagnostic_remains_unfrozen_even_on_perfect_filtered_predictions(diagnostic_inputs):
    result = c.diagnose(*diagnostic_inputs, [.25, .5])
    assert result["status"] == "diagnostic_only"
    assert not result["final_acceptance"]
    assert not result["calibration_complete"]
    assert result["best_micro_f1_confidence_for_comparison_only"] == .5
    assert result["provenance"]["tool_sha256"] == c.digest(c.__file__)
    assert len(result["sweep"][1]["draft_class_failures"]) == len(c.canonical()[0]["classes"])


@pytest.mark.parametrize("change", ["final_test", "checkpoint", "membership", "counts", "model"])
def test_changed_inputs_and_final_test_tuning_are_rejected(diagnostic_inputs, change):
    evaluation, bundle, _, _ = diagnostic_inputs
    path = evaluation / "report.json"
    report = json.loads(path.read_text(encoding="utf-8"))
    if change == "final_test":
        report["provisional"] = False
        report["final_acceptance"] = True
    elif change == "checkpoint":
        report["checkpoint_sha256"] = "b" * 64
    elif change == "counts":
        report["per_class"][0]["tp"] += 1
    elif change == "membership":
        predictions = json.loads((evaluation / "predictions.json").read_text(encoding="utf-8"))
        predictions[0]["source_name"] = "held-out"
        (evaluation / "predictions.json").write_text(json.dumps(predictions), encoding="utf-8")
    else:
        (bundle / "model.tflite").write_bytes(b"corrupted")
    path.write_text(json.dumps(report), encoding="utf-8")
    with pytest.raises(ValueError):
        c.diagnose(*diagnostic_inputs, [.25, .5])
