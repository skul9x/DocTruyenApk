# Phase 01: Adaptive Lookahead Pre-buffering in TTSAudioPipeline
Status: ✅ Completed
Dependencies: None

## Objective
Upgrade `TTSAudioPipeline` with an adaptive lookahead pre-buffering strategy that guarantees at least 2 synthesized chunks or a cumulative buffer duration of $\ge 6000\text{ms}$ before releasing PCM frames to `AudioPlayer` for fresh playback sessions (`startIndex == 0`), preventing early buffer starvation without degrading mid-stream resume responsiveness.

## Requirements
### Functional
- [x] Implement adaptive pre-buffer thresholds in `TTSAudioPipeline`:
  - Fresh session (`startIndex == 0`): `minPreBufferChunks = 2`, `minPreBufferDurationMs = 6000L`.
  - Resume / Seek (`startIndex > 0`): `minPreBufferChunks = 1`, `minPreBufferDurationMs = 1500L` (immediate response).
  - Short stories ($\le 2$ chunks total): Automatically release when all chunks are synthesized (`isSynthDone`).
- [x] Add explicit state transition `PipelineState.Buffering` to signal UI when initial chunks are accumulating.
- [x] Maintain silence and pause durations attached to each chunk so total buffered duration calculation accurately reflects physical playback time.

### Non-Functional
- [x] **Starvation Rate:** 0 underruns recorded across synthetic multi-sentence benchmarks.
- [x] **Thread Safety:** Lock-free, concurrent queue polling without UI thread blocking.

## Implementation Steps
1. [x] Update `TTSAudioPipeline.kt` constructor and state definitions:
   - Add `PipelineState.Buffering(val bufferedChunks: Int, val targetChunks: Int)` (or update `Synthesizing`).
   - Refactor `startPipeline(startIndex: Int)` pre-buffering loop condition:
     ```kotlin
     val requiredChunks = if (startIndex == 0 && totalChunks > 2) 2 else 1
     val requiredDurationMs = if (startIndex == 0 && totalChunks > 2) 6000L else 1500L
     val hasEnoughChunks = currentQueueSize >= requiredChunks
     val hasEnoughDuration = bufferedDurationMs >= requiredDurationMs
     ```
2. [x] Refine `TtsMetricsLogger.kt` to record pre-buffering wait time and verify starvation tracking accurately logs underruns if they ever occur.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` - Update lookahead buffering logic and parameters.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TtsMetricsLogger.kt` - Track pre-buffer wait duration telemetry.

## File-Based Test Criteria
Create unit test `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AdaptivePrebufferingTest.kt`:
- [x] `testInitialPlayback_requiresTwoChunksOrSixSeconds`: Mock `VieNeuOnnxEngine` producing 1.36s for Chunk 0 and 3.68s for Chunk 1. Verify `AudioPlayer` is NOT fed until Chunk 1 is available.
- [x] `testResumeMidStory_startsImmediatelyWithSingleChunk`: Verify seeking to Chunk 3 only waits for 1 chunk before playing.
- [x] `testShortStory_releasesImmediatelyWhenSynthDone`: Verify a 2-chunk story does not deadlock waiting for extra chunks.

---
Next Phase: [Phase 02: Background Pre-warming Engine](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-02-background-prewarming-engine.md)
