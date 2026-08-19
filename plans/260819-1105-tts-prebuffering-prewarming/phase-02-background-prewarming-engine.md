# Phase 02: Background Pre-warming Engine in VieNeuTTSManager & ReadingService
Status: 🟩 Completed
Dependencies: [Phase 01](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-01-adaptive-lookahead-prebuffering.md)

## Objective
Implement proactive in-memory pre-warming (`prepareStory()`) in `VieNeuTTSManager` and `ReadingService`. When story content is received from the API, the engine pre-synthesizes the first 2 chunks (Title + Opening sentence) into an LRU in-memory cache without playing audio, enabling instant playback (TTFT $\le 50\text{ms}$) with zero buffer starvation when the user hits "Play".

## Requirements
### Functional
- [x] Create `PrewarmedChunkCache` in `com.skul9x.doctruyen.tts.vieneu`:
  - Stores pre-synthesized PCM buffers keyed by `(storyId, voiceName, chunkIndex)`.
  - Max capacity limit of 4 chunks (~8-12MB PCM in RAM) with auto-eviction on story switch.
- [x] Add `prepareStory(storyId: Int, title: String, content: String, voiceName: String)` to `VieNeuTTSManager`:
  - Splits text via `SmartTextSegmenter.segmentStory(title, content)`.
  - Runs G2P and ONNX inference in a background coroutine with `Dispatchers.Default` for Chunks 0 and 1.
  - Automatically cancels pre-warm job if a new story is prepared or playback starts.
- [x] Update `TTSAudioPipeline.startReadingChunks()` to check `PrewarmedChunkCache`:
  - If pre-warmed chunks exist, load them directly into `chunkQueue` on startup.
  - Synthesis coroutine skips already cached chunk indices and continues synthesizing Chunk 2 onwards immediately.

### Non-Functional
- [x] **Memory Safety:** Cache size hard-capped; automatically cleared on story change or low memory.
- [x] **Zero Waste:** Pre-warming is cancelled immediately if user exits screen before completion.

## Implementation Steps
1. [x] Create `PrewarmedChunkCache.kt` with thread-safe ConcurrentHashMap + size bounds.
2. [x] Add `prepareStory` method and pre-warm job management to `VieNeuTTSManager.kt`.
3. [x] Expose `prepareStory(storyId, title, content)` in `ReadingService.kt` for bound activities.
4. [x] Wire `TTSAudioPipeline` to ingest pre-warmed frames at `startPipeline()` startup.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/PrewarmedChunkCache.kt` - [NEW] In-memory PCM cache.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Add `prepareStory()` and pre-warm job lifecycle.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` - Support injecting pre-warmed chunks.
- `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt` - Expose `prepareStory` API.

## File-Based Test Criteria
Create unit test `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PrewarmedChunkCacheTest.kt`:
- [x] `testPrewarmStory_populatesCacheForFirstTwoChunks`: Verify preparing a story populates cache with Chunks 0 & 1.
- [x] `testStartReading_consumesPrewarmedCacheAndSkipsDuplicateInference`: Verify pipeline synthesizes starting at Chunk 2 when 0 & 1 are cached.
- [x] `testSwitchStory_invalidatesPreviousStoryCache`: Verify preparing Story B cleans up Story A from memory.

---
Next Phase: [Phase 03: UI & Lifecycle Orchestration](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-03-lifecycle-and-ui-orchestration.md)
