# Phase 01: Immediate Cooperative Inference Cancellation & Latency Elimination (AUD-001 & AUD-005)
Status: ✅ Completed
Dependencies: None

## Objective
Eliminate the critical 3.0s–7.5s unresponsiveness and thread lock contention (AUD-001) that occurs when a user triggers stop, seek, or story transitions during active neural speech synthesis, and correct the engine state guard order (AUD-005) so that attempting inference on a closed engine correctly throws `IllegalStateException`.

## Scope
1. Implement cooperative cancellation reset capability (`resetCancel()`) in `VieNeuOnnxEngine.kt`.
2. Fix the state verification order in `VieNeuOnnxEngine.inferWithTiming()` so `isClosed` is checked before `cancelRequested`.
3. Eliminate `withContext(NonCancellable)` from the standard playback synthesis loop in `TTSAudioPipeline.kt`.
4. Trigger immediate engine-level cancellation (`engine.requestCancel()`) in `TTSAudioPipeline.stopInternal()`.
5. Reset cancellation state (`engine.resetCancel()`) upon initiating a fresh pipeline session in `startPipeline()`.

## Requirements
### Functional
- When `stop()`, `seekToChunk()`, or `startReadingChunks()` is invoked while chunk synthesis is actively computing:
  - The in-flight acoustic inference loop in `VieNeuOnnxEngine` must abort execution cooperatively within < 50ms instead of running to chunk completion (up to 7.5s).
  - The engine's internal `ReentrantLock` must be released immediately, allowing incoming speech requests to proceed without blocking.
- When `startPipeline()` commences a new reading session or chunk seek, the engine's cancellation flag must be reset to `false` so subsequent chunks synthesize normally.
- Invocations of `inferWithTiming()` on a disposed/closed engine must throw `IllegalStateException` with a message containing `"closed"`.

### Non-Functional & Performance
- Stop and seek transition latency must drop from 3,000ms–7,500ms down to < 50ms.
- Eliminate wasted CPU utilization (94% core load) on discarded audio computation.
- Thread-safe state transitions with zero native memory corruption, zero `SIGSEGV`, and zero deadlock.

## How to Implement
1. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`:
   - Add `fun resetCancel()` synchronized on the engine's internal `lock: ReentrantLock`:
     ```kotlin
     fun resetCancel() {
         lock.withLock {
             cancelRequested = false
         }
     }
     ```
     > [!IMPORTANT]
     > **Critical Concurrency Safety & Anti-Race Condition:**
     > `inferWithTiming` synchronizes via `lock.withLock`, NOT via JVM object monitors (`@Synchronized`).
     > If `resetCancel()` were just `@Synchronized`, invoking `resetCancel()` immediately in `startPipeline()` right after `stopInternal()` (such as in `seekToChunk()`) would reset `cancelRequested` to `false` in microseconds on the caller thread before the worker thread on `Dispatchers.Default` could observe the flag!
     > By synchronizing on `lock.withLock`, `resetCancel()` waits for the in-flight inference to abort (which takes < 5ms as `cancelRequested` is checked 15 times per frame), ensuring the engine lock is completely relinquished before clearing the cancellation flag.

   - In `inferWithTiming(phonemes, speakerEmb, refCodes, ...)`:
     Reorder guards so that `isClosed` takes priority:
     ```kotlin
     if (isClosed) {
         throw IllegalStateException("VieNeuOnnxEngine is closed")
     }
     if (cancelRequested) {
         throw InterruptedException("Synthesis cancelled")
     }
     ```
2. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`:
   - In `startPipeline(startIndex: Int, storyId: Int)`:
     Add `engine.resetCancel()` before launching `synthesisJob` and `playbackJob`.
   - In `stopInternal(resetIndex: Boolean)`:
     Invoke `engine.requestCancel()` immediately alongside `pauseGate.resume()` and `synthesisJob?.cancel()`.
   - In `synthesisJob` chunk synthesis loop (around line 492):
     Remove the `withContext(NonCancellable)` wrapper around `engine.inferWithTiming(...)`, allowing the coroutine and cooperative engine checks to respond immediately to job cancellation signals:
     ```kotlin
     val inferStart = System.currentTimeMillis()
     val effectiveTemp = if (voiceName == "Thiền Tâm Đức") 0.25f else temperature
     val inferResult = engine.inferWithTiming(phonemes = phonemes, voiceName = voiceName, temperature = effectiveTemp)
     val inferDuration = System.currentTimeMillis() - inferStart
     ```

## Files to Create/Modify
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`

## Dependencies
- Existing `VieNeuOnnxEngine`, `TTSAudioPipeline`, `PauseGate`.

## Constraints
- Prewarm chunk 0 completion logic inside `VieNeuTTSManager.prewarmStory()` must continue to finish its isolated computation without being prematurely aborted by unrelated UI pauses.
- Must remain 100% thread-safe across `Dispatchers.Default` and `Dispatchers.IO`.

## Acceptance Criteria
- [x] In-flight chunk inference aborts and releases `engine.lock` in < 50ms upon `stop()` or story switch.
- [x] Subsequent calls to `startReading()` or `seekToChunk()` execute immediately without waiting for previous chunks.
- [x] Calling `inferWithTiming()` on a closed engine throws `IllegalStateException`.
- [x] Passes `NativeMemoryLifecycleManagementTest.testVieNeuOnnxEngineCloseAndClosedGuard`.
- [x] Passes `ImmediateInferenceCancellationTest`.

## Test Implementation
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/ImmediateInferenceCancellationTest.kt`
- Exactly one comprehensive file-based test suite verifying:
  1. Triggering `stopInternal()` while `inferWithTiming` is actively processing causes the inference pass to terminate immediately with `InterruptedException` within < 50ms.
  2. Starting a new pipeline session after an abort successfully resets `cancelRequested` and synthesizes subsequent chunks without error.
  3. Closed engine guard test verifying `IllegalStateException` contract.
