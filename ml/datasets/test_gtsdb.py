import importlib.util
import json
from pathlib import Path

import pytest
import yaml
from PIL import Image

spec = importlib.util.spec_from_file_location("gtsdb", Path(__file__).with_name("gtsdb.py"))
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)


@pytest.fixture
def prepared(tmp_path):
    images = tmp_path / "raw"
    images.mkdir()
    for index in range(3):
        Image.new("RGB", (100, 50), (index * 30, 10, 20)).save(images / f"{index:05}.ppm")
    source, _ = g.canonical()
    source_class = source["classes"][0]["gtsrb_id"]
    gt = tmp_path / "gt.txt"
    gt.write_text(f"00000.ppm;10;5;30;25;{source_class}\n00001.ppm;10;5;30;25;999\n")
    splits = tmp_path / "splits.json"
    splits.write_text(json.dumps({f"{i:05}.ppm": {"sequence_id": f"sequence-{i}", "split": split,
                                               "annotations_complete": True}
                                 for i, split in enumerate(("train", "val", "test"))}))
    return images, gt, splits, tmp_path / "converted"


def test_conversion_and_negative_scenes(prepared):
    manifest = g.convert(*prepared)
    output = prepared[-1]
    data = yaml.safe_load((output / "dataset.yaml").read_text())
    assert len(data["names"]) == len(g.canonical()[0]["classes"])
    assert (output / "labels/train/00000.txt").read_text().split() == [
        "0", "0.2", "0.3", "0.2", "0.4"]
    assert (output / "labels/val/00001.txt").read_text() == ""
    assert (output / "labels/test/00002.txt").read_text() == ""
    assert manifest["records"][1]["excluded_source_ids"] == [999]
    assert len(g.audit(prepared[1])["per_class"]) == len(data["names"])


def test_sequence_leakage(prepared):
    splits = prepared[2]
    assignments = json.loads(splits.read_text())
    assignments["00001.ppm"]["sequence_id"] = "sequence-0"
    splits.write_text(json.dumps(assignments))
    with pytest.raises(ValueError, match="sequence leakage"):
        g.convert(*prepared)
    assert not prepared[-1].exists()


def test_identical_image_leakage(prepared):
    images = prepared[0]
    (images / "00001.ppm").write_bytes((images / "00000.ppm").read_bytes())
    with pytest.raises(ValueError, match="identical image leakage"):
        g.convert(*prepared)


def test_bad_box(prepared):
    prepared[1].write_text("00000.ppm;0;0;101;50;1\n")
    with pytest.raises(ValueError, match="outside image"):
        g.convert(*prepared)
    assert not prepared[-1].exists()


def test_missing_assignments(prepared):
    prepared[2].write_text("{}")
    with pytest.raises(ValueError, match="missing"):
        g.convert(*prepared)


def test_crop_directories_are_not_scenes(tmp_path):
    with pytest.raises(ValueError, match="no full-scene"):
        g.convert(tmp_path, tmp_path / "unused", tmp_path / "unused", tmp_path / "out")


def test_output_is_not_overwritten(prepared):
    g.convert(*prepared)
    with pytest.raises(ValueError, match="already exists"):
        g.convert(*prepared)


def test_unannotated_image_is_not_a_negative(prepared):
    assignments = json.loads(prepared[2].read_text())
    assignments["00002.ppm"]["annotations_complete"] = False
    prepared[2].write_text(json.dumps(assignments))
    with pytest.raises(ValueError, match="unannotated scenes"):
        g.convert(*prepared)


def test_provisional_policy_is_explicit_and_preserves_official_boundary():
    assignments = {f"{i:05}.ppm": {"sequence_id": None,
                   "split": "train" if i < 600 else "val", "annotations_complete": True}
                   for i in range(900)}
    g.validate_splits(assignments, assignments, provisional=True)
    with pytest.raises(ValueError, match="invalid group"):
        g.validate_splits(assignments, assignments)
    assignments["00600.ppm"]["split"] = "train"
    with pytest.raises(ValueError, match="official provisional"):
        g.validate_splits(assignments, assignments, provisional=True)


def test_official_policy_rejects_train_only_annotations(tmp_path):
    images = tmp_path / "images"
    images.mkdir()
    for index in range(900):
        (images / f"{index:05}.ppm").touch()
    gt = tmp_path / "gt.txt"
    gt.write_text("00000.ppm;1;1;5;5;1\n")
    with pytest.raises(ValueError, match="full-archive annotations"):
        g.convert(images, gt, None, tmp_path / "out", provisional=True)
