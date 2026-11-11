"""Version-one interchange contracts; no training/runtime dependencies."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator

ROOT = Path(__file__).resolve().parent
HASH = {"type": "string", "pattern": "^[0-9a-f]{64}$"}
TEXT = {"type": "string", "minLength": 1}
NAT = {"type": "integer", "minimum": 0}
POS = {"type": "integer", "minimum": 1}
SCORE = {"type": "number", "minimum": 0, "maximum": 1}


def obj(**properties):
    return {"type": "object", "properties": properties, "required": list(properties),
            "additionalProperties": False}


def enum(*values):
    return {"enum": list(values)}


def array(items, minimum=0):
    return {"type": "array", "items": items, "minItems": minimum}


CONFIG = obj(
    version=POS, confidence_threshold=SCORE, minimum_hits=POS,
    confirmation_window_ms=POS, association_iou=SCORE, rearm_absence_ms=POS,
    estimate_max_age_ms=POS, stop_display_ms=POS,
    sampling_enabled={"type": "boolean"}, sample_interval_ms=POS,
    sample_budget_bytes=NAT, event_queue_capacity=POS, storage_budget_bytes=POS,
)
BOX = obj(left=SCORE, top=SCORE, right=SCORE, bottom=SCORE)
DETECTION = obj(class_id=NAT, confidence=SCORE, box=BOX)
PROVENANCE = obj(run_id=TEXT, checkpoint_sha256=HASH, config_sha256=HASH,
                 dataset_manifest_sha256=HASH, tools={"type": "object",
                 "minProperties": 1, "additionalProperties": TEXT})
QUANT = {"oneOf": [{"type": "null"}, obj(scale={"type": "number",
         "exclusiveMinimum": 0}, zero_point={"type": "integer"})]}
TENSOR = obj(shape=array(POS, 1), dtype=enum("float32", "float16", "int8", "uint8"),
             quantization=QUANT)
LABELS = obj(version=POS, sha256=HASH, classes=array(obj(id=NAT, name=TEXT), 1))

MANIFEST = obj(
    schema_version={"const": 1}, model_version=TEXT, model_sha256=HASH,
    labels=LABELS, provenance=PROVENANCE, input=TENSOR,
    preprocessing=obj(channel_order=enum("RGB", "BGR"), layout=enum("NHWC", "NCHW"),
                      normalization=obj(scale={"type": "number"},
                                        offset={"type": "number"}),
                      resize=enum("letterbox", "stretch"), padding_value=NAT,
                      rotation=enum("upright_before_resize")),
    output=obj(tensor=TENSOR, layout=enum("BNC", "BCN"),
               box_format=enum("cxcywh", "xyxy"),
               coordinates=enum("normalized_input", "input_pixels"),
               score_decoding=enum("class_probability", "objectness_times_class"),
               nms_owner=enum("model", "consumer"), confidence_threshold=SCORE,
               nms_iou=SCORE), config=CONFIG,
)
OBSERVATION = obj(
    schema_version={"const": 1}, session_id=TEXT, reset_generation=NAT,
    frame_id=NAT, capture_offset_ms=NAT, model_version=TEXT,
    labels_sha256=HASH, detections=array(DETECTION),
)
EVENT = obj(
    schema_version={"const": 1}, session_id=TEXT, reset_generation=NAT,
    sequence=NAT, offset_ms=NAT, model_version=TEXT, labels_sha256=HASH,
    config=CONFIG,
    payload={"oneOf": [
        obj(kind={"const": "confirmed_batch"}, frame_id=NAT, signs=array(DETECTION, 1)),
        obj(kind=enum("tick", "reset")),
        obj(kind=enum("transition", "conflict", "error"), reason=TEXT,
            source_sequences=array(NAT), details=TEXT),
        obj(kind={"const": "gap"}, dropped_events=POS),
    ]},
)
SCHEMAS = {"model-manifest": MANIFEST, "observation": OBSERVATION, "event": EVENT}
REFERENCE = obj(
    schema_version={"const": 1}, labels_sha256=HASH, synthetic={"type": "boolean"},
    split=enum("validation", "test"),
    parity=obj(score_absolute_tolerance=SCORE, minimum_box_iou=SCORE),
    scenes=array(obj(scene_id=TEXT, sequence_id=TEXT,
                     condition=enum("printed_daylight", "led", "night", "rain"),
                     ground_truth=array(DETECTION), expected_events=array(EVENT)), 1),
)
REPORT = obj(
    schema_version={"const": 1}, labels_sha256=HASH,
    model_sha256=HASH, reference_sha256=HASH, runtime=TEXT,
    config=CONFIG, condition=enum("printed_daylight", "led", "night", "rain"),
    negative_scene_count=NAT,
    per_class=array(obj(class_id=NAT, sample_count=NAT, precision=SCORE,
                        recall=SCORE, map_50_95=SCORE), 1),
    state=obj(false_transitions_per_hour={"type": "number", "minimum": 0},
              confirmation_delay_p95_ms=NAT, agreement=SCORE,
              unknown_fraction=SCORE, stale_fraction=SCORE),
)
SCHEMAS.update({"reference": REFERENCE, "evaluation-report": REPORT})


def load_labels(path=ROOT / "labels.yaml"):
    """Hash exact canonical bytes: formatting changes deliberately invalidate bundles."""
    raw = Path(path).read_bytes()
    source = yaml.safe_load(raw)
    classes = source["classes"]
    ids = [item["id"] for item in classes]
    names = [item["name"] for item in classes]
    if not classes or any(type(value) is not int for value in ids):
        raise ValueError("labels require integer IDs")
    if ids != list(range(len(classes))) or len(set(names)) != len(names):
        raise ValueError("labels require ordered contiguous IDs and unique names")
    result = {"version": source["version"], "sha256": hashlib.sha256(raw).hexdigest(),
              "classes": [{"id": c["id"], "name": c["name"]} for c in classes]}
    Draft202012Validator(LABELS).validate(result)
    return result


def validate(kind, document, labels=None):
    """Validate structure and cross-field invariants before accepting external data."""
    labels = load_labels() if labels is None else labels
    # JSON does not permit NaN/Infinity, even though Python's encoder does by default.
    json.dumps(document, allow_nan=False)
    Draft202012Validator(SCHEMAS[kind]).validate(document)
    if kind == "model-manifest":
        if document["labels"] != labels:
            raise ValueError("incompatible canonical labels")
        tensor = document["input"]
        shape = tensor["shape"]
        channel = 3 if document["preprocessing"]["layout"] == "NHWC" else 1
        if len(shape) != 4 or shape[0] != 1 or shape[channel] != 3:
            raise ValueError("expected one three-channel image")
        output = document["output"]
        output_shape = output["tensor"]["shape"]
        feature_axis = 2 if output["layout"] == "BNC" else 1
        features = 4 + len(labels["classes"])
        if output["score_decoding"] == "objectness_times_class":
            features += 1
        if len(output_shape) != 3 or output_shape[0] != 1:
            raise ValueError("expected rank-three batched detector output")
        if output_shape[feature_axis] != features:
            raise ValueError("output feature count disagrees with labels/score decoding")
        for declared_tensor in (tensor, output["tensor"]):
            integer = declared_tensor["dtype"] in ("int8", "uint8")
            if integer != (declared_tensor["quantization"] is not None):
                raise ValueError("tensor dtype/quantization mismatch")
            if integer:
                low, high = (-128, 127) if declared_tensor["dtype"] == "int8" else (0, 255)
                if not low <= declared_tensor["quantization"]["zero_point"] <= high:
                    raise ValueError("zero point outside tensor dtype")
        if document["preprocessing"]["padding_value"] > 255:
            raise ValueError("padding byte outside range")
    elif kind in ("reference", "evaluation-report"):
        if document["labels_sha256"] != labels["sha256"]:
            raise ValueError("incompatible labels hash")
        if kind == "reference":
            seen = set()
            for scene in document["scenes"]:
                if scene["scene_id"] in seen:
                    raise ValueError("duplicate scene ID")
                seen.add(scene["scene_id"])
                validate("observation", {
                    "schema_version": 1, "session_id": scene["sequence_id"],
                    "reset_generation": 0, "frame_id": 0, "capture_offset_ms": 0,
                    "model_version": "ground-truth", "labels_sha256": labels["sha256"],
                    "detections": scene["ground_truth"],
                }, labels)
                for event in scene["expected_events"]:
                    validate("event", event, labels)
        else:
            if [row["class_id"] for row in document["per_class"]] != [
                row["id"] for row in labels["classes"]
            ]:
                raise ValueError("report requires every canonical class in order")
    else:
        if document["labels_sha256"] != labels["sha256"]:
            raise ValueError("incompatible labels hash")
        detections = document.get("detections", document.get("payload", {}).get("signs", []))
        for detection in detections:
            if detection["class_id"] >= len(labels["classes"]):
                raise ValueError("unknown class ID")
            box = detection["box"]
            if box["left"] >= box["right"] or box["top"] >= box["bottom"]:
                raise ValueError("box must have positive area")
    return document


def generate(output):
    """Produce one JSON mapping for both Python training and Android assets."""
    output = Path(output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(load_labels(), indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generate-labels", type=Path)
    parser.add_argument("--write-schemas", type=Path)
    parser.add_argument("--kind", choices=SCHEMAS)
    parser.add_argument("--validate", type=Path)
    args = parser.parse_args()
    if args.generate_labels:
        generate(args.generate_labels)
    if args.write_schemas:
        args.write_schemas.mkdir(parents=True, exist_ok=True)
        for kind, schema in SCHEMAS.items():
            document = {"$schema": "https://json-schema.org/draft/2020-12/schema", **schema}
            (args.write_schemas / f"{kind}.v1.schema.json").write_text(
                json.dumps(document, indent=2) + "\n", encoding="utf-8")
    if args.validate:
        if not args.kind:
            parser.error("--validate requires --kind")
        validate(args.kind, json.loads(args.validate.read_text(encoding="utf-8")))


if __name__ == "__main__":
    main()
