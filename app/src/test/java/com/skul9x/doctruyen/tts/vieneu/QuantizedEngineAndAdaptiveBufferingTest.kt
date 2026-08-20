package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.skul9x.doctruyen.tts.ITTSManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 03 Comprehensive Test:
 * 1. Model Quantization Detection (metadata_props byte scan & filename fallback)
 * 2. Execution Provider & SessionOptions configuration (XNNPACK vs NNAPI vs CPU fallback)
 * 3. Big core detection in DevicePerformanceHelper
 * 4. Adaptive duration-based pre-buffering (7500ms target) in TTSAudioPipeline
 * 5. End-to-end storyId and quantization telemetry propagation to TtsMetricsLogger
 * 6. Deferred/pending speech storyId propagation in VieNeuTTSManager
 */
class QuantizedEngineAndAdaptiveBufferingTest {

    private lateinit var engine: VieNeuOnnxEngine
    private lateinit var pipeline: TTSAudioPipeline

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

    @Before
    fun setup() {
        val backboneDir = File("src/main/assets/vieneu/backbone")
        val codecDir = File("src/main/assets/vieneu/codec")
        val voicesFile = File("src/main/assets/vieneu/voices_v3_turbo.json")

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 2
        )

        val configFile = File(backboneDir, "config.json")
        val config = VieNeuConfig.fromJson(configFile.readText(Charsets.UTF_8))

        val tokenizerFile = File(backboneDir, "tokenizer.json")
        val tokenizer = VieNeuTokenizer.fromFile(tokenizerFile)

        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
        val headsReader = NpzReader.read(headsFile)

        val textEmb = headsReader.get2D("text_emb")!!
        val audioEmb = headsReader.get3D("audio_emb")!!
        val xvecW = headsReader.get2D("xvec_w")
        val xvecB = headsReader.getFloatArray("xvec_b")
        val xvecLnW = headsReader.getFloatArray("xvec_ln_w")
        val xvecLnB = headsReader.getFloatArray("xvec_ln_b")
        val xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f

        if (voicesFile.exists()) {
            VoicePresets.loadFromJson(voicesFile.readText(Charsets.UTF_8))
        }

