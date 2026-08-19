package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
        engine = VieNeuOnnxEngine.create(context)
        pipeline = TTSAudioPipeline(engine, voiceName = "Trúc Ly", context = context)
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
        val states = mutableListOf<TTSAudioPipeline.PipelineState>()

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
