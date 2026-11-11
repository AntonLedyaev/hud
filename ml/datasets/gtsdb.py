"""Audit and convert GTSDB scenes using explicit, reviewed sequence/split metadata."""

from __future__ import annotations

import argparse
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path

import yaml
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
SOURCE = "https://sid.erda.dk/public/archives/ff17dc924eba88d5d01a807357d6614c/"


def digest(path):
    checksum = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def canonical(path=ROOT / "shared/labels.yaml"):
    value = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    classes = value["classes"]
    if [c["id"] for c in classes] != list(range(len(classes))):
        raise ValueError("canonical labels must have contiguous ordered IDs")
    mapping = {c["gtsrb_id"]: c["id"] for c in classes if c["gtsrb_id"] is not None}
    if len(mapping) != len(set(c["gtsrb_id"] for c in classes)):
        raise ValueError("ambiguous GTSRB mapping")
    return value, mapping


def annotations(path):
    result = defaultdict(list)
    for number, line in enumerate(Path(path).read_text(encoding="utf-8-sig").splitlines(), 1):
        if not line.strip():
            continue
        fields = line.strip().split(";")
        if len(fields) != 6:
            raise ValueError(f"line {number}: expected filename;x1;y1;x2;y2;class")
        name = fields[0]
        if Path(name).name != name or not name.endswith(".ppm"):
            raise ValueError(f"line {number}: unsafe or unsupported scene path {name}")
        x1, y1, x2, y2, class_id = map(int, fields[1:])
        if class_id < 0 or min(x1, y1) < 0 or x1 >= x2 or y1 >= y2:
            raise ValueError(f"line {number}: invalid class or box")
        result[name].append((x1, y1, x2, y2, class_id))
    if not result:
        raise ValueError("empty annotation file")
    return dict(result)


def audit(path, labels=ROOT / "shared/labels.yaml", images_dir=None):
    source, mapping = canonical(labels)
    rows = annotations(path)
    counts = Counter(box[4] for boxes in rows.values() for box in boxes)
    result = {
        "source_url": SOURCE, "annotation_sha256": digest(path),
        "labels_sha256": digest(labels), "labels_version": source["version"],
        "annotated_scene_count": len(rows), "annotation_count": sum(counts.values()),
        "first_scene": min(rows), "last_scene": max(rows),
        "per_class": [{"id": c["id"], "name": c["name"],
                       "source_id": c["gtsrb_id"], "instances": counts[c["gtsrb_id"]]}
                      for c in source["classes"]],
        "excluded_source_class_counts": {
            str(k): v for k, v in sorted(counts.items()) if k not in mapping},
        "split_coverage": "unknown until reviewed sequence/split manifest is supplied",
    }
    if images_dir:
        images = {p.name for p in Path(images_dir).glob("*.ppm")}
        if set(rows) - images:
            raise ValueError("annotations refer to unavailable images")
        result["full_scene_count"] = len(images)
        result["no_annotated_sign_scenes"] = len(images - set(rows))
        result["no_target_sign_scenes"] = sum(
            not any(box[4] in mapping for box in rows.get(name, [])) for name in images)
    return result


def validate_splits(images, assignments, provisional=False):
    if set(images) != set(assignments):
        missing = sorted(set(images) - set(assignments))[:5]
        extra = sorted(set(assignments) - set(images))[:5]
        raise ValueError(f"split manifest must cover exactly all scenes; {missing=}, {extra=}")
    group_splits = {}
    for name, assignment in assignments.items():
        if set(assignment) != {"sequence_id", "split", "annotations_complete"}:
            raise ValueError(f"{name}: expected sequence_id, split, annotations_complete")
        if assignment["annotations_complete"] is not True:
            raise ValueError(f"{name}: unannotated scenes cannot become negative examples")
        group, split = assignment["sequence_id"], assignment["split"]
        if provisional:
            expected = "train" if int(Path(name).stem) < 600 else "val"
            if group is not None or split != expected:
                raise ValueError(f"{name}: invalid official provisional assignment")
            continue
        if not isinstance(group, str) or not group.strip() or split not in ("train", "val", "test"):
            raise ValueError(f"{name}: invalid group/split")
        if group in group_splits and group_splits[group] != split:
            raise ValueError(f"sequence leakage: {group} crosses splits")
        group_splits[group] = split


