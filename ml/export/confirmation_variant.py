"""Create a new bundle with an experimental confirmation window; preserve weights."""

import argparse
import json
from pathlib import Path

from bundle import sha
from stage_bundle import stage, validate


def create_variant(source, destination, window_ms, config_version):
    source, destination = Path(source).resolve(), Path(destination).resolve()
    if destination.exists():
        raise ValueError("destination exists; use a new bundle directory")
    manifest = json.loads((source / "manifest.json").read_text(encoding="utf-8"))
    source_version = manifest["config"]["version"]
    if window_ms <= 0 or config_version <= manifest["config"]["version"]:
        raise ValueError("require a positive window and a new config version")
    # stage verifies the original model, canonical labels, profile and source parity.
    stage(source, destination)
    manifest["config"]["version"] = config_version
    manifest["config"]["confirmation_window_ms"] = window_ms
    validate("model-manifest", manifest)
    (destination / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    profile = json.loads((destination / "preprocessing-profile.json").read_text(encoding="utf-8"))
    profile["manifest_sha256"] = sha(destination / "manifest.json")
    (destination / "preprocessing-profile.json").write_text(json.dumps(profile, indent=2) + "\n", encoding="utf-8")
    (destination / "configuration-variant.json").write_text(json.dumps({
        "source_manifest_sha256": sha(source / "manifest.json"),
        "manifest_sha256": sha(destination / "manifest.json"),
        "model_sha256": manifest["model_sha256"],
        "source_config_version": source_version,
        "config_version": config_version, "confirmation_window_ms": window_ms,
        "status": "experimental_not_calibrated", "final_acceptance": False,
        "parity_scope": "Existing tensor/decoder parity retained; no confirmation quality claim",
    }, indent=2) + "\n", encoding="utf-8")
    return manifest


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--window-ms", type=int, required=True)
    parser.add_argument("--config-version", type=int, required=True)
    args = parser.parse_args()
    result = create_variant(args.source, args.destination, args.window_ms, args.config_version)
    print(json.dumps({"model": result["model_version"], "config": result["config"], "calibrated": False}))
