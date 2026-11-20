# Phase 02: Pipeline, Pause Duration & Integration Verification
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Verify and adapt `TTSAudioPipeline.kt`, `PauseConfig`, and story reading orchestration for punctuation-first chunks up to 200 characters. Ensure audio playback, pre-buffering heuristics, pause durations, and background service lifecycle work stably with fewer, longer, and more natural audio chunks.

## Scope
- Validate `TTSAudioPipeline.kt` pre-buffering and cancellation handling with longer chunks (up to 200 chars).
- Confirm pause durations for `PauseType.CLAUSE_BREAK` (180ms), `SENTENCE_BREAK` (400ms), and `PARAGRAPH_BREAK` (650ms) align smoothly across the novel text.
- Ensure `PcmUtils.kt` callers correctly inherit `DEFAULT_MAX_CHARS = 200`.
- Update any regression tests that hardcoded `SmartTextSegmenter.DEFAULT_MAX_CHARS == 75`.

## Requirements
### Functional
1. **Pipeline Streaming Stability**: `TTSAudioPipeline` generates chunks up to 200 characters without memory spikes, underruns, or frame-capping truncation.
2. **Pause Timing Alignment**: Chunks ending in `,` or `:.` receive `PauseType.CLAUSE_BREAK` (180ms); chunks ending in `.` receive `PauseType.SENTENCE_BREAK` (400ms) or `PARAGRAPH_BREAK` (650ms).
3. **Backward Compatibility**: `PcmUtils.splitIntoSentences` and `PcmUtils.splitIntoChunks` function seamlessly with the updated 200-char default.

### Non-Functional
- Real-Time Factor (RTF) remains well below 0.85x on mobile device inference.
- Zero audio buffer starvations or audible glitches across multi-paragraph novel chapters.

## How to Implement
1. In `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/OptimizedTextChunkingAndStateTest.kt`:
   - Update assertions expecting the old `75` char constant to expect `200`.
2. Inspect `TTSAudioPipeline.kt`:
   - Verify lookahead pre-buffering queue logic with 200-character chunks.
   - Confirm `AudioTrack` write buffer accommodates 48kHz mono PCM up to 5-8 seconds per chunk.
3. Verify `ReadingService.kt`:
   - Validate state transitions (IDLE -> READY -> PLAYING) and progress callback intervals with longer chunks.

## Files to Create / Modify
- **Modify:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/OptimizedTextChunkingAndStateTest.kt` - Update constant assertion if present.
- **Create:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PunctuationFirstPipelineIntegrationTest.kt` - End-to-end unit test validating chunk pauses, pipeline chunking, and story segmentation.

## Dependencies
- Phase 01 completed.
- Android JUnit 4 runner and ONNX Runtime mock/JVM context.

## Constraints
- Do not modify ONNX model architectures or quantization pipelines.
- Audio synthesis frame cap (`VieNeuOnnxEngine.maxExpectedFrames`) must naturally scale with phoneme length without hitting premature frame cutoffs.

## Acceptance Criteria
- A full 11-paragraph story (such as *"Một trí khôn hơn trăm trí khôn"*) segments into clean punctuation-bounded chunks.
- Every chunk has correct `pauseDurationMs` matching its punctuation type.
- Unit test suite passes 100% without regression.

## Comprehensive File-Based Test
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PunctuationFirstPipelineIntegrationTest.kt`
- Test cases:
  1. `testFullStoryPunctuationFirstSegmentation`: Verifies the entire sample story segments into 23 natural punctuation chunks rather than 26 fragmented chunks.
  2. `testPauseTypeAndDurationMapping`: Asserts clause pauses (180ms) and sentence pauses (400ms/650ms) attach properly.
  3. `testPipelineCompatibilityWith200CharChunks`: Asserts chunk queuing and progress estimation calculate accurately.

