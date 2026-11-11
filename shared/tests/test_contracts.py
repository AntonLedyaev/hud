"""Interchange rejection tests, independent of GPU/model downloads."""
import copy
import importlib.util
import json
from pathlib import Path

import pytest
import yaml
from jsonschema import ValidationError

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("contracts", ROOT / "contracts.py")
c = importlib.util.module_from_spec(spec)
spec.loader.exec_module(c)


def sample(schema):
    if "const" in schema:
        return schema["const"]
    if "enum" in schema:
        return schema["enum"][0]
    if "oneOf" in schema:
        return sample(schema["oneOf"][0])
    if schema.get("type") == "object":
        value = {k: sample(v) for k, v in schema.get("properties", {}).items()}
        return value or {"python": "test"}
    if schema.get("type") == "array":
        return [sample(schema["items"]) for _ in range(schema.get("minItems", 0))]
    return {"string": "a" * 64, "integer": 1, "number": 0.5,
            "boolean": False, "null": None}[schema["type"]]


@pytest.fixture
def documents():
    values = {kind: sample(schema) for kind, schema in c.SCHEMAS.items()}
    labels = c.load_labels()
    manifest = values["model-manifest"]
    manifest["labels"] = labels
    manifest["input"]["shape"] = [1, 320, 320, 3]
    manifest["output"]["tensor"]["shape"] = [1, 2100, 4 + len(labels["classes"])]
    for kind in ("observation", "event", "reference", "evaluation-report"):
        values[kind]["labels_sha256"] = labels["sha256"]
    values["reference"]["scenes"][0]["ground_truth"] = []
    values["reference"]["scenes"][0]["expected_events"] = []
    values["evaluation-report"]["per_class"] = [
        {"class_id": item["id"], "sample_count": 0, "precision": 0,
         "recall": 0, "map_50_95": 0} for item in labels["classes"]
    ]
    values["observation"]["detections"] = [sample(c.DETECTION)]
    for detection in (values["observation"]["detections"][0],
                      values["event"]["payload"]["signs"][0]):
        detection["box"] = {"left": 0.1, "top": 0.2, "right": 0.4, "bottom": 0.5}
    return values


@pytest.mark.parametrize("kind", c.SCHEMAS)
def test_roundtrip(kind, documents):
    value = documents[kind]
    assert c.validate(kind, json.loads(json.dumps(value))) == value


@pytest.mark.parametrize("kind", c.SCHEMAS)
def test_unknown_version(kind, documents):
    documents[kind]["schema_version"] = 2
    with pytest.raises(ValidationError):
        c.validate(kind, documents[kind])


@pytest.mark.parametrize("field,value", [("confidence", 1.1), ("confidence", float("nan")),
                                       ("class_id", 999), ("class_id", -1)])
def test_bad_detection(field, value, documents):
    documents["observation"]["detections"][0][field] = value
    with pytest.raises((ValueError, ValidationError)):
        c.validate("observation", documents["observation"])


def test_inverted_box(documents):
    documents["observation"]["detections"][0]["box"]["left"] = 0.9
    with pytest.raises(ValueError):
        c.validate("observation", documents["observation"])


@pytest.mark.parametrize("mutation", ["reorder", "missing", "hash", "tensor"])
def test_incompatible_model(mutation, documents):
    value = documents["model-manifest"]
    if mutation == "reorder":
        value["labels"]["classes"].reverse()
    elif mutation == "missing":
        value["labels"]["classes"].pop()
    elif mutation == "hash":
        value["labels"]["sha256"] = "0" * 64
    else:
        value["input"]["shape"] = [1, 320, 320, 1]
    with pytest.raises(ValueError):
        c.validate("model-manifest", value)


@pytest.mark.parametrize("mutation", ["reorder", "duplicate", "missing"])
def test_invalid_canonical_labels(tmp_path, mutation):
    source = yaml.safe_load((ROOT / "labels.yaml").read_text())
    if mutation == "reorder":
        source["classes"].reverse()
    elif mutation == "duplicate":
        source["classes"][1]["name"] = source["classes"][0]["name"]
    else:
        source["classes"].pop(1)
    path = tmp_path / "labels.yaml"
    path.write_text(yaml.safe_dump(source))
    with pytest.raises(ValueError):
        c.load_labels(path)


def test_generation(tmp_path):
    path = tmp_path / "labels.json"
    c.generate(path)
    assert json.loads(path.read_text()) == c.load_labels()


def test_schema_documents_match_source():
    for kind, schema in c.SCHEMAS.items():
        saved = json.loads((ROOT / "schemas" / f"{kind}.v1.schema.json").read_text())
        saved.pop("$schema")
        assert saved == schema


def test_fixtures_are_valid(documents):
    for kind, document in documents.items():
        c.validate(kind, copy.deepcopy(document))


@pytest.mark.parametrize("mutation", ["missing_metric", "missing_class"])
def test_incomplete_report(mutation, documents):
    report = documents["evaluation-report"]
    if mutation == "missing_class":
        report["per_class"].pop()
    else:
        del report["state"]["agreement"]
    with pytest.raises((ValueError, ValidationError)):
        c.validate("evaluation-report", report)


def test_committed_fixtures():
    for kind in c.SCHEMAS:
        value = json.loads((ROOT / "fixtures" / f"{kind}.v1.json").read_text())
        c.validate(kind, value)
