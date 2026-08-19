package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.skul9x.doctruyen.utils.DebugLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class TTSNaturalReadingDeviceTest {

    private lateinit var context: Context
    private lateinit var engine: VieNeuOnnxEngine
    private lateinit var pipeline: TTSAudioPipeline

    companion object {
        private const val STORY_TITLE = "Một trí khôn hơn trăm trí khôn"
        private const val STORY_CONTENT = """Ngày xưa, có đôi bạn thân là con Chồn và con Gà Rừng. Chồn thường hay khoe khoang rằng nó có hàng trăm trí khôn, còn Gà Rừng thì khiêm tốn bảo chỉ có một trí khôn mà thôi.

Một buổi sáng nọ, hai bạn đang dạo chơi trên cánh đồng, bất ngờ thấy một người thợ săn đến gần. Họ vội vàng chạy vào một cái hang để trốn. Người thợ săn thấy dấu chân ở cửa hang thì reo lên: "Có mà trốn đằng trời!" rồi dùng gậy thọc vào hang để bắt.

Gà Rừng lo lắng bảo Chồn:

- Cậu có hàng trăm trí khôn, hãy nghĩ kế ra đi!

Nhưng Chồn buồn bã:

- Lúc này, trong đầu mình chẳng còn một trí khôn nào cả.

Sau một lúc đắn đo, Gà Rừng nghĩ ra một kế, thì thầm với Chồn:

- Mình sẽ giả vờ chết cứng, còn cậu cứ như thế nhé!

Khi người thợ săn lôi Gà Rừng ra thấy nó nằm bất động tưởng đã chết nên quăng nó xuống đám cỏ rồi thọc gậy vào hang bắt Chồn. Bất ngờ, Gà Rừng vùng chạy, người thợ săn đuổi theo. Đợi thế, Chồn cũng chạy thoát vào rừng an toàn.

Ngày hôm sau, hai bạn gặp lại nhau, Chồn thừa nhận:

- Một trí khôn của cậu còn hơn cả trăm trí khôn của mình.

Bài học cho Minh Khôi: Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn. Không cần khoe mình giỏi, vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc và luôn sẵn sàng học hỏi từ người khác."""
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        SeaG2P.init(context)
        engine = VieNeuOnnxEngine.create(context)
        // Warmup ONNX sessions and JNI
        try {
            val warmPhonemes = SeaG2P.phonemize("Khởi động")
            engine.infer(warmPhonemes, voiceName = "Trúc Ly")
        } catch (e: Throwable) {
            // ignore warmup error
        }
        pipeline = TTSAudioPipeline(
            engine = engine,
            voiceName = "Trúc Ly",
            context = context
        )
    }

    @After
    fun teardown() {
        pipeline.release()
        engine.close()
    }

    @Test
    fun testStory118NaturalReadingEndToEnd() = runBlocking {
        // Start telemetry session
        TtsMetricsLogger.startSession("118", STORY_TITLE)

        val completionLatch = CountDownLatch(1)
        val pauseConfig = PauseConfig(
            titlePauseMs = 1000,
            paragraphPauseMs = 600,
            sentencePauseMs = 350,
            clausePauseMs = 180,
            dialogueLeadPauseMs = 300
        )

        val segments = SmartTextSegmenter.segmentStory(STORY_TITLE, STORY_CONTENT, pauseConfig = pauseConfig)
        assertTrue("Story segments should not be empty", segments.isNotEmpty())

        var isPlayingSeen = false
        var firstAudioDetectedTime = 0L
        val sessionStartTime = System.currentTimeMillis()

        pipeline.startReadingChunks(
            chunkList = segments,
            onStateChange = { state ->
                when (state) {
                    is TTSAudioPipeline.PipelineState.Playing -> {
                        if (!isPlayingSeen) {
                            isPlayingSeen = true
                            firstAudioDetectedTime = System.currentTimeMillis()
                        }
                    }
                    is TTSAudioPipeline.PipelineState.Idle -> {
                        if (isPlayingSeen && pipeline.currentChunkIndex >= pipeline.totalChunks) {
                            completionLatch.countDown()
                        }
                    }
                    is TTSAudioPipeline.PipelineState.Error -> {
                        fail("Playback encountered error: ${state.message}")
                    }
                    else -> {}
                }
            }
        )

        // Allow up to 180 seconds for the entire story reading on device
        val completed = completionLatch.await(180, TimeUnit.SECONDS)
        assertTrue("Story reading should complete within 180s", completed)

        val sessionMetrics = TtsMetricsLogger.currentSession
        val logs = DebugLogger.getFormattedLogs()

        println("==================== BENCHMARK RESULTS ====================")
        println("Story: ${sessionMetrics.storyTitle} (ID: ${sessionMetrics.storyId})")
        println("TTFT: ${sessionMetrics.timeToFirstAudioMs} ms")
        println("Total Chunks: ${sessionMetrics.chunks.size}")
        println("Average RTF: ${sessionMetrics.averageRtf}")
        println("Total Audio Duration: ${sessionMetrics.totalAudioDurationMs} ms (${sessionMetrics.totalAudioDurationMs / 1000f} s)")
        println("Total Synthesis Time: ${sessionMetrics.totalSynthesisDurationMs} ms")
        println("Buffer Underruns: ${sessionMetrics.underrunCount}")
        println("===========================================================")

        // 1. Assert TTFT on physical device (<= 3500ms for neural synthesis & AudioTrack startup)
        assertTrue(
            "TTFT should be <= 3500ms, actual was ${sessionMetrics.timeToFirstAudioMs}ms",
            sessionMetrics.timeToFirstAudioMs <= 3500L
        )

        // 2. Assert minimal playback starvation/underrun events (<= 10 under background OS load)
        assertTrue("Buffer underruns should be <= 10, was ${sessionMetrics.underrunCount}", sessionMetrics.underrunCount <= 10)

        // 3. Verify inter-chunk pause lengths conform to configuration
        sessionMetrics.chunks.forEach { chunk ->
            if (chunk.chunkIndex == 0) {
                // Title chunk should have titlePauseMs
                assertEquals("Title pause config", pauseConfig.titlePauseMs.toLong(), chunk.pauseDurationMs)
            }
        }

        // 4. Verify all metrics are formatted and visible in DebugLogger
        assertTrue("DebugLogger should contain TTS timing metrics", logs.contains("[TTS Session Summary]") || logs.contains("TTS Session Summary"))
    }
}
