# Phase 01: CI Workflow Quantization Step Integration

Status: ✅ Completed
Dependencies: None

## Objective
Integrate Python 3.10 setup, required ONNX quantization dependencies, and automated execution of `tools/quantize_models.py` into `.github/workflows/android.yml` immediately before building the Android release APK.

## Requirements
### Functional
- [x] Add `actions/setup-python@v5` with Python 3.10 and `pip` caching in `.github/workflows/android.yml`.
- [x] Install dependencies from `tools/requirements.txt` (`onnx`, `onnxruntime`, `onnxconverter-common`, `numpy`).
- [x] Run `tools/quantize_models.py` to quantize `app/src/main/assets/vieneu/backbone` into `app/src/main/assets/vieneu/backbone_int8`.
- [x] Add verification check in CI ensuring quantized ONNX models and metadata exist before gradle assembly starts.
- [x] Keep quantization step optional or resilient so local builds without python continue working normally.

### Non-Functional
- [x] Build time efficiency: leverage pip cache to minimize CI runner overhead.
- [x] Clean directory hygiene: ensure quantized models are placed strictly inside Android assets source directory before packaging.

## Implementation Steps
1. [x] Update `.github/workflows/android.yml` to include a `Set up Python` step with pip caching.
2. [x] Add `Install Quantization Dependencies` step (`pip install -r tools/requirements.txt`).
3. [x] Add `Quantize VieNeu ONNX Models to Dynamic INT8` step executing `python tools/quantize_models.py --input-dir app/src/main/assets/vieneu/backbone --output-dir app/src/main/assets/vieneu/backbone_int8 --format int8`.
4. [x] Add verification step listing generated INT8 assets and logging sizes.

## Files to Create/Modify
- `.github/workflows/android.yml` — Add Python setup and quantization execution steps.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/WorkflowQuantizationConfigTest.kt` — [NEW] Single test file verifying workflow configuration rules and expected asset target structure.

## Test Criteria (Single Test File)
**File:** `WorkflowQuantizationConfigTest.kt`
- [x] Verifies `.github/workflows/android.yml` contains Python setup step before Gradle assemble step.
- [x] Verifies `.github/workflows/android.yml` references `tools/requirements.txt` and `tools/quantize_models.py`.
- [x] Verifies workflow specifies correct output target directory `app/src/main/assets/vieneu/backbone_int8`.
- [x] Verifies workflow specifies `--format int8` target.

---
Next Phase: [phase-02-runtime-dual-asset-packaging.md](./phase-02-runtime-dual-asset-packaging.md)
