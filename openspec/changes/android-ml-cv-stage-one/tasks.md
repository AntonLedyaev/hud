# Tasks

This checklist implements the existing proposal, five capability specs and selected single-module Android / single-stage YOLO design. All boxes start unchecked; planning completion is not implementation completion.

Follow groups in dependency order. Pure contract/domain and dataset work can proceed without a phone. Reference-device tasks require an identified physical phone; emulator results cannot satisfy them. Model size, runtime versions and calibration values are experiment outputs within the agreed architecture, not permission to change its scope. Raw datasets, weights, captured images and generated bundles remain outside git.

## 1. Shared contracts and evaluation inputs

- [ ] 1.1 Define versioned model-manifest and observation/event schemas under `shared/`, covering the complete inference contract, session/reset identity, monotonic times, provenance and effective configuration snapshots; verify valid fixtures round-trip and malformed versions, boxes, scores and tensor declarations fail validation.
- [ ] 1.2 Implement canonical label loading and generated ML/Android mappings from `shared/labels.yaml`; verify all twelve v1 IDs match and tests reject reordered IDs, duplicates, missing classes and hash mismatches without maintaining another handwritten class list.
- [ ] 1.3 Create the reference-fixture format and evaluation protocol, including per-class sample counts, negative scenes, numeric parity tolerances, supported conditions and state-event ground truth; verify a small synthetic fixture can be validated and that missing required metrics are reported explicitly.
- [ ] 1.4 Record the developer's reference phone, Android version and available device access in the acceptance profile; verify the profile identifies a real device and distinguishes measurements still pending. Leave device-dependent tasks incomplete if no phone is available.
- [ ] 1.5 Document the shared contracts, generation commands and artifact locations; verify documented commands reproduce fixture mappings and that model/data paths are ignored by git.

## 2. Android shell and export compatibility experiment

- [ ] 2.1 Initialize the Android Studio Kotlin/Compose project under `android/` with one `:app` module and `ui`, `session`, `camera`, `inference`, `domain`, `data` packages; verify the Gradle wrapper builds and the empty app launches, with no Android imports in domain code.
- [ ] 2.2 Verify the existing ML environment using `uv run scripts/check_env.py` and the existing smoke test; record toolchain versions and GPU execution, distinguishing toy COCO training from traffic-sign validation in `ml/README.md`.
- [ ] 2.3 Implement a minimal twelve-output YOLO export experiment under `ml/export/`, using the existing YOLO11-nano smoke setup as a starting candidate; verify source inference, ONNX export and TFLite conversion on fixed fixtures and record the exact checkpoint/config, converter versions and unsupported operators. Random or toy weights are explicitly experimental.
- [ ] 2.4 Execute the experimental TFLite model through an Android CPU harness on the reference phone, pin compatible runtime/SDK/build versions and compare tensors/decoded outputs with source fixtures; verify declared parity tolerances pass before committing to a long training run.
- [ ] 2.5 Document Windows Android setup, the local model staging input and export experiment commands in the subproject READMEs; verify a clean build can reproduce the experiment with recorded inputs and that a missing production bundle cannot silently select fixture inference.

## 3. Pure Kotlin confirmation and restriction state

- [ ] 3.1 Implement immutable domain observations, confirmed batches, state, actions and explicit clock ticks using the shared contracts; verify event serialization preserves ordering, source identity and configuration snapshots through a round trip.
- [ ] 3.2 Implement confidence filtering, bounded spatial association, distinct-frame confirmation and absence-based rearming; verify single-frame noise, duplicate frames, persistent signs and a later new encounter against the temporal-confirmation scenarios.
- [ ] 3.3 Implement speed and overtaking transitions plus transient STOP; verify replacement, matching/nonmatching end-of-80, end-of-all, independent overtaking cancellation and STOP expiry without changing unrelated fields.
- [ ] 3.4 Implement conflict handling, age/staleness, reset generations and session isolation; verify simultaneous conflicting signs become unknown, empty frames retain unexpired state, expiry advances without detections, and late/pre-reset/out-of-order results cannot restore an estimate.
- [ ] 3.5 Add deterministic event replay and domain documentation; verify the same event stream/config produces identical states and reasons on repeated runs and demonstrate an explicit debug replay without camera or model access.

