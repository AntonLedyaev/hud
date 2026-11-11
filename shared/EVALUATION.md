# Evaluation protocol v1

Use `reference.v1.schema.json` for held-out scene annotations and expected ordered
state events; use `evaluation-report.v1.schema.json` for results. Both require the
canonical label hash. Validate with `contracts.py --kind reference --validate FILE`
or `--kind evaluation-report`. Reports require every canonical class, even when its
sample count is zero. Zero samples means uncovered, never a successful class gate.
Undefined precision/recall are encoded as zero with their sample counts, not NaN.

1. Group scenes by source drive/sequence before splitting. A sequence must not
   cross training, validation and test. Include empty ground-truth lists for
   negative scenes. Record dataset versions, annotation hashes and exclusions.
2. Use validation only to choose confidence, confirmation and expiry thresholds.
   Freeze the model/config, per-class quality requirements, minimum sample counts
   and physical-device acceptance thresholds before opening final test results.
3. Include source images, transforms and numeric outputs in the external corpus;
   this repository commits only synthetic metadata. Check class agreement, maximum
   absolute score error and box IoU for paired source/export/runtime predictions.
   Tolerances are explicit per reference set, not universal accuracy claims.
4. Report precision, recall, mAP50-95 and sample counts for each class, confusion
   analysis and negative-scene counts. Separate daylight printed signs, LED,
   night and rain; never infer adverse-condition performance from daylight.
5. Compare state against annotated intervals where supported signs explicitly
   establish the reference estimate. Report false transitions per hour,
   p95 confirmation delay, agreement and unknown/stale fractions. Exclude
   unsupported intervals explicitly rather than inventing legal defaults.
6. A missing metric, missing class, insufficient samples, parity failure or
   failed predeclared threshold prevents full acceptance. Synthetic examples
   test serialization only; their zero counts cannot establish model quality.

The checked-in reference is a synthetic negative scene. Add positive synthetic
observations/events using the observation/event fixtures for replay tests.
The checked-in report is an empty-coverage example, not measured performance.

Approved GTSDB exception: official images 00000–00599 form training and
00600–00899 form provisional validation. Sequence provenance is unknown, not
verified independent. These 300 images may guide the initial baseline, but their
metrics cannot satisfy final independent-drive acceptance. No final test split is
created from these images by the provisional converter.
