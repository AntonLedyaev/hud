import hashlib
import json
import sys
from pathlib import Path

import pytest
import yaml

sys.path.insert(0, str(Path(__file__).parent))
from train import ROOT, canonical, check_dataset, digest  # noqa: E402


@pytest.fixture
def dataset(tmp_path):
    labels, _ = canonical()
    records = []
    for split in ("train", "val"):
        image = tmp_path / "images" / split / "00000.png"
        label = tmp_path / "labels" / split / "00000.txt"
        image.parent.mkdir(parents=True)
        label.parent.mkdir(parents=True)
        image.write_bytes(b"synthetic fixture, no image decode is needed for hash checking")
        label.write_text("0 0.5 0.5 0.2 0.2\n")
        records.append({"source_name": "00000.ppm", "split": split,
                        "labels": [[0, 0.5, 0.5, 0.2, 0.2]],
                        "image_sha256": digest(image), "label_sha256": digest(label)})
    (tmp_path / "manifest.json").write_text(json.dumps({
        "labels_sha256": digest(ROOT / "shared/labels.yaml"), "records": records}))
    (tmp_path / "dataset.yaml").write_text(yaml.safe_dump({
        "path": str(tmp_path), "train": "images/train", "val": "images/val",
        "names": {c["id"]: c["name"] for c in labels["classes"]}}))
    return tmp_path


def test_coverage_and_missing_classes_are_explicit(dataset):
    _, scenes, coverage = check_dataset(dataset)
    assert scenes == {"train": 1, "val": 1}
    assert coverage[0]["train"] == coverage[0]["val"] == 1
    assert all(c["val"] == 0 for c in coverage[1:])


def test_tampered_label_is_rejected(dataset):
    (dataset / "labels/val/00000.txt").write_text("1 0.5 0.5 0.2 0.2\n")
    with pytest.raises(ValueError, match="modified dataset"):
        check_dataset(dataset)


def test_unverified_dataset_paths_are_rejected(dataset):
    path = dataset / "dataset.yaml"
    data = yaml.safe_load(path.read_text())
    data["val"] = "images/train"
    path.write_text(yaml.safe_dump(data))
    with pytest.raises(ValueError, match="paths disagree"):
        check_dataset(dataset)


def test_changed_labels_hash_is_rejected(dataset):
    path = dataset / "manifest.json"
    manifest = json.loads(path.read_text())
    manifest["labels_sha256"] = hashlib.sha256(b"different labels").hexdigest()
    path.write_text(json.dumps(manifest))
    with pytest.raises(ValueError, match="incompatible canonical"):
        check_dataset(dataset)
