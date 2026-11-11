import importlib.util
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
spec = importlib.util.spec_from_file_location("evaluate", Path(__file__).with_name("evaluate.py"))
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)


def item(class_id=0, confidence=0.9, box=None):
    return {"class_id": class_id, "confidence": confidence, "box": box or [0, 0, 20, 20]}


def test_duplicate_predictions_and_negative_false_positives():
    images = [{"truth": [item()], "predictions": [item(), item(confidence=0.8)]},
              {"truth": [], "predictions": [item()]},
              {"truth": [item(class_id=1)], "predictions": []}]
    result = e.summarize(images, 2)
    assert result["per_class"][0] == {"tp": 1, "fp": 2, "fn": 0,
                                      "precision": 1 / 3, "recall": 1.0}
    assert result["per_class"][1]["fn"] == 1
    assert result["negative_scene_count"] == 1
    assert result["by_source_pixel_size"]["small"]["recall"] == 0.5
    assert result["by_source_pixel_size"]["large"]["recall"] is None


def test_wrong_class_and_iou():
    result = e.summarize([{"truth": [item()], "predictions": [item(class_id=1)]}], 2)
    assert result["per_class"][0]["fn"] == 1
    assert result["per_class"][1]["fp"] == 1
    assert e.iou([0, 0, 10, 10], [20, 20, 30, 30]) == 0
    assert e.iou([0, 0, 10, 10], [0, 0, 10, 10]) == 1


def test_unknown_predicted_class_is_rejected():
    with pytest.raises(ValueError, match="outside canonical"):
        e.summarize([{"truth": [], "predictions": [item(class_id=2)]}], 2)
