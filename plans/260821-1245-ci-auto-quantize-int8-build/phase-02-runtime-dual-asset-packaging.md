# Phase 02: Dual-Asset Packaging & Runtime Resolution Verification

Status: ✅ Completed
Dependencies: Phase 01

## Objective
Verify that when both `backbone` (FP32) and `backbone_int8` (INT8) assets coexist in the application package, `OnnxSessionManager` and `VieNeuTTSManager` correctly extract, load, and detect the quantized INT8 models when INT8 precision is selected, and extract/load FP32 models when FP32 is selected, with zero regression or asset extraction collisions.

## Requirements
### Functional
- [x] `ensureAssetsExtracted()` extracts `vieneu/backbone_int8` when `precision == "INT8"` and INT8 assets exist.
- [x] `ensureAssetsExtracted()` extracts `vieneu/backbone` when `precision == "FP32"` even if INT8 assets exist.
- [x] `getBackboneDir()` returns `backbone_int8` directory when INT8 is selected and present.
- [x] `getBackboneDir()` returns `backbone` directory when FP32 is selected even if `backbone_int8` exists.
- [x] Quantization detection (`detectQuantizationType`) properly identifies `QuantizationType.INT8` on the extracted INT8 model file.
- [x] Dynamic switching between INT8 and FP32 operates without path conflicts or cached file corruption.

### Non-Functional
- [x] Asset extraction efficiency: avoid re-copying existing, intact model assets on repeated application launches.
- [x] Full backward compatibility: graceful fallback to FP32 if INT8 assets are absent.

## Implementation Steps
1. [x] Review and ensure `OnnxSessionManager.kt` asset extraction and copy logic handles dual asset directories cleanly without collisions.
2. [x] Validate that ancillary files (`config.json`, `tokenizer.json`, `vieneu_v3_heads.npz`) in both directories are preserved and referenced properly.
3. [x] Create test verifying the complete dual-precision asset resolution pipeline.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt` — Ensure clean asset isolation if needed.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/DualAssetExtractionAndDetectionTest.kt` — [NEW] Single test file verifying dual-asset extraction, selection, and detection.

## Test Criteria (Single Test File)
**File:** `DualAssetExtractionAndDetectionTest.kt`
- [x] When both `backbone` and `backbone_int8` assets are present, requesting INT8 extracts and selects `backbone_int8`.
- [x] When both `backbone` and `backbone_int8` assets are present, requesting FP32 extracts and selects `backbone`.
- [x] `getBackboneDir` resolves to `backbone_int8` for INT8 request and `backbone` for FP32 request in dual-asset environment.
- [x] INT8 model file path passes quantization detection as `QuantizationType.INT8`.
- [x] Switching requested precision from INT8 to FP32 correctly points to the corresponding model directory without state pollution.

---
Previous Phase: [phase-01-ci-workflow-quantization.md](./phase-01-ci-workflow-quantization.md)
