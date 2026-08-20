package com.skul9x.doctruyen.tts.vieneu

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 02 Comprehensive Verification Test:
 * 1. Adaptive pre-buffer calculation (computeAdaptiveTargetDurationMs) for INT8, FP16, FP32 across various CPU core profiles.
 * 2. INT8 low-TTFT playback transition: 1 chunk of audio (~2,440ms) transitions from Buffering to Playing immediately.
 * 3. Safe fallback threshold enforcement for FP32 / constrained core profiles (7,500ms).
 * 4. Mid-story seeking / resumption immediate playback threshold (1,500ms).
 */
class AdaptivePreBufferLowTtftTest {

    private lateinit var engineInt8: VieNeuOnnxEngine
    private lateinit var engineFp32: VieNeuOnnxEngine
    private lateinit var pipelineInt8: TTSAudioPipeline
    private lateinit var pipelineFp32: TTSAudioPipeline

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupNativeLibrary() {
            val possiblePaths = listOf(
                "sea-g2p-android/target/release/libsea_g2p_android.so",
                "../sea-g2p-android/target/release/libsea_g2p_android.so",
                "app/src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/sea-g2p-android/target/release/libsea_g2p_android.so"
            )
            for (path in possiblePaths) {
                val file = File(path)
                if (file.exists()) {
                    try {
                        System.load(file.absolutePath)
                        break
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun createEngine(precision: String): VieNeuOnnxEngine {
        val baseAssetsDir = File("src/main/assets/vieneu")
        val backboneDir = OnnxSessionManager.getBackboneDir(baseAssetsDir, precision)
        val codecDir = File(baseAssetsDir, "codec")
        val voicesFile = File(baseAssetsDir, "voices_v3_turbo.json")

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 2,
            precision = precision
        )

        val configFile = File(backboneDir, "config.json")
        val config = VieNeuConfig.fromJson(configFile.readText(Charsets.UTF_8))

        val tokenizerFile = File(backboneDir, "tokenizer.json")
        val tokenizer = VieNeuTokenizer.fromFile(tokenizerFile)

        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
        val headsReader = NpzReader.read(headsFile)

        if (voicesFile.exists()) {
            VoicePresets.loadFromJson(voicesFile.readText(Charsets.UTF_8))
        }

        return VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = headsReader.get2D("text_emb")!!,
            audioEmbeddings = headsReader.get3D("audio_emb")!!,
            xvecW = headsReader.get2D("xvec_w"),
            xvecB = headsReader.getFloatArray("xvec_b"),
            xvecLnW = headsReader.getFloatArray("xvec_ln_w"),
            xvecLnB = headsReader.getFloatArray("xvec_ln_b"),
            xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f
        )
    }

    @Before
    fun setup() {
        engineInt8 = createEngine("INT8")
        engineFp32 = createEngine("FP32")

        pipelineInt8 = TTSAudioPipeline(
            engine = engineInt8,
            voiceName = "Trúc Ly",
            quantizationType = OnnxSessionManager.QuantizationType.INT8
        )
        pipelineFp32 = TTSAudioPipeline(
            engine = engineFp32,
            voiceName = "Trúc Ly",
            quantizationType = OnnxSessionManager.QuantizationType.FP32
        )
    }

    @After
    fun teardown() {
        pipelineInt8.release()
        pipelineFp32.release()
        engineInt8.close()
        engineFp32.close()
    }

    @Test
    fun testComputeAdaptiveTargetDurationMs_allProfiles() {
        // 1. INT8 with >= 6 cores (Fast SoCs, e.g. Snapdragon 8 Gen 3) -> 2,000ms
        val int8HighCore = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8,
            remainingChunks = 4,
            startIndex = 0
        )
        assertEquals(2000L, int8HighCore)

        // 2. INT8 with 4-5 cores -> 2,500ms
        val int8MidCore = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 4,
            remainingChunks = 4,
            startIndex = 0
        )
        assertEquals(2500L, int8MidCore)

        // 3. INT8 with < 4 cores -> safe fallback (7,500ms)
        val int8LowCore = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 2,
            remainingChunks = 4,
            startIndex = 0
        )
        assertEquals(7500L, int8LowCore)

