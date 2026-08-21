# Phase 03: Add Explicit Fallback Logging for Missing INT8 Assets
Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Add clear, actionable log messages when INT8 precision is requested but INT8 model assets are not available, so the silent fallback to FP32 is no longer a debugging mystery.

## Requirements
### Functional
- [x] Log a warning in `ensureAssetsExtracted()` when INT8 is requested but `backbone_int8/` assets not found
- [x] Log an info message when INT8 assets ARE found and selected
- [x] Log a warning in `create()` when detected quantization doesn't match the requested precision
- [x] All new log messages use the existing `TAG` constant and standard `Log.w()` / `Log.i()`

### Non-Functional
- [x] No behavioral changes — only diagnostic log output added
- [x] Warning messages include actionable hint (e.g., "INT8 model assets may be missing from APK")

## Implementation Steps
1. [x] In `OnnxSessionManager.kt` `ensureAssetsExtracted()` (line 272-277), add `Log.w()` when falling back from INT8 to FP32 backbone
2. [x] In `OnnxSessionManager.kt` `ensureAssetsExtracted()`, add `Log.i()` when INT8 assets are found
3. [x] In `OnnxSessionManager.kt` `create()` (after line 189), add `Log.w()` when `detectedQuant != requested precision`

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt` — Add fallback logging in `ensureAssetsExtracted()` and `create()`
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AssetFallbackLoggingFixTest.kt` — [NEW] Comprehensive test

## Test Criteria (Single Test File)
**File:** `AssetFallbackLoggingFixTest.kt`
- [x] When INT8 requested and `backbone_int8` assets are missing, `ensureAssetsExtracted` returns base dir pointing to `backbone` (fallback verified)
- [x] When FP32 requested, `ensureAssetsExtracted` returns base dir pointing to `backbone` (no fallback warning expected)
- [x] `getBackboneDir` with INT8 and missing int8 dir returns `backbone` directory
- [x] `getBackboneDir` with INT8 and existing int8 dir returns `backbone_int8` directory
- [x] Precision mismatch between requested and detected is identifiable from create() flow

## Notes
- The log messages use emoji prefixes (⚠️) consistent with existing codebase logging style
- These logs will be visible in logcat and in the app's `TtsMetricsLogger` diagnostic output
- This phase is purely additive — no existing behavior changes, only new log statements

---
Previous Phase: [phase-02-fix-cold-start-precision.md](./phase-02-fix-cold-start-precision.md)
