# Phase 02: Bounded Audio Channel & Playback Concurrency

**Status:** ✅ Completed  
**Dependencies:** Phase 01  

## Objective
Implement strict memory backpressure between the synthesis producer and the audio playback consumer. Prevent unbounded accumulation of uncompressed 48kHz PCM audio in memory during long reading sessions, eliminate thread blocking on `Dispatchers.Default`, and replace busy-wait polling with reactive, event-driven coroutine synchronization.

## Scope
- Modify `TTSAudioPipeline.kt` and `AudioPlayer.kt`.
- Replace `ConcurrentLinkedQueue` with a bounded Coroutine `Channel` providing natural producer suspension when the buffer ceiling is reached.
- Isolate blocking `AudioTrack.write` calls onto a dedicated I/O or single-thread audio dispatcher, freeing `Dispatchers.Default` for compute tasks.
- Replace `delay(20)` polling loops with suspending channel reception for instantaneous, jitter-free chunk handover.

## Requirements
### Functional
1. **Bounded Buffer Capacity**: The pipeline must queue no more than a configurable maximum number of chunks (default: 4 chunks, representing ~10–12 seconds of speech).
2. **Natural Producer Suspension**: When the chunk buffer is full, the synthesis job must suspend asynchronously without consuming CPU or dropping chunks until the consumer plays back queued audio.
3. **Non-blocking Dispatcher Isolation**: Hardware audio writes must never block worker threads in `Dispatchers.Default`.
4. **Seamless Gapless Audio Continuation**: Playback transitions across chunks, micro-fades, and acoustic pauses must remain gapless without click, pop, or underrun artifacts.
5. **Instant Cancellation & Flush**: Pausing, stopping, or seeking must immediately cancel pending channel items, clear hardware buffers, and release audio focus without hanging or deadlocks.

### Non-Functional
- Memory occupied by unplayed PCM short arrays in the pipeline must be capped under 15MB at all times regardless of story length.
- Latency between chunk availability and playback consumption must drop to 0ms (replacing up to 20ms polling jitter).
- Zero thread starvation on `Dispatchers.Default`.

## Constraints
- Maintain existing `PipelineState` state transitions (`Idle`, `Buffering`, `Synthesizing`, `Playing`, `Paused`, `Error`).
- Retain existing `TtsMetricsLogger` hooks for pre-buffering, starvation, and playback timing.
- Preserve backward compatibility with `startReading`, `pause`, `resume`, `stop`, and `seekToChunk`.

## Acceptance Criteria
- Synthesizing a 100-chunk story never allocates more than the configured chunk buffer capacity in the active queue.
- Pausing audio playback immediately halts further synthesis once the buffer limit is reached.
- Resuming playback immediately triggers consumption and subsequent synthesis resumption without deadlocks.
- `AudioTrack` write operations execute off the default computation thread pool.

## Comprehensive File-Based Test
- **File:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/BoundedAudioChannelPipelineTest.kt`
- **Verification Strategy:**
  1. `testBoundedChannelBackpressureSuspension`: Verifies that producer coroutine suspends when queue reaches capacity (e.g., 4 chunks) and resumes only when consumer drains an item.
  2. `testMemoryCapUnderLongStoryPlayback`: Asserts that queue memory footprint remains bounded below 15MB when simulating 150 consecutive chunks.
  3. `testDispatcherSeparationForAudioTrack`: Verifies that blocking audio playback operations execute on dedicated audio dispatchers without monopolizing `Dispatchers.Default`.
  4. `testImmediateCancellationAndChannelDrain`: Verifies that calling `stop()` or `pause()` cleanly drains the channel and terminates active playback jobs without leaks.

