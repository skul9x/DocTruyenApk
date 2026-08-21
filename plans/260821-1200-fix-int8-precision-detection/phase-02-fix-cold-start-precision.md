# Phase 02: Fix Cold-Start `initManager()` Precision Pass-Through
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Fix `VieNeuTTSManager.initManager()` to read the user's saved precision preference from `UserConfig` and pass it to `VieNeuOnnxEngine.create()` during cold start, instead of using the default parameter.

## Requirements
### Functional
- [x] On app startup, `initManager()` reads precision from `UserConfig.getTtsPrecision(ctx)`
- [x] The saved precision is passed to `VieNeuOnnxEngine.create(ctx, precision = savedPrecision)`
- [x] If no saved preference exists, defaults to `UserConfig.DEFAULT_TTS_PRECISION` ("INT8")
- [x] Existing `reloadEngine()` / `setPrecision()` behavior remains unchanged

### Non-Functional
- [x] No additional I/O — `UserConfig.getTtsPrecision()` reads from in-memory `SharedPreferences`

## Implementation Steps
1. [x] In `VieNeuTTSManager.kt` line 111-113, add `val savedPrecision = UserConfig.getTtsPrecision(ctx)` before engine creation
2. [x] Change `VieNeuOnnxEngine.create(ctx)` to `VieNeuOnnxEngine.create(ctx, precision = savedPrecision)`

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` — Pass saved precision in `initManager()` (lines 111-113)
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/ColdStartPrecisionFixTest.kt` — [NEW] Comprehensive test

## Test Criteria (Single Test File)
**File:** `ColdStartPrecisionFixTest.kt`
- [x] When `UserConfig` has "INT8" saved, verify `VieNeuOnnxEngine.create()` receives `precision = "INT8"`
- [x] When `UserConfig` has "FP32" saved, verify `VieNeuOnnxEngine.create()` receives `precision = "FP32"`
- [x] When `UserConfig` has no saved value, verify default precision is used
- [x] Verify `reloadEngine()` still passes correct precision (regression check)
- [x] Verify the saved precision variable is read from context before engine creation

## Notes
- `UserConfig.getTtsPrecision()` already normalizes the value (returns "INT8" or "FP32")
- `VieNeuOnnxEngine.create()` already accepts `precision` parameter with default `"INT8"` — we just need to explicitly pass the saved value
- The `reloadEngine()` method already correctly reads `UserConfig.getTtsPrecision()` in its default parameter — this fix only affects the first-time `initManager()` path

---
Next Phase: [phase-03-add-fallback-logging.md](./phase-03-add-fallback-logging.md)