def convert(images_dir, gt, split_file, output, labels=ROOT / "shared/labels.yaml",
            provisional=False):
    images_dir, output = Path(images_dir), Path(output)
    images = {p.name: p for p in images_dir.glob("*.ppm")}
    if not images:
        raise ValueError("no full-scene PPM images at source root; crop directories are not scenes")
    rows = annotations(gt)
    if set(rows) - set(images):
        raise ValueError("annotations refer to missing full-scene images")
    if provisional:
        if set(images) != {f"{i:05}.ppm" for i in range(900)}:
            raise ValueError("official policy requires all 900 full scenes")
        if not any(int(Path(name).stem) >= 600 for name in rows):
            raise ValueError("official policy requires full-archive annotations, not train-only gt")
        assignments = {name: {"sequence_id": None,
                       "split": "train" if int(Path(name).stem) < 600 else "val",
                       "annotations_complete": True} for name in images}
    else:
        assignments = json.loads(Path(split_file).read_text(encoding="utf-8"))
    validate_splits(images, assignments, provisional)
    source, mapping = canonical(labels)
    # Preflight all boxes and identical-image leakage before writing any output.
    records, content_splits = [], {}
    for name, path in sorted(images.items()):
        assignment = assignments[name]
        image_hash = digest(path)
        if image_hash in content_splits and content_splits[image_hash] != assignment["split"]:
            raise ValueError(f"identical image leakage at {name}")
        content_splits[image_hash] = assignment["split"]
        with Image.open(path) as image:
            width, height = image.size
        encoded, excluded = [], []
        for x1, y1, x2, y2, class_id in rows.get(name, []):
            if not (0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height):
                raise ValueError(f"{name}: box outside image {width}x{height}")
            if class_id not in mapping:
                excluded.append(class_id)
                continue
            encoded.append([mapping[class_id], (x1 + x2) / (2 * width),
                            (y1 + y2) / (2 * height), (x2 - x1) / width, (y2 - y1) / height])
        records.append({"source_name": name, "sha256": image_hash, **assignment,
                        "width": width, "height": height, "labels": encoded,
                        "excluded_source_ids": excluded})
    if output.exists():
        raise ValueError("output already exists; choose a new versioned directory")
    output.mkdir(parents=True)
    for record in records:
        split, stem = record["split"], Path(record["source_name"]).stem
        target = output / "images" / split / f"{stem}.png"
        target.parent.mkdir(parents=True, exist_ok=True)
        with Image.open(images[record["source_name"]]) as image:
            image.convert("RGB").save(target)
        label_path = output / "labels" / split / f"{stem}.txt"
        label_path.parent.mkdir(parents=True, exist_ok=True)
        label_path.write_text("".join(" ".join(str(v) for v in box) + "\n"
                                     for box in record["labels"]), encoding="utf-8")
        record["image_sha256"] = digest(target)
        record["label_sha256"] = digest(label_path)
    manifest = {"schema_version": 1, "source_url": SOURCE,
                "annotation_sha256": digest(gt),
                "split_sha256": (hashlib.sha256(json.dumps(assignments, sort_keys=True).encode())
                                 .hexdigest() if provisional else digest(split_file)),
                "split_policy": "gtsdb_official_provisional" if provisional else "reviewed_groups",
                "sequence_independence_verified": not provisional,
                "labels_sha256": digest(labels), "labels_version": source["version"],
                "box_convention": "xyxy pixel edges, upper edge exclusive",
                "records": records}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    data = {"path": str(output.resolve()), "train": "images/train", "val": "images/val",
            "names": {c["id"]: c["name"] for c in source["classes"]}}
    if any(record["split"] == "test" for record in records):
        data["test"] = "images/test"
    (output / "dataset.yaml").write_text(yaml.safe_dump(data, sort_keys=False), encoding="utf-8")
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--annotations", type=Path, required=True)
    parser.add_argument("--audit-output", type=Path)
    parser.add_argument("--images", type=Path)
    parser.add_argument("--splits", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--official-provisional", action="store_true")
    args = parser.parse_args()
    if args.audit_output:
        args.audit_output.parent.mkdir(parents=True, exist_ok=True)
        args.audit_output.write_text(
            json.dumps(audit(args.annotations, images_dir=args.images), indent=2) + "\n",
            encoding="utf-8")
    if args.output:
        if not args.images or (not args.splits and not args.official_provisional):
            parser.error("conversion requires --images and --splits")
        convert(args.images, args.annotations, args.splits, args.output,
                provisional=args.official_provisional)


if __name__ == "__main__":
    main()
