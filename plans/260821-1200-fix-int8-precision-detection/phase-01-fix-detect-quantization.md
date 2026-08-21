# Phase 01: Fix `detectQuantizationType()` Path Checking
Status: ✅ Completed
Dependencies: None

## Objective
Fix `OnnxSessionManager.detectQuantizationType()` so it checks the full model path (including parent directory name like `backbone_int8/`) instead of only the filename.

## Requirements
### Functional
- [x] When model path contains `backbone_int8/` directory, detect as `QuantizationType.INT8`
- [x] When model filename contains `int8`, detect as `QuantizationType.INT8` (existing behavior preserved)
- [x] When model filename contains `fp16`, detect as `QuantizationType.FP16` (existing behavior preserved)
- [x] When neither path nor filename contains quantization hints, default to `QuantizationType.FP32`
- [x] ONNX metadata byte scan (primary detection) remains unchanged

### Non-Functional
- [x] No performance regression — only string operations on the path, no extra I/O

## Implementation Steps
1. [x] In `OnnxSessionManager.kt` line 85-90, change `File(modelPath).name.lowercase()` fallback to also check `modelPath.lowercase()` for directory-based quantization hints
2. [x] Ensure the check order: ONNX metadata bytes (primary) → full path check (secondary) → filename check (tertiary) → default FP32

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt` — Fix fallback detection logic (lines 85-90)
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/DetectQuantizationPathFixTest.kt` — [NEW] Comprehensive test

## Test Criteria (Single Test File)
**File:** `DetectQuantizationPathFixTest.kt`
- [x] `detectQuantizationType("/data/vieneu/backbone_int8/vieneu_prefill.onnx")` returns `INT8`
- [x] `detectQuantizationType("/data/vieneu/backbone/vieneu_prefill_int8.onnx")` returns `INT8`
- [x] `detectQuantizationType("/data/vieneu/backbone/vieneu_prefill.onnx")` returns `FP32`
- [x] `detectQuantizationType("/data/vieneu/backbone_fp16/vieneu_prefill.onnx")` returns `FP16`
- [x] `detectQuantizationType("/data/vieneu/backbone/vieneu_prefill_fp16.onnx")` returns `FP16`

## Notes
- The ONNX metadata scan (lines 67-83) is the primary detection method and should NOT be changed.
- This fix only affects the **fallback** path when metadata scan finds nothing (which is the case for current FP32 models that lack metadata).
- The files on disk are always named `vieneu_prefill.onnx` regardless of quantization — only the parent directory differs (`backbone/` vs `backbone_int8/`).

---
Next Phase: [phase-02-fix-cold-start-precision.md](./phase-02-fix-cold-start-precision.md)
