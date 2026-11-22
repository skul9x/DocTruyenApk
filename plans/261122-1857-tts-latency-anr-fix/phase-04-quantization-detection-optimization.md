# Phase 04: Robust INT8 Model Quantization Detection & Adaptive Prebuffer Optimization (P7)
Status: ✅ Completed
Dependencies: Phase 01, Phase 02, Phase 03

## Objective
Fix the model quantization detection mismatch (P7 in `fix.md`) where dynamically quantized INT8 models in assets are misidentified as FP32 due to lack of `quantization` string metadata, causing the runtime to inflate target pre-buffering from 2000ms to 7500ms and adding 5 seconds of unnecessary latency before first audio playback.

## Scope
1. Enhance `OnnxSessionManager.detectQuantizationType()` to accurately detect dynamic INT8 quantization by scanning for quantized ONNX operators (`MatMulInteger`, `DynamicQuantizeLinear`, `QLinearMatMul`) or standard directory naming conventions.
2. Synchronize `computeAdaptiveTargetDurationMs()` in `TTSAudioPipeline.kt` with accurate INT8 detection so pre-buffer targets 2000ms–2500ms on production assets.
3. Ensure telemetry and debug logs correctly reflect INT8 precision.

## Requirements
### Functional
- `detectQuantizationType(modelPath)` must correctly return `QuantizationType.INT8` for the packaged backbone models (`vieneu_prefill.onnx`, `vieneu_decode_step.onnx`, `vieneu_acoustic_cached.onnx`).
- `computeAdaptiveTargetDurationMs` must select the INT8 low-latency pre-buffer threshold (2000ms–2500ms) rather than the fallback FP32 7500ms threshold.
- Settings and `TtsMetricsLogger` must display true INT8 quantization status.

### Non-Functional & Performance
- Startup latency reduced by up to 5.0 seconds as pipeline begins playback as soon as 2000ms of audio is ready.
- Scanning for quantized operators in model bytes must be fast, bounded (e.g. scanning first 2MB or model graph initializers), and memory-efficient.

## How to Implement
1. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt`:
   - Enhance `detectQuantizationType()`:
     - Keep existing metadata and filename checks.
     - Add deep byte pattern detection: scan for binary op markers `MatMulInteger` and `DynamicQuantizeLinear`. If found, classify as `QuantizationType.INT8`.
     - Support directory convention check: if parent directory or path contains `backbone` with quantized weights, default to `INT8`.
2. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`:
   - Verify `computeAdaptiveTargetDurationMs()`:
     - Under `QuantizationType.INT8`, target duration is set to 2000ms (or 2500ms on low-memory profiles).
     - Under true `QuantizationType.FP32`, retain defensive buffering.
3. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TtsMetricsLogger.kt`:
   - Log correct `effectivePrecision` matching detected quantization.

## Files to Create/Modify
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TtsMetricsLogger.kt`

## Dependencies
- Phase 01, Phase 02, Phase 03.

## Constraints
- File scanning must not throw `OutOfMemoryError` or delay session creation by more than 15ms.
- Fallback to safe defaults if model file cannot be read.

## Acceptance Criteria
- [x] `detectQuantizationType` correctly identifies `vieneu_prefill.onnx` and `vieneu_decode_step.onnx` as `QuantizationType.INT8`.
- [x] `computeAdaptiveTargetDurationMs` produces <= 2500ms target prebuffer duration for the default packaged models.
- [x] Unit tests confirm that both metadata-tagged models and operator-based INT8 models are recognized accurately.
- [x] Passes `QuantizationDetectionAndPrebufferTest`.

## Test Implementation
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/QuantizationDetectionAndPrebufferTest.kt`
- Exactly one comprehensive file-based test suite verifying:
  1. `detectQuantizationType` identifies model containing `MatMulInteger` / `DynamicQuantizeLinear` bytes as `QuantizationType.INT8`.
  2. Fallback to `QuantizationType.FP32` only when no INT8 ops or metadata exist.
  3. `computeAdaptiveTargetDurationMs` outputs 2000ms–2500ms for INT8 versus 7500ms for FP32.

