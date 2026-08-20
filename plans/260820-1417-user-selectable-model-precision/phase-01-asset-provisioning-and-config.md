# Phase 01: Multi-Precision Asset Provisioning & Configuration Persistence

Status: ✅ Completed
Dependencies: None

## Objective
1. Generate and bundle Dynamic INT8 quantized ONNX models into `app/src/main/assets/vieneu/backbone_int8/` using the automated quantization pipeline (`tools/quantize_models.py`), while keeping original FP32 models in `app/src/main/assets/vieneu/backbone/`.
2. Implement precision configuration persistence in `UserConfig.kt` with keys, defaults, and getters/setters (`INT8` vs `FP32`).
3. Update `OnnxSessionManager.kt` asset extraction mechanism (`ensureAssetsExtracted`) to extract the appropriate precision directory (`backbone` or `backbone_int8`) based on user preference.

## Requirements
### Functional
- [x] Quantize VieNeu backbone models (`vieneu_prefill.onnx`, `vieneu_decode_step.onnx`, `vieneu_acoustic_cached.onnx`) into `app/src/main/assets/vieneu/backbone_int8/` with embedded `quantization=int8` metadata.
- [x] Copy ancillary files (`config.json`, `tokenizer.json`, `vieneu_v3_heads.npz`, `vieneu_backbone_shared.data`) into `app/src/main/assets/vieneu/backbone_int8/`.
- [x] Add `KEY_TTS_PRECISION = "tts_precision"` and `DEFAULT_TTS_PRECISION = "INT8"` to `UserConfig.kt`.
- [x] Add `UserConfig.getTtsPrecision(context: Context): String` (returns `"INT8"` or `"FP32"`).
- [x] Add `UserConfig.setTtsPrecision(context: Context, precision: String)` (stores normalized `"INT8"` or `"FP32"`).
- [x] Update `OnnxSessionManager.ensureAssetsExtracted(context: Context, precision: String)` to resolve target path:
  - If `precision == "INT8"` -> extract from asset folder `vieneu/backbone_int8` (with fallback to `vieneu/backbone` if not present).
  - If `precision == "FP32"` -> extract from asset folder `vieneu/backbone`.

### Non-Functional
- [x] Model integrity: Ensure INT8 acoustic safety checks pass without prohibited quantized operators.
- [x] Backward compatibility: If INT8 assets are missing on older builds, gracefully fall back to FP32.
- [x] Thread safety and clean SharedPreferences synchronization.

## Implementation Steps
1. Run `.venv/bin/python tools/quantize_models.py --input-dir app/src/main/assets/vieneu/backbone --output-dir app/src/main/assets/vieneu/backbone_int8 --format int8` to generate quantized models.
2. Update `UserConfig.kt`:
   - Add `KEY_TTS_PRECISION` constant.
   - Add `getTtsPrecision(context)` and `setTtsPrecision(context, precision)` methods.
3. Update `OnnxSessionManager.kt`:
   - Update `ensureAssetsExtracted` to take `precision: String = "INT8"`.
   - Resolve backbone directory dynamically (`backbone_int8` for INT8, `backbone` for FP32).
4. Create test file: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PrecisionAssetAndConfigTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/utils/UserConfig.kt` - Precision preference persistence.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt` - Multi-precision asset extraction.
- `app/src/main/assets/vieneu/backbone_int8/` - Quantized ONNX model assets and configurations.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PrecisionAssetAndConfigTest.kt` - Unit test for asset resolution and config persistence.

## Test Criteria
- Exactly one test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PrecisionAssetAndConfigTest.kt`
- Verifies default precision in `UserConfig` is `"INT8"`.
- Verifies `UserConfig.setTtsPrecision` stores and retrieves `"INT8"` and `"FP32"` correctly.
- Verifies `OnnxSessionManager.detectQuantizationType` correctly identifies `INT8` for `backbone_int8` models and `FP32` for `backbone` models.
- Verifies fallback to `FP32` when invalid precision strings are provided.
