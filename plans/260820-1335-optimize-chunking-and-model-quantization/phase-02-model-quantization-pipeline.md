# Phase 02: Automated ONNX Model Quantization Pipeline (Dynamic INT8 / Optional FP16)

Status: ✅ Completed
Dependencies: Phase 01

## Objective
Implement a production-grade, automated Python tooling pipeline (`tools/quantize_models.py`) capable of quantizing VieNeu ONNX models to `Dynamic INT8` (primary, for CPU/XNNPACK path) and optionally converting to `FP16` (secondary, only effective with NNAPI/GPU execution providers — **NOT for CPU EP**), correctly handling ONNX external data (`.data` files) without corruption, and safeguarding audio fidelity by selectively quantizing heavy matrix operations.

> ⚠️ **CRITICAL NOTE on FP16:**
> ONNX Runtime CPU EP (including XNNPACK) does NOT have native FP16 kernels on Android.
> Loading an FP16 model on CPU EP causes automatic Cast node insertion (FP16→FP32),
> which **degrades performance** compared to native FP32. FP16 is only beneficial when
> using NNAPI (with `NNAPI_FLAG_USE_FP16`) or GPU execution providers with Tensor Core support.
> For CPU-only inference, **Dynamic INT8 is the correct optimization path**.

## Requirements
### Functional
- [x] Create `tools/quantize_models.py` with CLI arguments: `--input-dir`, `--output-dir`, `--format` (`int8`, `fp16`, `both`), and `--check-audio-safety`.
- [x] **Primary — Dynamic INT8 quantization** via `onnxruntime.quantization.quantize_dynamic` with:
  - `op_types_to_quantize=['MatMul', 'Gemm']` — restricts quantization to heavy matrix ops only, preventing acoustic distortion in 1D convolutions and neural codec decoders.
  - `weight_type=QuantType.QInt8`
  - `use_external_data_format=True` — required for models with linked `.data` files.
  - **Note:** `Gemm` nodes may be internally rewritten to `MatMul` + `Add` during quantization. Always use `op_types_to_quantize` (operator type filter), NOT `nodes_to_quantize` (node name filter), to avoid node name mismatch failures.
- [x] **Secondary — Optional FP16 conversion** via `onnxconverter_common.float16.convert_float_to_float16()` with:
  - `disable_shape_infer=True` — required for large models with external data.
  - `keep_io_types=True` — keeps model I/O tensors in FP32 for compatibility.
  - `op_block_list` — optionally block sensitive operators from FP16 conversion.
  - ⚠️ FP16 output should be clearly labeled as NNAPI/GPU-only in the CLI output.
- [x] Correctly handle linked external data (`vieneu_backbone_shared.data`, `moss_audio_tokenizer_decode_shared.data`) ensuring valid protobuf references and file re-sharding.
- [x] Generate model size delta and compression ratio reports after conversion.
- [x] Print clear warnings when FP16 format is selected: "FP16 models require NNAPI or GPU EP for performance benefit. CPU EP will auto-cast to FP32."

### Non-Functional
- [x] Clean Python code compatible with Python 3.10 - 3.12.
- [x] Standalone executable without requiring Android SDK or Java dependencies.

## Implementation Steps
1. Create `tools/requirements.txt` listing `onnx`, `onnxruntime`, `onnxconverter-common`, `numpy`.
2. Create `tools/quantize_models.py`:
   - Implement `quantize_model_int8_dynamic()` with `op_types_to_quantize=['MatMul', 'Gemm']` operator filtering.
   - Implement `convert_model_fp16()` with external data handling using `onnxconverter_common.float16` (NOT `onnxruntime.transformers.float16` — that module wraps the same logic but is intended for transformer-specific models, not general TTS models).
   - Implement batch processing for `app/src/main/assets/vieneu/` models (`backbone`, `codec`, `denoiser`).
   - Add ONNX `metadata_props` injection: write `{"quantization": "int8"}` or `{"quantization": "fp16"}` into each quantized model's metadata for runtime detection on Android.
3. Create file-based verification test: `tools/test_quantization_pipeline.py`.

## Files to Create/Modify
- `tools/requirements.txt` - Python dependencies for model quantization.
- `tools/quantize_models.py` - Core conversion and quantization script for Dynamic INT8 (primary) and optional FP16 models.
- `tools/test_quantization_pipeline.py` - Standalone test verifying quantization routines, external data resolution, and operator safety filters.

## Test Criteria
- Exactly one test: `tools/test_quantization_pipeline.py`
- Verifies that a sample ONNX model with external data transforms into valid INT8 output (and optionally FP16).
- Verifies that input/output tensor shapes and names are preserved.
- Verifies that prohibited operators (`Conv`, `ConvTranspose`) remain in original precision for acoustic preservation.
- Verifies that ONNX `metadata_props` contain correct quantization type after conversion.

---
Next Phase: [Phase 03: Android Engine Quantization Integration & Adaptive Buffering](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1335-optimize-chunking-and-model-quantization/phase-03-quantized-engine-and-adaptive-buffering.md)
