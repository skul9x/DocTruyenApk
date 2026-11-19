# Phase 04: Dedicated Audio Thread & Channel Backpressure

Status: ✅ Completed  
Dependencies: Phase 03  

## Objective
Isolate blocking audio hardware calls (`AudioTrack.write(WRITE_BLOCKING)`) from CPU-bound computation pools (`Dispatchers.Default`), eliminate 50Hz CPU battery-draining active polling loops (`delay(20)`), and implement natural backpressure using Kotlin Coroutines `Channel` to prevent memory blowup during audio synthesis.

## Scope
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`:
  - Introduce dedicated `audioDispatcher` backed by a high-priority worker thread.
  - Replace `ConcurrentLinkedQueue` with `Channel<Pair<Int, ShortArray>>(capacity = 4)`.
  - Refactor `playbackJob` to suspend naturally on `receive()` instead of polling with `delay(20)`.
  - Refactor `synthesisJob` to suspend on `send()` when the playback buffer is full (backpressure).
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/AudioPlayer.kt`:
  - Optimize `write()` handling to exit promptly upon channel cancellation.

## Requirements

### Functional Requirements
- [x] In `TTSAudioPipeline.kt`:
  - Create a dedicated single-threaded dispatcher:
    ```kotlin
    private val audioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            // Boost Linux thread priority to urgent audio (-19) to prevent CPU starvation and glitches
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            runnable.run()
        }, "AudioTrackWorkerThread")
    }
    private val audioDispatcher = audioExecutor.asCoroutineDispatcher()
    ```
  - Close/shutdown `audioExecutor` and `audioDispatcher` in `release()`.
  - Replace `private val chunkQueue = ConcurrentLinkedQueue<Pair<Int, ShortArray>>()` with:
    ```kotlin
    private var chunkChannel: Channel<Pair<Int, ShortArray>> = Channel(capacity = 4)
    ```
  - In `startPipeline()`:
    - Pre-buffering phase: Suspend until target duration or chunk count is reached without `delay(20)` loops.
    - Launch `playbackJob` on `audioDispatcher`:
      ```kotlin
      playbackJob = scope.launch(audioDispatcher) {
          for (nextChunk in chunkChannel) {
              if (!isActive) break
              currentChunkIndex = nextChunk.first
              setState(PipelineState.Playing)
              ...
              audioPlayer.write(nextChunk.second)
              ...
          }
      }
      ```
    - In `synthesisJob`:
      - After synthesizing and preparing `finalPcm`, call `chunkChannel.send(Pair(chunkIndex, finalPcm))` which automatically suspends if the consumer is slower than the producer (backpressure).
      - When all chunks are synthesized, close the channel: `chunkChannel.close()`.
  - On Pause or Stop:
    - Cancel `chunkChannel` immediately to wake up any suspended producer or consumer.
    - Re-create a fresh `Channel(capacity = 4)` when playback restarts.

### Non-Functional Requirements
- [x] Thread Isolation: 100% of `Dispatchers.Default` threads remain available for AI tensor inference and general background computation.
- [x] CPU / Battery Efficiency: 0 polling cycles during audio playback; coroutines sleep passively while waiting for audio frames.
- [x] Memory Cap: Maximum 4 audio chunks ($< 15\text{MB}$) can be buffered ahead, completely eliminating uncontrolled PCM queue inflation.
- [x] Stop/Pause Latency: Sub-50ms responsiveness when pausing or stopping playback.

## How to Implement

1. **Dispatcher & Channel Setup in `TTSAudioPipeline.kt`:**
   ```kotlin
   private val audioExecutor = Executors.newSingleThreadExecutor { r ->
       Thread({
           android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
           r.run()
       }, "AudioTrackWorkerThread")
   }
   private val audioDispatcher = audioExecutor.asCoroutineDispatcher()

   private var chunkChannel = Channel<Pair<Int, ShortArray>>(capacity = 4)
   ```
2. **Channel-Based Synthesis Ingestion:**
   ```kotlin
   // Inside synthesis coroutine in startPipeline()
   val finalPcm = ...
   // Channel.send() applies natural backpressure
   chunkChannel.send(Pair(chunkIndex, finalPcm))
   ```
3. **Passive Playback Consumer Loop:**
   ```kotlin
   playbackJob = scope.launch(audioDispatcher) {
       try {
           while (isActive) {
               // Receive suspends until item is available or channel is closed
               val nextChunk = try {
                   chunkChannel.receive()
               } catch (e: ClosedReceiveChannelException) {
                   break // All chunks consumed
               }

               currentChunkIndex = nextChunk.first
               setState(PipelineState.Playing)
               notifyProgress(...)

               val playStart = System.currentTimeMillis()
               audioPlayer.write(nextChunk.second)
               val playDuration = System.currentTimeMillis() - playStart
               TtsMetricsLogger.recordPlaybackComplete(nextChunk.first, playDuration)
           }
       } catch (e: CancellationException) {
           // Normal cancellation
       } finally {
           audioPlayer.stop()
       }
   }
   ```
4. **Lifecycle & Cleanup:**
   - In `pause()` / `stop()`:
     ```kotlin
     chunkChannel.cancel()
     chunkChannel = Channel(capacity = 4)
     ```
   - In `release()`:
     ```kotlin
     chunkChannel.cancel()
     audioExecutor.shutdown()
     ```

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` - Implement dedicated audio thread and channel backpressure.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AudioPipelineChannelConcurrencyTest.kt` - Single comprehensive verification test.

## Dependencies
- Phase 03 completed.
- `kotlinx.coroutines.channels.Channel`.

## Constraints
- `AudioTrack.write()` MUST continue using `WRITE_BLOCKING` on the dedicated thread to ensure glitch-free audio pacing governed by the hardware buffer clock.
- Channel capacity of 4 MUST be respected to bound memory while preventing underrun on fast readers.

## Acceptance Criteria
- [x] Playback thread is named `AudioTrackWorkerThread` with elevated Android Linux priority `THREAD_PRIORITY_URGENT_AUDIO` (-19).
- [x] No `delay(20)` polling loops exist in `TTSAudioPipeline.kt`.
- [x] Slow consumer suspends the synthesis coroutine when channel capacity (4) is reached.
- [x] Canceling playback immediately unblocks both producer and consumer in $< 50\text{ms}$.
- [x] All scenarios in `AudioPipelineChannelConcurrencyTest.kt` pass with 100% success rate.

## Single File-Based Test Criteria
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AudioPipelineChannelConcurrencyTest.kt`
- Test Scenarios:
  1. `testChannelBackpressureCapacity`: Send 4 chunks to channel and assert that the 5th `send()` suspends until a chunk is received.
  2. `testPassiveReceiveSuspension`: Assert that consumer coroutine suspends without CPU spinning when channel is empty.
  3. `testImmediateCancellationResponsiveness`: Enqueue items, trigger pipeline pause/cancel, and assert execution terminates in $< 50\text{ms}$.
  4. `testNormalCompletionOnChannelClose`: Synthesize $N$ chunks, close channel, and verify consumer drains all $N$ chunks and transitions state to Idle.

## Review Checkpoint
Stop after running `AudioPipelineChannelConcurrencyTest.kt` and await user review before proceeding to Phase 05.

