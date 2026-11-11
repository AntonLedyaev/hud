# Shared contracts

`labels.yaml` is the only maintained class list. `contracts.py` defines v1
interchange structures and validates semantic invariants beyond JSON Schema.
Generated JSON Schemas in `schemas/` must match these definitions; tests detect drift.

From the repository root on Windows, using the ML environment:

```powershell
ml/.venv/Scripts/python.exe shared/contracts.py --write-schemas shared/schemas
ml/.venv/Scripts/python.exe shared/contracts.py --generate-labels ml/runs/contracts/labels.json
ml/.venv/Scripts/python.exe -m pytest shared/tests -q
```

Use the same generated JSON mapping in ML and, once initialized, Android build
assets. No generated handwritten class enum is needed. The labels SHA-256 covers
the exact YAML bytes (including line endings); generate and package mappings from
the same checkout. Contiguous IDs and unique names are checked on load; a bundle
must match the current canonical version, hash and ordered mapping exactly.

`model-manifest` describes one image input and YOLO raw prediction output,
normalization, resize/rotation, box units, score decoding, NMS ownership,
quantization and provenance. Shape/feature counts must match the canonical labels.
This validates a declaration, not the actual FlatBuffer or weight bytes: bundle
loading and runtime tensor comparison remain separate implementation tasks.

`observation` uses normalized upright source-image xyxy boxes, capture offsets in
monotonic milliseconds, session ID and reset generation. `event` adds an ordered
sequence number and a complete effective config snapshot. Its tagged payload
supports confirmation batches, ticks, resets, transitions, conflicts, errors and
evidence gaps. Sequence monotonicity and session/reset isolation are stream-level
checks owned by the future reducer, not single-document validation.

Schemas reject unknown fields/versions. The Python validator additionally rejects
non-finite numbers, non-positive box areas, incompatible labels and inconsistent
tensor declarations. Cross-field checks must be ported to Android when its loader
is implemented; JSON Schema alone is insufficient.

Synthetic examples in `fixtures/` are contract fixtures, not a trained model or
valid road-trial evidence. Model files and generated training outputs belong in
ignored paths (`ml/runs/`, `ml/data/`), never beside committed synthetic fixtures.
