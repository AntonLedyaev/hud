# Proposal

## Why

The repository has ML environment scaffolding but no Android application or validated road-sign recognition pipeline. Stage 1 needs an offline, measurable end-to-end prototype that connects model training to camera inference, estimated restriction state, and reviewable trip evidence.

## What Changes

- Build a Kotlin/Compose Android MVP using CameraX, LiteRT, Coroutines/Flow and Room.
- Prepare detection datasets, train and evaluate a baseline, and export a versioned model bundle for on-device inference.
- Derive all class mappings from `shared/labels.yaml` v1: its 12 classes define this iteration. Zone 30 and generic ends of speed limits are not supported by that taxonomy, despite the broader roadmap wording in `docs/CONTEXT.md`.
- Confirm observations over time before updating estimated speed-limit and overtaking state; show unknown and stale states explicitly.
- Persist trip events and bounded, opt-in image samples locally for review and future annotation.
- Define reproducible replay, model parity, device performance and road-trial acceptance gates.
- Exclude maps/implicit speed limits, intersection inference, GPS vehicle speed, background recording, sound alerts, hardware integration and automatic retraining. LED/night/rain performance is measured separately and is not assumed from daylight results.

## Capabilities

### New Capabilities

- `sign-model-bundle`: Dataset provenance, training/evaluation and a compatible model/label/preprocessing bundle.
- `camera-sign-inference`: Lifecycle-aware capture, bounded offline inference, geometry and diagnostic measurements.
- `restriction-state`: Temporal confirmation and deterministic estimated restriction state.
- `driver-hud`: Minimal driving view, session controls and honest availability indicators.
- `trip-evidence`: Local session/event storage, optional image samples and explicit export/deletion.

### Modified Capabilities

None; the project has no existing durable specs.

## Impact

Future implementation affects `android/`, `ml/` and derived artifacts from `shared/labels.yaml`. Model weights and raw datasets remain outside git. Training and Android must share bundle and replay contracts; no server is required. This change captures planning only. The developer selected a single Android app module with simple package-level layers and a single-stage YOLO baseline; phone-specific runtime and performance decisions require a real device benchmark.

## High-level Delivery Plan

1. Freeze the v1 label/bundle and timestamped observation contracts. Select a reference phone and predeclare recognition and sustained-performance acceptance thresholds before final evaluation.
2. Establish a buildable Android shell and deterministic observation replay. Verify state transitions and HUD unknown/error behavior using fixtures explicitly separated from real recognition.
3. Audit dataset coverage for all twelve classes, prepare leakage-free splits, train the selected baseline and validate its export against the source model. Missing data coverage is a gate, not a reason to claim full MVP completion.
4. Integrate foreground camera inference and HUD; verify geometry, frame dropping, lifecycle cleanup, CPU fallback and offline startup.
5. Add local trip evidence, bounded opt-in samples, export/deletion and interrupted-session recovery. Validate ordering and provenance by replaying exported events.
6. Run a sustained device benchmark and evaluate held-out annotated drives. Report per-class recognition, false state changes per hour, confirmation delay, state agreement on supported explicit-sign intervals and unknown/stale time. Keep unsupported conditions separate. Do not equate a successful build or an emulator demo with road-trial acceptance.

The selected architecture and its trade-offs are captured in [design.md](design.md); implementation tasks follow this design. No application or training code is introduced by this planning change.