        // 4. FP32 mode (any core count) -> safe fallback (7,500ms default)
        val fp32Duration = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.FP32,
            cpuCores = 8,
            remainingChunks = 4,
            startIndex = 0
        )
        assertEquals(7500L, fp32Duration)

        // 5. Custom fallback duration respected
        val fp32CustomFallback = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.FP32,
            cpuCores = 4,
            remainingChunks = 4,
            startIndex = 0,
            safeFallbackDurationMs = 5000L
        )
        assertEquals(5000L, fp32CustomFallback)

        // 6. Resuming or seeking mid-story (startIndex > 0 or remainingChunks <= 1) -> 1,500ms
        val midStorySeek = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8,
            remainingChunks = 3,
            startIndex = 2
        )
        assertEquals(1500L, midStorySeek)

        val singleRemainingChunk = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8,
            remainingChunks = 1,
            startIndex = 0
        )
        assertEquals(1500L, singleRemainingChunk)
    }

    @Test
    fun testInt8Pipeline_immediatePlaybackOnFirstQualifiedChunk() = runBlocking {
        val recordedStates = CopyOnWriteArrayList<TTSAudioPipeline.PipelineState>()
        // 4-sentence story
        val text = "Trời hôm nay nhiều mây và có thể có mưa rào. Gió nhẹ thổi qua từng ngọn cây xào xạc. Mọi người đều vội vã trở về nhà sau giờ làm việc. Đêm nay thành phố sẽ chìm trong giấc ngủ yên bình."

        assertEquals(OnnxSessionManager.QuantizationType.INT8, pipelineInt8.quantizationType)

        pipelineInt8.startReading(
            text = text,
            storyId = 5555,
            onStateChange = { state ->
                recordedStates.add(state)
            }
        )

        assertEquals(4, pipelineInt8.totalChunks)

        // Wait until Playing or timeout
        var elapsed = 0
        while (elapsed < 15000 && !recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing }) {
            delay(50)
            elapsed += 50
        }

        assertTrue("INT8 pipeline must reach Playing state quickly",
            recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing })

        // Verify telemetry session recorded INT8 quantization
        val currentSession = TtsMetricsLogger.currentSession
        assertEquals("5555", currentSession.storyId)
        assertEquals("INT8", currentSession.deviceProfile?.quantization)

        pipelineInt8.stop()
    }

    @Test
    fun testFp32Pipeline_enforcesSafeFallbackThreshold() = runBlocking {
        val recordedStates = CopyOnWriteArrayList<TTSAudioPipeline.PipelineState>()
        val text = "Trời hôm nay nhiều mây và có thể có mưa rào. Gió nhẹ thổi qua từng ngọn cây xào xạc. Mọi người đều vội vã trở về nhà sau giờ làm việc. Đêm nay thành phố sẽ chìm trong giấc ngủ yên bình."

        assertEquals(OnnxSessionManager.QuantizationType.FP32, pipelineFp32.quantizationType)
        assertEquals(7500L, pipelineFp32.minPreBufferDurationMs)

        pipelineFp32.startReading(
            text = text,
            storyId = 6666,
            onStateChange = { state ->
                recordedStates.add(state)
            }
        )

        assertEquals(4, pipelineFp32.totalChunks)

        // Wait until Playing or timeout
        var elapsed = 0
        while (elapsed < 18000 && !recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing }) {
            delay(50)
            elapsed += 50
        }

        assertTrue("FP32 pipeline must transition through Synthesizing or Buffering",
            recordedStates.any { it is TTSAudioPipeline.PipelineState.Synthesizing || it is TTSAudioPipeline.PipelineState.Buffering })
        assertTrue("FP32 pipeline must reach Playing state",
            recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing })

        val currentSession = TtsMetricsLogger.currentSession
        assertEquals("6666", currentSession.storyId)
        assertEquals("FP32", currentSession.deviceProfile?.quantization)

        pipelineFp32.stop()
    }
}
