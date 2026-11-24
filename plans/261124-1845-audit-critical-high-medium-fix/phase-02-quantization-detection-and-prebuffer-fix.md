# Phase 02: Robust Quantization Detection & Defensive Prebuffer Margin Restoration (AUD-002)
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Eliminate the critical model quantization detection flaw (AUD-002) where presence of `vieneu_backbone_shared.data` caused all FP32 models to be falsely classified as INT8, restoring proper 7,500ms defensive buffering for FP32 while preserving fast 2,000ms buffering for INT8, resolving audio starvation underruns and fixing 4 failing unit test suites.

## Scope
1. Refactor `OnnxSessionManager.detectQuantizationType()` to remove the invalid `vieneu_backbone_shared.data` heuristic.
2. Establish unambiguous quantization classification:
   - Identify INT8 via explicit folder convention (`backbone_int8`), filename convention (`*_int8.onnx`), model metadata properties, or deep byte-pattern matching of quantized ONNX operators (`MatMulInteger`, `DynamicQuantizeLinear`, `QLinearMatMul`).
   - Identify FP32 when residing in `backbone` without quantized operator patterns.
3. Validate that `TTSAudioPipeline.computeAdaptiveTargetDurationMs()` properly assigns 7,500ms pre-buffering to FP32 models and 2,000ms–2,500ms to INT8 models.

## Requirements
### Functional
- `OnnxSessionManager.createFromFiles(backboneFp32Dir, ..., precision = "FP32")` must instantiate an `OnnxSessionManager` whose `quantizationType` is strictly `QuantizationType.FP32`.
- `OnnxSessionManager.createFromFiles(backboneInt8Dir, ..., precision = "INT8")` must instantiate an `OnnxSessionManager` whose `quantizationType` is strictly `QuantizationType.INT8`.
- In FP32 mode, `TTSAudioPipeline` must maintain a minimum defensive prebuffer target of 7,500ms (7.5s) to account for CPU RTF (~1.8x–2.5x).
- In INT8 mode, `TTSAudioPipeline` must maintain an aggressive prebuffer target of 2,000ms–2,500ms on multi-core devices for low TTFT (<2.5s).

### Non-Functional & Reliability
- Zero audio buffer starvation underruns when executing in FP32 precision.
- Fully idempotent session creation across multiple precision switches.
- 100% passing tests for all precision switching suites (`DynamicPrecisionSwitchingTest`, `QuantizedEngineAndAdaptiveBufferingTest`, `PrecisionAssetAndConfigTest`, `SettingsPrecisionIntegrationTest`).

## How to Implement
1. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt`:
   - Inspect and clean `detectQuantizationType(modelPath: String)`:
     - Remove lines 132-135 (`val sharedData = File(parent, "vieneu_backbone_shared.data"); if (sharedData.exists()) return QuantizationType.INT8`).
     - Refine parent directory convention check (using case-insensitive matching):
       ```kotlin
       val file = File(modelPath)
       val parent = file.parentFile
       if (parent != null && parent.exists()) {
           val parentName = parent.name.lowercase()
           if (parentName.contains("backbone_int8") || parentName.endsWith("_int8")) {
               return QuantizationType.INT8
           }
       }
       ```
     - Preserve the companion model check (`vieneu_prefill.onnx`) without the invalid shared data heuristic.
     - Ensure fallback logic correctly defaults to `QuantizationType.FP32` when scanning unquantized models in `backbone`.
2. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`:
   - Ensure `computeAdaptiveTargetDurationMs()` preserves the 7,500ms safe fallback duration when `quantizationType == QuantizationType.FP32` and switches to 2,000ms–2,500ms only when `quantizationType == QuantizationType.INT8`.

## Files to Create/Modify
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`

## Dependencies
- Phase 01

## Constraints
- Must remain compatible with packaging conventions in both debug and release builds (bundled assets under `assets/vieneu/backbone` and `assets/vieneu/backbone_int8`).
- Must not degrade TTFT for INT8 models.

## Acceptance Criteria
- [x] `OnnxSessionManager.createFromFiles` with `precision = "FP32"` resolves to `QuantizationType.FP32`.
- [x] `OnnxSessionManager.createFromFiles` with `precision = "INT8"` resolves to `QuantizationType.INT8`.
- [x] `DynamicPrecisionSwitchingTest.testOnnxSessionManager_precisionSwitchingAndResolution` passes.
- [x] `QuantizedEngineAndAdaptiveBufferingTest.testAdaptivePrebuffering_durationTargetAndStoryIdPropagation` passes.
- [x] `SettingsPrecisionIntegrationTest.testEndToEnd_precisionSelectionToSynthesis` passes.
- [x] Passes `AccurateQuantizationResolutionTest`.

## Test Implementation
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AccurateQuantizationResolutionTest.kt`
- Exactly one comprehensive file-based test suite verifying:
  1. `detectQuantizationType` returns `FP32` on files within `src/main/assets/vieneu/backbone` despite the presence of `vieneu_backbone_shared.data`.
  2. `detectQuantizationType` returns `INT8` on files within `src/main/assets/vieneu/backbone_int8` or containing int8 operators.
  3. `computeAdaptiveTargetDurationMs` outputs >= 7500ms for FP32 and <= 2500ms for INT8 under identical core counts.
