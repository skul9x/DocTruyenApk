# Phase 01: Prewarm Preservation & Instant First-Chunk Playback (P2)
Status: ✅ Completed
Completed At: 2026-11-22T19:16:00+07:00
Dependencies: None

## Objective
Eliminate the critical prewarm cancellation bug (P2 in `fix.md`) where triggering playback immediately cancels active prewarming, causing the 3–7 second inference pass running inside `NonCancellable` to hold the engine lock to completion, discard its computed chunk 0 result due to premature `isActive` checks, and force the main pipeline to wait and re-synthesize chunk 0 from scratch.

## Scope
1. Refactor `VieNeuTTSManager.kt` prewarm cancellation and completion logic.
2. Ensure computed PCM data is deposited into `PrewarmedChunkCache` immediately upon completion of inference, before any cancellation or loop exit checks.
3. Coordinate `speakContent()` and `TTSAudioPipeline.kt` so that active prewarm jobs are gracefully attached to or awaited rather than discarded, guaranteeing that pre-warmed audio is instantly delivered on Play.

## Requirements
### Functional
- When `speakContent()` or `speakWithTitlePauseInternal()` is invoked while prewarm is executing:
  - If chunk 0 is already in `PrewarmedChunkCache`, pipeline consumes it immediately (TTFT < 100ms).
  - If chunk 0 is actively synthesizing in the prewarm job, the pipeline must coordinate with the active computation rather than aborting its result and recomputing.
  - Completed prewarm chunks must never be discarded: `PrewarmedChunkCache.put(...)` must execute as soon as PCM synthesis completes.
- Multiple rapid calls to Play/Pause must never corrupt cache entries or leave orphaned locks.

### Non-Functional & Performance
- TTFT when prewarm has completed must drop from ~5.0–7.5s to < 200ms.
- Zero CPU cycles wasted on re-synthesizing chunk 0 if prewarm already ran.
- Thread-safe coordination between prewarm coroutine and playback pipeline coroutine.

## How to Implement
1. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`:
   - In `prewarmStory()`:
     - Reorder the inference block so that `PrewarmedChunkCache.put(storyId, effectiveVoice, i, combinedPcm)` occurs immediately after PCM processing, before checking `if (!isActive) break`.
     - Introduce a thread-safe coordination mechanism (such as a `CompletableDeferred<ShortArray?>` or tracking active chunk index in `PrewarmedChunkCache`) so the pipeline can observe an in-flight chunk 0 prewarm.
   - In `speakContent()` and `speakWithTitlePauseInternal()`:
     - Replace blind, destructive `cancelPrewarm()` with cooperative synchronization: allow chunk 0 to finish and populate the cache instead of severing it.
2. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`:
   - At start of `startPipeline()` / `synthesisJob`:
     - Check `PrewarmedChunkCache.get(storyId, voiceName, 0)`. If present, immediately emit chunk 0 to `audioChannel` and advance `nextChunkIndex` to 1.
     - If chunk 0 is pending in prewarm, await completion up to a defensive timeout before falling back to manual synthesis.

## Files to Create/Modify
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/PrewarmedChunkCache.kt`

## Dependencies
- Existing `PrewarmedChunkCache`, `VieNeuOnnxEngine`, `SmartTextSegmenter`.

## Constraints
- Must remain 100% backward compatible with `ReadingService` bindings.
- Must handle edge cases: engine not initialized, empty story, network interruption during prewarm.

## Acceptance Criteria
- [x] `PrewarmedChunkCache.put` is guaranteed to be called even if prewarm job receives cancellation while chunk inference was finishing.
- [x] Pipeline start immediately retrieves prewarmed chunk 0 and routes it to `audioChannel`.
- [x] No double-synthesis of chunk 0 in logs or metrics when user plays a prewarmed story.
- [x] Passes `PrewarmPreservationIntegrityTest`.

## Test Implementation
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PrewarmPreservationIntegrityTest.kt`
- Exactly one comprehensive file-based test suite verifying:
  1. Prewarm chunk caching survives cancellation signals occurring during/after inference.
  2. Pipeline fetches prewarmed chunk 0 without invoking `engine.infer(chunk 0)`.
  3. Race condition verification: Play initiated 10ms into prewarm successfully receives computed chunk 0 without duplicate work.