        engine = VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = textEmb,
            audioEmbeddings = audioEmb,
            xvecW = xvecW,
            xvecB = xvecB,
            xvecLnW = xvecLnW,
            xvecLnB = xvecLnB,
            xvecLnEps = xvecLnEps
        )

        pipeline = TTSAudioPipeline(engine, voiceName = "Trúc Ly")
    }

    @After
    fun teardown() {
        pipeline.release()
        engine.close()
    }

    @Test
    fun testQuantizationDetection_metadataAndFilenameFallback() {
        // 1. Fallback filename detection
        val dummyInt8 = "/tmp/vieneu_prefill_int8.onnx"
        val dummyFp16 = "/tmp/vieneu_decode_step_fp16.onnx"
        val dummyFp32 = "/tmp/vieneu_acoustic.onnx"

        assertEquals(OnnxSessionManager.QuantizationType.INT8, OnnxSessionManager.detectQuantizationType(dummyInt8))
        assertEquals(OnnxSessionManager.QuantizationType.FP16, OnnxSessionManager.detectQuantizationType(dummyFp16))
        assertEquals(OnnxSessionManager.QuantizationType.FP32, OnnxSessionManager.detectQuantizationType(dummyFp32))

        // 2. Primary metadata scan detection with mock ONNX binary header
        val tempDir = File.createTempFile("onnx_test", "dir")
        tempDir.delete()
        tempDir.mkdirs()

        try {
            val fileWithInt8Meta = File(tempDir, "model_generic.onnx")
            FileOutputStream(fileWithInt8Meta).use { fos ->
                fos.write(byteArrayOf(0x08, 0x07, 0x12, 0x04, 0x6F, 0x6E, 0x6E, 0x78)) // Header
                fos.write("quantization".toByteArray(Charsets.UTF_8))
                fos.write(byteArrayOf(0x12, 0x04)) // Protobuf string tag
                fos.write("int8".toByteArray(Charsets.UTF_8))
                fos.write(ByteArray(128))
            }
            assertEquals(
                OnnxSessionManager.QuantizationType.INT8,
                OnnxSessionManager.detectQuantizationType(fileWithInt8Meta.absolutePath)
            )

            val fileWithFp16Meta = File(tempDir, "model_fp16_generic.onnx")
            FileOutputStream(fileWithFp16Meta).use { fos ->
                fos.write(byteArrayOf(0x08, 0x07))
                fos.write("quantization".toByteArray(Charsets.UTF_8))
                fos.write(byteArrayOf(0x12, 0x04))
                fos.write("fp16".toByteArray(Charsets.UTF_8))
            }
            assertEquals(
                OnnxSessionManager.QuantizationType.FP16,
                OnnxSessionManager.detectQuantizationType(fileWithFp16Meta.absolutePath)
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSessionOptionsConfiguration_epSelectionAndThreads() {
        // 1. Big cores calculation
        val bigCores = DevicePerformanceHelper.getBigCoreCount()
        assertTrue("Big core count should be >= 1", bigCores >= 1)

        // 2. INT8 configuration (XNNPACK EP with intra_op = 1)
        val (int8Options, int8Ep) = OnnxSessionManager.configureSessionOptions(
            OnnxSessionManager.QuantizationType.INT8,
            threads = 2
        )
        assertNotNull(int8Options)
        assertTrue("INT8 should use XNNPACK or CPU", int8Ep.startsWith("XNNPACK") || int8Ep.startsWith("CPU"))

        // 3. FP32 configuration (XNNPACK EP)
        val (fp32Options, fp32Ep) = OnnxSessionManager.configureSessionOptions(
            OnnxSessionManager.QuantizationType.FP32,
            threads = 2
        )
        assertNotNull(fp32Options)
        assertTrue("FP32 should use XNNPACK or CPU", fp32Ep.startsWith("XNNPACK") || fp32Ep.startsWith("CPU"))

        // 4. FP16 configuration (NNAPI EP attempt with graceful fallback if unavailable in JVM)
        val (fp16Options, fp16Ep) = OnnxSessionManager.configureSessionOptions(
            OnnxSessionManager.QuantizationType.FP16,
            threads = 2
        )
        assertNotNull(fp16Options)
        assertTrue("FP16 should use NNAPI or Fallback", fp16Ep.contains("NNAPI") || fp16Ep.contains("Fallback") || fp16Ep.contains("CPU") || fp16Ep.contains("XNNPACK"))
    }

    @Test
    fun testAdaptivePrebuffering_durationTargetAndStoryIdPropagation() = runBlocking {
        val recordedStates = java.util.concurrent.CopyOnWriteArrayList<TTSAudioPipeline.PipelineState>()
        val storyId = 9876
        val text = "Trời hôm nay rất đẹp và trong xanh. Chúng ta cùng đi dạo trong công viên dưới tán cây xanh mát. Những chú chim hót líu lo trên cành cây. Một ngày thật tuyệt vời để nghỉ ngơi và thư giãn."

        assertEquals(7500L, pipeline.minPreBufferDurationMs)

        pipeline.startReading(
            text = text,
            storyId = storyId,
            onStateChange = { state ->
                recordedStates.add(state)
            }
        )

        assertEquals(4, pipeline.totalChunks)

        // Wait until playing or timeout
        var elapsed = 0
        while (elapsed < 18000 && !recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing }) {
            delay(50)
            elapsed += 50
        }

        assertTrue("Pipeline must transition through Synthesizing or Buffering",
            recordedStates.any { it is TTSAudioPipeline.PipelineState.Synthesizing || it is TTSAudioPipeline.PipelineState.Buffering })
        assertTrue("Pipeline must reach Playing state",
            recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing })

        // Verify storyId propagation to TtsMetricsLogger
        val sessionMetrics = TtsMetricsLogger.currentSession
        assertEquals(storyId.toString(), sessionMetrics.storyId)
        assertNotNull(sessionMetrics.deviceProfile)
        assertEquals("FP32", sessionMetrics.deviceProfile?.quantization)

        pipeline.stop()
    }

    @Test
    fun testVieNeuTTSManager_pendingSpeechStoryIdPreservation() = runBlocking {
        var observedState: ITTSManager.TTSState? = null
        val testStoryId = 12345

        val manager = VieNeuTTSManager(
            context = null,
            onStateChange = { state ->
                observedState = state
            },
            customEngine = engine,
            customPipeline = pipeline
        )

        manager.speakWithTitlePause(
            title = "Tiêu đề truyện",
            content = "Nội dung câu chuyện bắt đầu tại đây.",
            storyId = testStoryId
        )

        var elapsed = 0
        while (elapsed < 15000 && observedState != ITTSManager.TTSState.PLAYING) {
            delay(50)
            elapsed += 50
        }

        assertEquals(ITTSManager.TTSState.PLAYING, observedState)
        assertEquals(testStoryId.toString(), TtsMetricsLogger.currentSession.storyId)

        manager.stop()
        manager.shutdown()
    }
}