## 4. Detection datasets and trained baseline

- [ ] 4.1 Audit the proposed detection sources for access, provenance, license references and coverage of the canonical twelve classes; deliver a per-source/class coverage report identifying missing annotations and excluding unmapped labels or classification-only crops from scene detection training.
- [ ] 4.2 Implement reproducible source conversion, box validation and split manifests under `ml/datasets/`; verify fixture conversions, sequence-level split isolation, negative-scene inclusion and actionable rejection of invalid boxes or cross-split leakage.
- [ ] 4.3 Implement the YOLO training entry point/config under `ml/training/` with seed, input/checkpoint hashes and tool versions; verify a short run on prepared sign data uses generated class mappings and produces a reproducible run manifest before starting full training.
- [ ] 4.4 Train and compare the bounded baseline experiments selected after the export check; deliver validation reports by class and sign size, record the chosen checkpoint/resolution and retain explicit coverage failures rather than claiming all-class success from an aggregate score.
- [ ] 4.5 Implement detection evaluation and document preparation/training/evaluation commands; verify metric aggregation on controlled fixtures, include mAP/precision/recall/confusion/sample counts and negative scenes, and separate printed daylight results from LED/night/rain conditions. Reserve the final test split for group 9.

## 5. Production model bundle and inference adapter

- [ ] 5.1 Package the selected trained model with generated labels, checksums, effective preprocessing/postprocessing and provenance; verify the bundle validator accepts the valid pair and rejects altered weights, labels, unsupported contracts and tensor metadata mismatches.
- [ ] 5.2 Implement Android bundle loading and the CPU LiteRT detector adapter with explicit buffer/runtime ownership; verify fixtures cover channel order, normalization, quantization, score decoding and exactly-once NMS, plus recoverable initialization and fatal inference errors.
- [ ] 5.3 Implement preprocessing and inverse geometry mapping to normalized upright source coordinates; verify rotated, padded, cropped and differently scaled fixtures recover the reference boxes within declared tolerances.
- [ ] 5.4 Run source-to-Android parity on the trained bundle and evaluate optional GPU execution with validated CPU fallback; deliver backend-specific reports and verify unsupported acceleration is reported, fallback works and no unvalidated backend is promoted for a road trial.
- [ ] 5.5 Integrate checksum-verified local model staging into the build and document bundle replacement/rollback; verify a fresh installed build loads its bundled runtime/model offline, an incompatible model disables recognition clearly, and reverting to a validated pair starts a new unknown-state session.

## 6. Camera pipeline and driving interface

- [ ] 6.1 Implement foreground session start/stop, permission handling, model validation and CameraX binding; verify denied/revoked permission, unavailable camera, background/resume and fatal engine errors release resources and require explicit restart with unknown state.
- [ ] 6.2 Connect CameraX to one inference worker with latest-frame backpressure and session/reset tokens; verify a deliberately slow detector retains at most one pending frame, releases every processed/dropped frame and discards late results after stop, restart or reset.
- [ ] 6.3 Connect ordered observations and ticks to domain state and immutable UI snapshots; verify live-fixture and replay paths yield identical transitions, expiry runs without detections and inference never blocks UI interactions.
- [ ] 6.4 Implement the Compose driving view, start/stop/reset actions and actionable availability errors; verify unknown/stale display, km/h labeling, independent overtaking/STOP indicators, no fabricated vehicle speed and no sound/modal acknowledgement on sign changes.
- [ ] 6.5 Add the pre-session diagnostic mode with mapped boxes, confidence, age and pipeline metrics; verify preview alignment on the reference device, ordinary driving mode hides raw candidates, and reports include FPS, drops, p50/p95 latencies, backend, thermal status, versions and warm-up boundaries.
- [ ] 6.6 Document and exercise the foreground-session and diagnostics walkthrough in `android/README.md`; record checks for offline cold start, explicit recovery and repeated start/stop without leaked camera or runtime resources.

