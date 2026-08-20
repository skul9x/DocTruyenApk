# Phase 02: Pre-buffering 1, 2, 3 Chunks Pipeline Configuration
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Support user-configured pre-buffering chunk count in `UserConfig.kt` and parameterize `TTSAudioPipeline.kt` and `VieNeuTTSManager.kt` so that the initial pre-buffering duration dynamically reflects the user's choice:
- **1 Chunk**: Ultra fast startup (target duration ~2.0s, TTFT ~1.8s).
- **2 Chunks (Default)**: Balanced startup (target duration ~4.5s, TTFT ~3.5s, no underruns).
- **3 Chunks**: Deep buffer / Max stability (target duration ~8.0s, TTFT ~8.0s).

## Requirements
### Functional
- [x] Add `KEY_TTS_PREBUFFER_CHUNKS = "tts_prebuffer_chunks"` in `UserConfig.kt` with default value `2` (valid options: 1, 2, 3).
- [x] Implement `UserConfig.getPrebufferChunks(context): Int` and `UserConfig.setPrebufferChunks(context, chunks: Int)`.
- [x] Update `TTSAudioPipeline.kt` pre-buffering threshold evaluation:
  - Take `targetPreBufferChunks: Int` into account.
  - Dynamically calculate target duration:
    - 1 Chunk: `targetDurationMs = 2000L`, minimum single chunk duration = `1500L`.
    - 2 Chunks: `targetDurationMs = 4500L`, minimum duration = `3500L`.
    - 3 Chunks: `targetDurationMs = 8000L`, minimum duration = `7000L`.
- [x] Connect `VieNeuTTSManager.kt` and `ReadingService.kt` to update pipeline target chunks when setting changes.

### Non-Functional
- [x] Backward compatibility with existing user configs.
- [x] Zero audio stutter when chunks arrive as expected.

## Implementation Steps
1. [x] Add `KEY_TTS_PREBUFFER_CHUNKS`, `DEFAULT_PREBUFFER_CHUNKS = 2`, `getPrebufferChunks(context)` and `setPrebufferChunks(context, count)` to `UserConfig.kt`.
2. [x] Update `TTSAudioPipeline.kt` to accept `preBufferChunkCount` and adjust `computeAdaptiveTargetDurationMs` and playback starting condition.
3. [x] Wire `VieNeuTTSManager.kt` to load `getPrebufferChunks(context)` and support dynamic adjustment.
4. [x] Create unit test `PrebufferChunkConfigTest.kt` to verify:
   - `UserConfig` saves and retrieves 1, 2, and 3 chunks and clamps invalid inputs.
   - `TTSAudioPipeline` calculates correct adaptive duration targets for 1, 2, and 3 chunks.
5. [x] Run the single unit test `PrebufferChunkConfigTest.kt` for verification.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/utils/UserConfig.kt` - Prebuffer chunk preference
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` - Pipeline prebuffer chunk customization
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Forward config to pipeline
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PrebufferChunkConfigTest.kt` - [NEW] Verification test

## Test Criteria
- [x] Test `UserConfig` prebuffer chunk persistence and validation.
- [x] Test `TTSAudioPipeline` duration target and chunk threshold calculation for 1, 2, and 3 chunks.

---
Next Phase: [Phase 03: Settings UI Chunk Selector](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1557-fix-tts-reentrancy-and-chunk-selector/phase-03-settings-ui-chunk-selector.md)
