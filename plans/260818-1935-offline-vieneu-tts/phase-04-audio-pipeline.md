# Phase 04: Audio Pipeline & Playback
Status: ✅ Completed  
Dependencies: Phase 03

## Objective
Build the audio pipeline that takes raw PCM float data from the ONNX engine (Phase 03) and plays it through the device speaker/headphones using Android's `AudioTrack` API. Implement chunk-based streaming so the user hears audio within 1-2 seconds of pressing play, instead of waiting for the entire text to be synthesized.

## Background
VieNeu-TTS outputs audio at **48,000 Hz, mono, float32**. For story reading, text is split into sentences/paragraphs. Each chunk is synthesized independently and queued for seamless playback. The pipeline must support:
- **Pre-buffering**: While chunk N is playing, chunk N+1 is being synthesized in background
- **Gapless playback**: No audible pauses between chunks
- **Pause/Resume**: Save current position and resume from exact point

## Requirements

### Functional
- [x] `AudioPlayer.kt` — wrapper around `AudioTrack` for low-latency PCM playback at 48kHz
- [x] `TTSAudioPipeline.kt` — orchestrator that:
  - Splits input text into sentence-level chunks
  - Runs VieNeu inference on each chunk in a background coroutine
  - Queues PCM audio for continuous playback
  - Supports play / pause / resume / stop / seek-to-chunk
- [x] Pre-buffer at least 1 chunk ahead for gapless playback
- [x] Track current reading position (which chunk / which sentence) for UI sync
- [x] Convert 48kHz float32 PCM to 16-bit PCM (for AudioTrack compatibility on older devices)

### Non-Functional
- [x] Time-to-first-audio < 3 seconds on mid-range devices
- [x] No audio glitches or pops between chunks
- [x] Memory: audio buffer pool < 50 MB (reuse buffers)
- [x] Battery: release AudioTrack when not playing

## Implementation Steps

1. [x] **Create `AudioPlayer.kt`**:
   ```kotlin
   class AudioPlayer(
       private val sampleRate: Int = 48000,
       private val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
       private val encoding: Int = AudioFormat.ENCODING_PCM_16BIT
   ) {
       private var audioTrack: AudioTrack? = null

       fun init() { /* create AudioTrack with buffer size */ }
       fun write(pcm16: ShortArray) { /* write to AudioTrack */ }
       fun play() { audioTrack?.play() }
       fun pause() { audioTrack?.pause() }
       fun stop() { audioTrack?.stop(); audioTrack?.flush() }
       fun release() { audioTrack?.release(); audioTrack = null }
   }
   ```

2. [x] **Create PCM conversion utility** `PcmUtils.kt`:
   ```kotlin
   object PcmUtils {
       /** Convert float32 [-1.0, 1.0] to int16 [-32768, 32767] */
       fun floatToShort(floatPcm: FloatArray): ShortArray {
           return ShortArray(floatPcm.size) { i ->
               (floatPcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
           }
       }

       /** Split text into sentence chunks at ". ", "! ", "? ", "\n" boundaries */
       fun splitIntoSentences(text: String, maxCharsPerChunk: Int = 200): List<String>
   }
   ```

3. [x] **Create `TTSAudioPipeline.kt`** — the core orchestrator:
   ```kotlin
   class TTSAudioPipeline(
       private val engine: VieNeuOnnxEngine,
       private val voiceName: String = "Trúc Ly"
   ) {
       sealed class PipelineState {
           object Idle : PipelineState()
           object Synthesizing : PipelineState()
           object Playing : PipelineState()
           object Paused : PipelineState()
           data class Error(val message: String) : PipelineState()
       }

       private val audioPlayer = AudioPlayer()
       private val chunkQueue = ConcurrentLinkedQueue<ShortArray>()
       private var synthesisJob: Job? = null
       private var playbackJob: Job? = null

       // Progress tracking
       var currentChunkIndex: Int = 0
       var totalChunks: Int = 0
       val progress: Float get() = if (totalChunks > 0) currentChunkIndex.toFloat() / totalChunks else 0f

       fun startReading(text: String, onStateChange: (PipelineState) -> Unit)
       fun pause()
       fun resume()
       fun stop()
       fun seekToChunk(index: Int)
       fun release()
   }
   ```

4. [x] **Implement the synthesis → playback loop**:
   ```
   startReading(text):
     1. sentences = splitIntoSentences(text)
     2. totalChunks = sentences.size
     3. Launch synthesis coroutine:
        for each sentence in sentences:
            phonemes = SeaG2P.phonemize(sentence)
            pcmFloat = engine.infer(phonemes, voiceName)
            pcm16 = PcmUtils.floatToShort(pcmFloat)
            chunkQueue.add(pcm16)
     4. Launch playback coroutine:
        audioPlayer.init()
        audioPlayer.play()
        while (hasMoreChunks || queue.isNotEmpty()):
            chunk = chunkQueue.poll()
            if (chunk != null):
                audioPlayer.write(chunk)
                currentChunkIndex++
            else:
                delay(50) // wait for synthesis to catch up
        audioPlayer.stop()
   ```

