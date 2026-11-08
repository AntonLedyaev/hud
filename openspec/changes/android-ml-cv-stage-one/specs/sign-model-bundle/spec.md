## Purpose

Provide reproducible training evidence and a self-describing model bundle that Android can validate and execute consistently.

## ADDED Requirements

### Requirement: Canonical label compatibility
All training and runtime class mappings SHALL be derived from `shared/labels.yaml`. A bundle MUST identify its labels version, content hash and ordered output mapping. Stage 1 SHALL support the twelve v1 classes without interpreting unsupported classes as supported signs.

#### Scenario: Incompatible labels
- **WHEN** a bundle has reordered, missing or unknown class IDs or a mismatched labels hash
- **THEN** validation rejects it before inference and reports the mismatch

#### Scenario: Missing training coverage
- **WHEN** a target class lacks training or held-out evaluation examples
- **THEN** the report marks it uncovered and the bundle cannot pass full Stage 1 acceptance

### Requirement: Reproducible data preparation
Dataset preparation SHALL record source, access/license reference, dataset version, class mapping, exclusions, annotation format and split membership. Related frames from a drive or sequence MUST remain in one split; test data MUST NOT select thresholds or tune training.

#### Scenario: Unmapped or classification-only data
- **WHEN** a source label has no canonical mapping or an image has no detection boxes
- **THEN** preparation reports its exclusion from detection training rather than inventing a class or box

#### Scenario: Sequence leakage
- **WHEN** related frames occur in both training and test splits
- **THEN** dataset validation fails and identifies the conflicting sequence

### Requirement: Complete inference contract
The bundle SHALL include model checksum/version, provenance, input shape/type, channel order, normalization, resize/padding and rotation policy, quantization parameters, output layout, box coordinates, score decoding, NMS ownership and thresholds. Unsupported contracts MUST be rejected explicitly.

#### Scenario: Contract mismatch
- **WHEN** model tensor metadata disagrees with the declared contract
- **THEN** Android reports an incompatible model and produces no detections

### Requirement: Export equivalence
Export validation SHALL compare source-model and Android-runtime detections on a fixed reference corpus using declared numeric tolerances, class agreement and box overlap. The report MUST identify the model, runtime, preprocessing, corpus and tolerances; an unvalidated export MUST NOT be accepted for a road trial.

#### Scenario: Incorrect preprocessing after export
- **WHEN** exported inference exceeds the recorded score or box tolerances
- **THEN** the parity gate fails with reference examples and the bundle remains experimental

### Requirement: Measured recognition coverage
Evaluation SHALL report per-class precision, recall, detection mAP, confusion and sample counts on held-out full scenes, including negative scenes. Daylight printed signs and adverse conditions MUST be reported separately. Thresholds MUST be fixed using validation data before final test evaluation.

#### Scenario: Aggregate result hides a weak class
- **WHEN** aggregate accuracy is high but a target class fails its predeclared acceptance threshold
- **THEN** the report identifies that class and full Stage 1 acceptance fails