## 7. Local evidence and bounded sampling

- [ ] 7.1 Implement Room session/event storage with ordered sequence numbers, source links, model metadata and effective config snapshots; verify recorded confirmed batches, conflicts, resets, ticks, transitions and errors reconstruct an uninterrupted trip with the domain replay runner.
- [ ] 7.2 Implement the bounded asynchronous evidence queue, storage budget and failure accounting; verify slow/full storage cannot stall inference, overflow produces counts/sequence gaps, the UI reports incomplete evidence, and no unbounded retries or silent evidence deletion occur.
- [ ] 7.3 Implement default-off opt-in image sampling with a bounded encoding worker, frame-linked metadata and rate/byte limits; verify disabled sampling writes no images, enabled samples remain linked to their source frame, and reaching either limit stops new samples without stopping recognition.
- [ ] 7.4 Implement interrupted-session and partial-image recovery; verify simulated process termination preserves committed events, marks the trip interrupted, cleans up incomplete image writes and never resumes its restriction state.
- [ ] 7.5 Document evidence limits, gap semantics and the unreviewed status of samples; verify a sample trip shows the source event/model/config for each transition and clearly distinguishes full replay from a log with missing intervals.

## 8. Evidence review, export and deletion

- [ ] 8.1 Add completed/interrupted-trip review with event provenance and completeness status; verify review is inaccessible during active recognition and correctly distinguishes incomplete evidence from a complete trip.
- [ ] 8.2 Implement user-selected export of a versioned manifest, ordered JSONL events and explicitly selected images; verify exports with and without images preserve config/provenance, contain no broken included-image references and initiate no automatic upload.
- [ ] 8.3 Implement local trip deletion across database rows and image files with retryable cleanup; verify deletion removes local evidence after retry and communicates that previously exported copies remain outside local control.
- [ ] 8.4 Document the export format and annotation handoff; verify an exported complete trip replays to its recorded states, gaps are detected, partial exports are not advertised as complete, and unreviewed images are not automatically imported as training ground truth.

## 9. Calibration and end-to-end acceptance

- [ ] 9.1 Calibrate confirmation/expiry, detection and evidence settings on validation inputs and freeze the model/config and acceptance sheet before final testing; verify the sheet contains concrete per-class thresholds, minimum sample counts, operating envelope, false-transitions/hour, confirmation delay, state agreement, unknown/stale time and reference-device latency/FPS limits with versioned provenance.
- [ ] 9.2 Run held-out scene evaluation for the frozen bundle; deliver per-class results and exported-model parity evidence, verifying that missing class coverage or any failed declared threshold fails full Stage 1 acceptance rather than being hidden by aggregate metrics.
- [ ] 9.3 Run the 20-minute sustained integrated session on the reference phone; deliver a report separating warm-up, steady-state and thermal behavior and verify latency/FPS gates, offline operation, bounded work/storage, active backend and lifecycle recovery against the frozen acceptance sheet.
- [ ] 9.4 Evaluate recorded annotated drives and a real-trip trial with the integrated app; deliver supported-interval state metrics, false changes, confirmation delays and separate adverse-condition results, verifying each claimed outcome is traceable to complete evidence and its frozen model/config.
- [ ] 9.5 Assemble the Stage 1 acceptance report with links to the reports from 9.2–9.4 and any remaining failures; verify every capability's acceptance scenarios have evidence and update project stage/context only when the declared gates pass. Device/data access limitations remain explicit unfinished tasks, not substituted emulator success.