5. [x] **Implement pause/resume logic**:
   - `pause()`: Pause AudioTrack, cancel synthesis of future chunks, save `currentChunkIndex`
   - `resume()`: Re-start synthesis from `currentChunkIndex`, resume AudioTrack

6. [x] **Implement audio focus handling**:
   - Request `AUDIOFOCUS_GAIN` before playing
   - Duck/pause on `AUDIOFOCUS_LOSS_TRANSIENT` (e.g., incoming call)
   - Stop on `AUDIOFOCUS_LOSS`

## Files to Create/Modify

- `app/.../tts/vieneu/AudioPlayer.kt` — [NEW] AudioTrack wrapper
- `app/.../tts/vieneu/PcmUtils.kt` — [NEW] PCM conversion + text splitting
- `app/.../tts/vieneu/TTSAudioPipeline.kt` — [NEW] Synthesis-to-playback orchestrator

## Verification Test

### Test: `TTSAudioPipelineTest.kt`
Android instrumented test:

```kotlin
package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TTSAudioPipelineTest {

    private lateinit var context: Context
    private lateinit var engine: VieNeuOnnxEngine
    private lateinit var pipeline: TTSAudioPipeline

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        engine = VieNeuOnnxEngine(context)
        pipeline = TTSAudioPipeline(engine, voiceName = "Trúc Ly")
    }

    @After
    fun teardown() {
        pipeline.release()
        engine.close()
    }

    @Test
    fun testPcmConversion() {
        // Given float PCM data
        val floatPcm = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f, -1.0f)

        // When converted to 16-bit
        val shortPcm = PcmUtils.floatToShort(floatPcm)

        // Then values should be correctly mapped
        assertEquals("0.0 → 0", 0, shortPcm[0].toInt())
        assertTrue("0.5 → ~16383", shortPcm[1] > 16000)
        assertTrue("-0.5 → ~-16383", shortPcm[2] < -16000)
        assertEquals("1.0 → 32767", 32767, shortPcm[3].toInt())
        assertEquals("-1.0 → -32767", -32767, shortPcm[4].toInt())
    }

    @Test
    fun testTextSplitting() {
        val text = "Câu một. Câu hai! Câu ba? Câu bốn.\nCâu năm."
        val chunks = PcmUtils.splitIntoSentences(text)

        assertTrue("Should split into multiple chunks", chunks.size >= 4)
        chunks.forEachIndexed { i, chunk ->
            assertTrue("Chunk $i should not be blank", chunk.isNotBlank())
            println("Chunk $i: '$chunk'")
        }
    }

    @Test
    fun testAudioPlayerInitAndRelease() {
        val player = AudioPlayer()
        player.init()

        // Should be able to write silent data
        val silence = ShortArray(4800) // 100ms of silence at 48kHz
        player.play()
        player.write(silence)
        player.stop()
        player.release()

        // Should not crash on double-release
        player.release()
        println("✅ AudioPlayer init/write/stop/release cycle completed")
    }

    @Test
    fun testEndToEndPipeline() = runBlocking {
        var states = mutableListOf<TTSAudioPipeline.PipelineState>()

        // Given a short text
        val text = "Xin chào. Đây là bài thử nghiệm."

        // When we start reading
        pipeline.startReading(text) { state ->
            states.add(state)
            println("Pipeline state: $state")
        }

        // Wait for synthesis + playback (max 30 seconds)
        var waited = 0
        while (pipeline.currentChunkIndex < pipeline.totalChunks && waited < 30000) {
            delay(500)
            waited += 500
            println("Progress: chunk ${pipeline.currentChunkIndex}/${pipeline.totalChunks}")
        }

        // Then pipeline should have processed chunks
        assertTrue("Should have processed at least 1 chunk", pipeline.currentChunkIndex > 0)
        assertTrue("States should include Playing", states.any { it is TTSAudioPipeline.PipelineState.Playing })
        println("✅ End-to-end pipeline completed: ${pipeline.currentChunkIndex}/${pipeline.totalChunks} chunks")
    }
}
```

## Notes
- `AudioTrack` in `MODE_STREAM` is preferred over `MODE_STATIC` because we generate audio incrementally. `MODE_STATIC` would require all audio data upfront.
- Buffer size for `AudioTrack` should be at least `2 * AudioTrack.getMinBufferSize()` to prevent underruns.
- On Android API 26+, `AudioTrack.Builder` with `AudioAttributes.USAGE_MEDIA` and `CONTENT_TYPE_SPEECH` ensures proper audio routing and volume control.
- The pre-buffer strategy (synthesize chunk N+1 while playing chunk N) is essential. Without it, there would be a noticeable gap between sentences.

---
Previous Phase: [Phase 03 — ONNX Engine](./phase-03-onnx-engine.md)  
Next Phase: [Phase 05 — Integration](./phase-05-integration.md)
