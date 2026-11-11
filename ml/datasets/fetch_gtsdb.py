"""Download the author-linked GTSDB archive and extract full scenes, not sign crops."""

from __future__ import annotations

import argparse
import json
import shutil
import urllib.request
from pathlib import Path
from zipfile import ZipFile

from gtsdb import ROOT, SOURCE, digest


def fetch(destination):
    destination = Path(destination).resolve()
    destination.mkdir(parents=True, exist_ok=True)
    archive = destination / "FullIJCNN2013.zip"
    partial = archive.with_suffix(".zip.part")
    expected_size = 1661866983
    if not archive.exists():
        if not partial.exists() or partial.stat().st_size != expected_size:
            # Download into a partial file; interrupted files are never extracted.
            with urllib.request.urlopen(SOURCE + archive.name, timeout=60) as response:
                with partial.open("wb") as output:
                    shutil.copyfileobj(response, output, length=1024 * 1024)
        if partial.stat().st_size != expected_size:
            raise ValueError("archive size differs from publisher inventory")
        partial.replace(archive)
    if archive.stat().st_size != expected_size:
        raise ValueError("archive size differs from publisher inventory")
    with ZipFile(archive) as zipped:
        members = [entry for entry in zipped.infolist()
                   if entry.filename.startswith("FullIJCNN2013/")
                   and entry.filename.count("/") == 1 and not entry.is_dir()]
        images = [entry for entry in members if entry.filename.endswith(".ppm")]
        if len(images) != 900:
            raise ValueError("expected exactly 900 full-scene images")
        for entry in members:
            target = (destination / Path(entry.filename).name).resolve()
            if target.parent != destination:
                raise ValueError("archive path escapes destination")
            if target.exists() and target.stat().st_size == entry.file_size:
                continue
            with zipped.open(entry) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output)
    provenance = {"source_url": SOURCE + archive.name,
                  "archive_sha256": digest(archive), "archive_bytes": archive.stat().st_size,
                  "publisher_sha256": None,
                  "integrity": ("size and ZIP CRC; SHA256 recorded locally, "
                                "not publisher-authenticated"),
                  "annotation_sha256": digest(destination / "gt.txt"),
                  "license_reference": "ReadMe.txt section 1 in source archive",
                  "full_scene_count": len(images), "excluded": "per-class sign crop directories"}
    (destination / "provenance.json").write_text(
        json.dumps(provenance, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=ROOT / "ml/data/gtsdb-source")
    fetch(parser.parse_args().destination)
