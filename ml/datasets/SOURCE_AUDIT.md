# Stage 1 source audit

## Sources and access

- **GTSDB:** author-maintained [distribution page](https://github.com/houbensebastian/GermanTrafficSignBenchmarks)
  links the [ERDA archive](https://sid.erda.dk/public/archives/ff17dc924eba88d5d01a807357d6614c/published-archive.html).
  The complete archive was downloaded, its ZIP CRC checked, and 900 root scenes
  extracted. Its ReadMe section 1 permits use for any purpose and requests citation
  of Houben et al., *Detection of Traffic Signs in Real-World Images*, IJCNN 2013.
  This statement is recorded as the dataset's source permission, not replaced by
  the repository license. Original publication roles are 600 train / 300 evaluation.
- **GTSRB:** the same author page describes a classification dataset organized by
  physical-sign tracks. All twelve canonical GTSRB IDs are within its taxonomy,
  but instance coverage was not downloaded/measured in this audit. Its crops are
  excluded from full-scene detection training. Classification coverage does not
  establish detector coverage.
- **Mapillary MTSD:** the [official dataset page](https://www.mapillary.com/dataset/trafficsign)
  currently requires login. The [dataset paper](https://arxiv.org/abs/1909.04422)
  describes academic-research access. Current access terms, annotation version,
  exact class mappings and per-class counts are unverified without the downloaded
  package. No guessed mappings or blanket license claims are used.
- **Own drives:** no annotated drive dataset is present. All twelve classes have
  unmeasured coverage; no negative or adverse-condition coverage is claimed.

## Measured GTSDB class coverage

The generated companion `gtsdb-coverage.json` is a historical audit report, not a
second maintained label source. Recreate it using `gtsdb.py` and the complete
archive's annotations. Canonical IDs/names are always derived from the YAML.

All twelve classes have at least one instance across the full 900-image source.
The rare cancellation classes have only eight and seven instances respectively;
global coverage does not guarantee coverage in train/validation/test separately.
Training must report absent split coverage explicitly. It must not silently drop
those classes or claim full Stage 1 acceptance from aggregate metrics.

The separately downloadable annotation file ends at 00599 and contains 852 boxes
in 506 annotated scenes. The full archive's annotation file contains 1,213 boxes
in 741 annotated scenes and covers the full 00000–00899 image range. The remaining
159 images have no annotated signs in that complete source. Unsupported sign
classes are retained in the exclusion report; they do not become target labels.

## Approved provisional split; independence unverified

The full archive contains images, class crops, gt.txt and ReadMe.txt; it does not
provide drive/sequence IDs. Its official 600/300 assignment is known, but that alone
does not prove the project's stronger requirement that related scenes stay in one
split. A random frame split or one-group-per-filename assumption would evade that
requirement. The converter therefore requires an explicit reviewed group/split
manifest by default. The developer subsequently approved the exception below.

The approved baseline uses 00000–00599 for train and 00600–00899 for provisional
validation, records unknown sequence membership and creates no final test split.
This permits tasks 4.3–4.4 to proceed without claiming sequence independence.
Final independent-drive evaluation and adverse-condition coverage remain gates.
