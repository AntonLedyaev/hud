# DIY Head-Up Display

A DIY windshield HUD for older cars: current speed + camera-based traffic sign
recognition, projected onto the windshield — like the built-in TSR/HUD systems
in modern cars, but retrofittable.

**Status:** Stage 0 — infrastructure setup.

## Roadmap

| Stage | Goal |
|---|---|
| 0 | Repo, environments, AI workflow, harness |
| 1 | Android MVP: real-time sign recognition + speed-limit state logic, model training |
| 2 | Raspberry Pi 5 + camera + AI accelerator, OLED output, GPS speed |
| 3 | Form factor: windshield projection, enclosure, car power |
| 4 | OBD-II integration for real vehicle data |

Full project context: [docs/CONTEXT.md](docs/CONTEXT.md)

## Repo layout

- `ml/` — Python: dataset preparation, YOLO training, export to TFLite/ONNX
- `android/` — Kotlin/Compose MVP app (Stage 1)
- `rpi/` — Raspberry Pi code (Stage 2+)
- `shared/` — sign class definitions and configs shared across subprojects
- `docs/` — context, decisions, notes

## Setup

Each subproject has its own README with setup instructions (filled in during
Stage 0/1). Raw datasets and trained weights are not tracked in git.

## Disclaimer

This is a driver-assistance hobby project, not a certified device. It must never
be treated as a source of truth for speed limits or road rules.
