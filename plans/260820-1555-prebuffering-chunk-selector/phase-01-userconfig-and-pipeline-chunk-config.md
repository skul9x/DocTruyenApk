# Phase 01: UserConfig & Audio Pipeline Pre-buffering Config
Status: ⬜ Pending
Dependencies: None

## Objective
Implement storage and retrieval of user-selected pre-buffering chunk count in `UserConfig.kt`, and connect this configuration to `TTSAudioPipeline.kt` and `VieNeuTTSManager.kt` so that initial pre-buffering thresholds dynamically adapt to 1, 2, or 3 chunks with proper duration requirements.

## Requirements
### Functional
- [ ] Add `KEY_TTS_PREBUFFER_CHUNKS = "tts_prebuffer_chunks"` and default value `DEFAULT_PREBUFFER_CHUNKS = 2` (values: 1, 2, 3) in `UserConfig.kt`.
- [ ] Implement `UserConfig.getPrebufferChunks(context): Int` and `UserConfig.setPrebufferChunks(context, chunks: Int)`.
- [ ] Update `TTSAudioPipeline.kt` to allow setting or reading `targetPreBufferChunks: Int`:
  - **1 Chunk**: `minPreBufferChunks = 1`, `targetDurationMs = 2000L` (Fast start: starts if 1 chunk ready and duration >= 1500ms).
  - **2 Chunks**: `minPreBufferChunks = 2`, `targetDurationMs = 4500L` (Balanced start: starts if 2 chunks ready and duration >= 3500ms).
  - **3 Chunks**: `minPreBufferChunks = 3`, `targetDurationMs = 8000L` (Max stability: starts when 3 chunks or 8000ms duration ready).
- [ ] Connect `VieNeuTTSManager.kt` to pass the configured `prebufferChunks` from `UserConfig` or allow dynamic updates.

### Non-Functional
- [ ] Backward compatibility: Defaulting to 2 Chunks if no preference is stored.
- [ ] Clean thread safety and zero overhead during active playback.

## Implementation Steps
1. [ ] Update `UserConfig.kt` with `KEY_TTS_PREBUFFER_CHUNKS`, `DEFAULT_PREBUFFER_CHUNKS = 2`, `getPrebufferChunks(context)` and `setPrebufferChunks(context, chunks)`.
2. [ ] Update `TTSAudioPipeline.kt` pre-buffering logic to incorporate `targetPreBufferChunks` and adapt `computeAdaptiveTargetDurationMs`.
3. [ ] Update `VieNeuTTSManager.kt` to load `UserConfig.getPrebufferChunks(context)` upon initialization and support updating on the fly.
4. [ ] Create unit test `PrebufferChunkConfigTest.kt` to verify:
   - `UserConfig` persistence and fallback clamping for invalid values (< 1 or > 3).
   - `TTSAudioPipeline` duration and chunk threshold calculation for 1, 2, and 3 chunk settings.
5. [ ] Run the single unit test `PrebufferChunkConfigTest.kt` to verify all criteria.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/utils/UserConfig.kt` - Prebuffer chunk preference keys & accessors
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` - Pre-buffering chunk parameterization
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Wiring configuration to pipeline
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PrebufferChunkConfigTest.kt` - [NEW] Verification test

## Test Criteria
- [ ] Test that `UserConfig` persists 1, 2, and 3 chunks correctly and clamps invalid inputs.
- [ ] Test that `TTSAudioPipeline` correctly calculates pre-buffering thresholds for 1 chunk (~2000ms), 2 chunks (~4500ms), and 3 chunks (~8000ms).

---
Next Phase: [Phase 02: Settings UI Chunk Selector](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1555-prebuffering-chunk-selector/phase-02-settings-ui-chunk-selector.md)
