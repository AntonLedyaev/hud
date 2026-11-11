# Trained model export and Pixel parity

The selected provisional YOLO11n 640 checkpoint is packaged locally in
`ml/runs/trained-bundle-v2/`. It remains a low-quality experimental detector;
passing export parity does not establish recognition quality or final acceptance.

The bundle contains `model.tflite`, strict v1 manifest, generated canonical labels
inside the manifest, preprocessing profile and source parity evidence. It records
checkpoint/dataset/config/model hashes and tool versions. The profile specifies
RGB888, pixel-center nearest sampling, ties-to-even resize dimensions and a
manifest checksum; the manifest declares padding, normalization, tensor layout,
coordinate units, class-score decoding and consumer-owned NMS.

## Checks performed

- PyTorch to ONNX and FP32 LiteRT comparison on fixed GTSDB validation scenes.
- Pixel 6a / Android 16: byte-derived RGB preprocessing matches Python at float
  tolerance 1e-6; tensor parity uses atol 0.01 plus rtol 0.0001.
- Decoded classes/counts agree; matched box IoU exceeds 0.99 and score error is
  below 0.0001. The corpus must produce nonempty detections to avoid a vacuous test.
- Corrupt model bytes and missing manifest fail loading; a subsequent valid load
  succeeds. Labels and declared shapes/contracts are checked before inference;
  actual tensor dtype/shape is checked against the runtime after loading.
- JVM tests cover geometry, rotation, RGB order, normalization, padding, preview
  crop mapping, class-aware NMS and rejected quantized/unsupported contracts.

Measured CPU samples from one instrumented run (not a sustained benchmark):

| Scene | Maximum tensor error | Inference ms | Decoded signs |
|---|---:|---:|---:|
| 00605 | 0.000824 | 436.6 | 1 |
| 00611 | 0.000900 | 334.3 | 0 |
| 00613 | 0.000549 | 330.8 | 1 |

The original bundle-v1 corpus used Pillow nearest-resize edge handling that
differed from Kotlin at a few pixels. The Android preprocessor test caught it;
bundle-v1 is excluded from application staging. Bundle-v2 uses the same explicit
pixel-center formula in both implementations.

## GPU outcome

The standalone GPU delegate probe on this Pixel failed model initialization with
an unsupported-operations error. This does not imply that the phone lacks a GPU;
this graph/runtime/delegate combination did not run successfully. GPU is not
promoted to the app. Requests for GPU return the validated CPU backend with an
explicit fallback reason. GPU libraries are test-only dependencies.

The app bundles the CPU runtime/model for offline loading. Sustained latency,
thermal behavior and real-trip performance remain unmeasured. No 20-minute test
or calibrated FPS guarantee is claimed by these three fixture timings.
