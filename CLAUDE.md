# CLAUDE.md — DIY Head-Up Display

## What this project is

DIY windshield HUD for older cars: shows current speed and traffic signs recognized
by a camera (like built-in TSR systems in modern cars). Solo pet project, built in
stages: mobile MVP → Raspberry Pi hardware → windshield projection → OBD-II.

Full context, decisions, risks and roadmap: **docs/CONTEXT.md** — read it before
architectural work. Keep it updated when decisions are made (section 2) or
questions get answered (section 9).

## Current stage

**Stage 0 — infrastructure.** Repo skeleton, environments, AI workflow.
Next: Stage 1 — Android MVP app + YOLO model training (EU/German signs).

## Repo layout

```
docs/       project context and decision log
ml/         Python: dataset prep, training, export (TFLite/ONNX)
android/    Kotlin app (Stage 1) — created via Android Studio, not yet initialized
rpi/        Raspberry Pi code (Stage 2+) — empty for now
shared/     single source of truth for sign classes/labels, shared configs
```

## Hard rules

- `shared/labels.yaml` is the single source of truth for sign classes.
  ml/, android/ and rpi/ must derive class lists from it, never hardcode copies.
- Raw datasets and model weights never go into git (see .gitignore).
  Scripts that download/prepare data go into `ml/datasets/`.
- Code, comments, commit messages: **English**. Discussions with the developer: Russian.
- Commits: Conventional Commits (`feat:`, `fix:`, `docs:`, `chore:`), scope = subproject
  (`feat(ml): ...`, `feat(android): ...`).
- Safety framing: this is a driver *assistant*, never a source of truth.
  No feature should encourage taking eyes off the road.

## Developer profile — adjust explanations accordingly

Fullstack dev (JS/TS primary). Knows: Python, Keras/TensorFlow basics, old Java
Android experience. **New to:** Kotlin, Jetpack Compose, PyTorch, embedded/RPi.

- Kotlin/Compose and embedded code: explain non-obvious decisions and idioms,
  don't just emit code.
- PyTorch: can move faster, concepts map from Keras/TF, but flag PyTorch-specific
  idioms (autograd, DataLoader, no compile/fit).

## Workflow

- Before big decisions (model architecture, hardware choice, app architecture):
  present 2–3 options with trade-offs, wait for the developer's pick, then record
  it in docs/CONTEXT.md section 2.
- Prefer small verifiable steps: each work session should end in something
  runnable or testable.
- Environment commands per subproject: documented in each subproject's README
  (filled in as environments are set up).

## Known constraints (short version — details in CONTEXT.md §7)

- AI accelerator not yet purchased: Coral USB is likely to be replaced by
  Raspberry Pi AI Kit (Hailo-8L). Do not build Coral-specific code paths yet;
  keep export pipeline accelerator-agnostic (ONNX as intermediate).
- German implicit speed limits (road-type based) are invisible to a camera —
  out of scope for MVP, possible OSM fallback later.
- Variable LED signs on autobahns look unlike printed signs in datasets —
  expected weak spot, collect own data via the Stage 1 app.
