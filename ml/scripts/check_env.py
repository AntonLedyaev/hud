"""Environment sanity check for ml/.

Run: uv run scripts/check_env.py

Verifies: Python version, key imports, CUDA availability, a real GPU op,
and that shared/labels.yaml is readable and consistent.
Exit code 0 = environment is ready for training.
"""

from __future__ import annotations

import sys
from pathlib import Path

OK = "\033[92m[ ok ]\033[0m"
FAIL = "\033[91m[fail]\033[0m"
WARN = "\033[93m[warn]\033[0m"

REPO_ROOT = Path(__file__).resolve().parents[2]
LABELS = REPO_ROOT / "shared" / "labels.yaml"

failures = 0


def check(label: str, fn):
    global failures
    try:
        detail = fn()
        print(f"{OK} {label}" + (f" — {detail}" if detail else ""))
    except Exception as e:  # noqa: BLE001 — report anything, this is a diagnostic
        failures += 1
        print(f"{FAIL} {label} — {type(e).__name__}: {e}")


def check_python():
    v = sys.version_info
    assert (3, 11) <= (v.major, v.minor) < (3, 13), f"need 3.11–3.12, got {v.major}.{v.minor}"
    return f"{v.major}.{v.minor}.{v.micro}"


def check_torch_cuda():
    import torch

    if not torch.cuda.is_available():
        raise RuntimeError(
            f"torch {torch.__version__} installed but CUDA is not available "
            "(CPU-only build or driver issue — see CUDA note in pyproject.toml)"
        )
    name = torch.cuda.get_device_name(0)
    # Actually touch the GPU: tiny matmul + sync.
    a = torch.randn(512, 512, device="cuda")
    (a @ a).sum().item()
    vram = torch.cuda.get_device_properties(0).total_memory / 1024**3
    return f"torch {torch.__version__}, {name}, {vram:.1f} GiB"


def check_ultralytics():
    import ultralytics

    return f"ultralytics {ultralytics.__version__}"


def check_cv_stack():
    import albumentations as A
    import cv2

    return f"opencv {cv2.__version__}, albumentations {A.__version__}"


def check_onnx():
    import onnx
    import onnxruntime as ort

    return f"onnx {onnx.__version__}, onnxruntime {ort.__version__}"


def check_labels():
    import yaml

    data = yaml.safe_load(LABELS.read_text(encoding="utf-8"))
    classes = data["classes"]
    ids = [c["id"] for c in classes]
    assert ids == list(range(len(ids))), "class ids must be contiguous 0..N-1"
    assert len({c["name"] for c in classes}) == len(classes), "duplicate class names"
    return f"v{data['version']}, {len(classes)} classes ({data['region']})"


def main() -> int:
    print(f"repo root: {REPO_ROOT}\n")
    check("Python version", check_python)
    check("PyTorch + CUDA (GPU op executed)", check_torch_cuda)
    check("Ultralytics", check_ultralytics)
    check("OpenCV + Albumentations", check_cv_stack)
    check("ONNX + onnxruntime", check_onnx)
    check("shared/labels.yaml", check_labels)

    print()
    if failures:
        print(f"{FAIL} {failures} check(s) failed — fix before training.")
        return 1
    print(f"{OK} environment ready.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
