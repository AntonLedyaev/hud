import importlib.util
import json
import sys
from pathlib import Path

import pytest

HERE = Path(__file__).parent
sys.path.insert(0, str(HERE))
spec = importlib.util.spec_from_file_location("stage_bundle", HERE / "stage_bundle.py")
stage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stage)


@pytest.fixture
def source(tmp_path):
    from contracts import load_labels

    root = HERE.parents[1]
    manifest = json.loads((root / "shared/fixtures/model-manifest.v1.json").read_text())
    manifest["labels"] = load_labels()
    manifest["output"]["nms_owner"] = "consumer"
    source = tmp_path / "source"
    source.mkdir()
    (source / "model.tflite").write_bytes(b"synthetic checksum fixture, not a FlatBuffer")
    manifest["model_sha256"] = stage.sha(source / "model.tflite")
    (source / "manifest.json").write_text(json.dumps(manifest))
    (source / "parity.json").write_text(json.dumps({"status": "passed",
        "checkpoint_sha256": manifest["provenance"]["checkpoint_sha256"]}))
    (source / "preprocessing-profile.json").write_text(json.dumps({
        "version": 1, "interpolation": "nearest_pixel_centers", "rounding": "ties_to_even",
        "input_dtype": "RGB888", "manifest_sha256": stage.sha(source / "manifest.json")}))
    return source


def test_valid_bundle_stages_metadata_and_model(source, tmp_path):
    output = tmp_path / "staged"
    stage.stage(source, output)
    assert (output / "model.tflite").read_bytes() == (source / "model.tflite").read_bytes()


def test_confirmation_variant_preserves_weights_and_original(source, tmp_path):
    from confirmation_variant import create_variant
    original = (source / "manifest.json").read_bytes()
    version = json.loads(original)["config"]["version"]
    variant = tmp_path / "variant"
    manifest = create_variant(source, variant, 3000, version + 1)
    stage.stage(variant, tmp_path / "staged-variant")
    assert manifest["config"]["confirmation_window_ms"] == 3000
    assert manifest["config"]["version"] == version + 1
    assert (variant / "model.tflite").read_bytes() == (source / "model.tflite").read_bytes()
    assert (variant / "parity.json").read_bytes() == (source / "parity.json").read_bytes()
    assert (source / "manifest.json").read_bytes() == original
    with pytest.raises(ValueError):
        create_variant(source, variant, 3000, version + 1)
    with pytest.raises(ValueError):
        create_variant(source, tmp_path / "invalid", 3000, version)


@pytest.mark.parametrize("mutation", [
    "weights", "labels", "tensor", "profile", "parity", "contract"])
def test_incompatible_bundles_rejected(source, tmp_path, mutation):
    if mutation == "weights":
        (source / "model.tflite").write_bytes(b"modified")
    elif mutation in ("labels", "tensor", "contract"):
        path = source / "manifest.json"
        manifest = json.loads(path.read_text())
        if mutation == "labels":
            manifest["labels"]["classes"].reverse()
        elif mutation == "tensor":
            manifest["input"]["shape"] = [1, 640, 640, 1]
        else:
            manifest["output"]["nms_owner"] = "model"
        path.write_text(json.dumps(manifest))
    elif mutation == "profile":
        (source / "preprocessing-profile.json").write_text("{}")
    else:
        (source / "parity.json").write_text(json.dumps({"status": "failed"}))
    with pytest.raises((ValueError, KeyError)):
        stage.stage(source, tmp_path / "staged")
