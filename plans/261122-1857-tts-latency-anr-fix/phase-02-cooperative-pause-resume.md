# Phase 02: Cooperative Audio Pipeline Pause/Resume with Buffer Retention (P1)
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Resolve the destructive pause/resume behavior (P1 in `fix.md`) where calling `pause()` cancels `synthesisJob`, cancels `playbackJob`, and executes `drainChannel()`, throwing away up to 4 pre-synthesized audio chunks (dozens of seconds of heavy CPU work), and causing audio repetition and underruns upon `resume()`.

## Scope
1. Refactor `TTSAudioPipeline.kt` to introduce a cooperative suspension pattern for pausing and resuming rather than job cancellation and channel draining.
2. Refactor `AudioPlayer.kt` to support clean hardware pause/resume and sub-chunk write offsets to eliminate phrase repetition.
3. Ensure channel backpressure naturally halts the synthesis producer during pause without throwing away buffered chunks.

## Requirements
### Functional
- When `pause()` is invoked:
  - `audioPlayer.pause()` halts hardware playback immediately.
  - `synthesisJob` and `playbackJob` are suspended cooperatively (e.g. via suspending `Mutex` or `MutableStateFlow` gate) rather than being cancelled and destroyed.
  - `audioChannel` retains all queued chunks; `drainChannel()` is NOT called on pause.
- When `resume()` is invoked:
  - `audioPlayer.play()` resumes playback from the exact position.
  - Suspended coroutines are unlocked, immediately consuming remaining chunks in the channel with zero delay or re-synthesis.
- When `stop()` is invoked:
  - Jobs are cancelled, `audioChannel` is closed/drained, `audioPlayer.stop()` flushes buffers and resets tracking indexes.

### Non-Functional & Performance
- Zero CPU spent re-synthesizing already synthesized chunks after pause/resume.
- Zero audio repetition artifact upon resume.
- Channel capacity bound prevents unbounded memory consumption during extended pause.

## How to Implement
1. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`:
   - Replace destructive `pause()`:
     - Remove `synthesisJob?.cancel()`, `playbackJob?.cancel()`, and `drainChannel()`.
     - Introduce a thread-safe pausing gate (such as `isPaused = AtomicBoolean(false)` with a cooperative suspending check or `Mutex.withLock` checkpoint inside both synthesis loop and playback loop).
     - Set state to `PipelineState.Paused` and call `audioPlayer.pause()`.
   - In `resume()`:
     - Verify state is `PipelineState.Paused`.
     - Request audio focus and call `audioPlayer.play()`.
     - Release/unlock pausing gate to resume loop iteration.
     - Set state to `PipelineState.Playing`.
   - In `stopInternal()`:
     - Fully cancel jobs, drain channel, and reset indexes only on true stop or story change.
2. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/AudioPlayer.kt`:
   - Ensure `pause()` only calls `audioTrack?.pause()` without resetting positions or marking `needsReinit = true`.
   - Break `writeSuspended()` into small manageable frames (e.g. 100ms–200ms slices) with cancellation checks so pause responds with sub-second precision and resume does not replay already heard audio.

## Files to Create/Modify
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/AudioPlayer.kt`

## Dependencies
- Phase 01.

## Constraints
- Audio channel capacity must be strictly bounded (e.g. 2–4 chunks) to prevent heap bloating while synthesis waits on backpressure.
- Must handle transient audio focus loss (`AUDIOFOCUS_LOSS_TRANSIENT`) cleanly without losing buffers.

## Acceptance Criteria
- [x] Pausing reading does not clear or drain the audio channel buffer.
- [x] Resuming reading immediately starts playback of next buffered chunk without re-invoking `synthesizeChunk`.
- [x] Repeatedly toggling pause and resume does not leak coroutines or drop audio quality.
- [x] Passes `CooperativePipelinePauseResumeTest`.

## Test Implementation
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/CooperativePipelinePauseResumeTest.kt`
- Exactly one comprehensive file-based test suite verifying:
  1. `pause()` preserves channel items and does not invoke `drainChannel()`.
  2. `resume()` picks up directly from existing buffered channel items without triggering re-synthesis of chunk `currentChunkIndex`.
  3. No audio loop repetition: verification that playback position increments monotonically.

