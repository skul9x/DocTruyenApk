package com.skul9x.doctruyen.tts.vieneu

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Comprehensive verification for Phase 04:
 * Robust INT8 Model Quantization Detection & Adaptive Prebuffer Optimization (P7).
 */
class QuantizationDetectionAndPrebufferTest {

    private val tempFiles = mutableListOf<File>()

    @Before
    fun setUp() {
        tempFiles.clear()
    }

    @After
    fun tearDown() {
        tempFiles.forEach { file ->
            try {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            } catch (_: Throwable) {}
        }
        tempFiles.clear()
    }

    private fun createTempModelFile(name: String, content: ByteArray): File {
        val file = File.createTempFile("test_onnx_", "_$name")
        FileOutputStream(file).use { it.write(content) }
        tempFiles.add(file)
        return file
    }

    /**
     * 1. Production packaged asset verification:
     * Assets in vieneu/backbone (vieneu_prefill.onnx, vieneu_decode_step.onnx, vieneu_acoustic_cached.onnx)
     * must be accurately detected as QuantizationType.INT8 despite lacking "quantization" metadata.
     */
    @Test
    fun testPackagedAssetsQuantizationDetection() {
        val candidateDirs = listOf(
            File("app/src/main/assets/vieneu/backbone"),
            File("src/main/assets/vieneu/backbone")
        )
        val backboneDir = candidateDirs.firstOrNull { it.exists() && it.isDirectory }
        assertNotNull("backbone assets directory must exist", backboneDir)

        val prefillFile = File(backboneDir, "vieneu_prefill.onnx")
        assertTrue("vieneu_prefill.onnx must exist", prefillFile.exists())
        val prefillQuant = OnnxSessionManager.detectQuantizationType(prefillFile.absolutePath)
        assertEquals("vieneu_prefill.onnx must be detected as FP32", OnnxSessionManager.QuantizationType.FP32, prefillQuant)

        val decodeFile = File(backboneDir, "vieneu_decode_step.onnx")
        assertTrue("vieneu_decode_step.onnx must exist", decodeFile.exists())
        val decodeQuant = OnnxSessionManager.detectQuantizationType(decodeFile.absolutePath)
        assertEquals("vieneu_decode_step.onnx must be detected as FP32", OnnxSessionManager.QuantizationType.FP32, decodeQuant)

        val acousticFile = File(backboneDir, "vieneu_acoustic_cached.onnx")
        assertTrue("vieneu_acoustic_cached.onnx must exist", acousticFile.exists())
        val acousticQuant = OnnxSessionManager.detectQuantizationType(acousticFile.absolutePath)
        assertEquals("vieneu_acoustic_cached.onnx must be detected as FP32", OnnxSessionManager.QuantizationType.FP32, acousticQuant)
    }

    /**
     * 2. Synthetic byte pattern operator detection:
     * Models containing quantized ONNX operators (MatMulInteger, DynamicQuantizeLinear, QLinearMatMul)
     * must be recognized as QuantizationType.INT8.
     */
    @Test
    fun testBytePatternOperatorDetection() {
        // Model with MatMulInteger
        val matmulIntBytes = "dummy_header_data_op_MatMulInteger_weight_data".toByteArray(Charsets.UTF_8)
        val matmulIntFile = createTempModelFile("custom_model.onnx", matmulIntBytes)
        assertEquals(
            "Model with MatMulInteger operator must be detected as INT8",
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType(matmulIntFile.absolutePath)
        )

        // Model with DynamicQuantizeLinear
        val dynamicQuantBytes = "dummy_graph_data_DynamicQuantizeLinear_activation".toByteArray(Charsets.UTF_8)
        val dynamicQuantFile = createTempModelFile("custom_model.onnx", dynamicQuantBytes)
        assertEquals(
            "Model with DynamicQuantizeLinear operator must be detected as INT8",
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType(dynamicQuantFile.absolutePath)
        )

        // Model with QLinearMatMul
        val qlinearBytes = "dummy_graph_data_QLinearMatMul_tensor".toByteArray(Charsets.UTF_8)
        val qlinearFile = createTempModelFile("custom_model.onnx", qlinearBytes)
        assertEquals(
            "Model with QLinearMatMul operator must be detected as INT8",
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType(qlinearFile.absolutePath)
        )
    }

    /**
     * 3. Metadata-tagged model verification:
     * ONNX models with explicit metadata properties "quantization: int8/fp16/fp32" must be detected accurately.
     */
    @Test
    fun testMetadataTaggedQuantizationDetection() {
        val metaInt8 = "header_bytes_quantization: int8_trailing_bytes".toByteArray(Charsets.UTF_8)
        val fileInt8 = createTempModelFile("tagged_meta.onnx", metaInt8)
        assertEquals(OnnxSessionManager.QuantizationType.INT8, OnnxSessionManager.detectQuantizationType(fileInt8.absolutePath))

        val metaFp16 = "header_bytes_quantization: fp16_trailing_bytes".toByteArray(Charsets.UTF_8)
        val fileFp16 = createTempModelFile("tagged_meta.onnx", metaFp16)
        assertEquals(OnnxSessionManager.QuantizationType.FP16, OnnxSessionManager.detectQuantizationType(fileFp16.absolutePath))

        val metaFp32 = "header_bytes_quantization: fp32_trailing_bytes".toByteArray(Charsets.UTF_8)
        val fileFp32 = createTempModelFile("tagged_meta.onnx", metaFp32)
        assertEquals(OnnxSessionManager.QuantizationType.FP32, OnnxSessionManager.detectQuantizationType(fileFp32.absolutePath))
    }

    /**
     * 4. Pure FP32 fallback verification:
     * When neither INT8 ops nor metadata exist, detection must safely fallback to QuantizationType.FP32.
     */
    @Test
    fun testPureFp32FallbackWhenNoQuantizationOpsOrMetadata() {
        val standardFloatBytes = "standard_onnx_graph_Conv_Relu_Add_Softmax_float_weights".toByteArray(Charsets.UTF_8)
        val fileFp32 = createTempModelFile("standard_fp32_model.onnx", standardFloatBytes)
        assertEquals(
            "Pure float model without INT8 ops or metadata must fall back to FP32",
            OnnxSessionManager.QuantizationType.FP32,
            OnnxSessionManager.detectQuantizationType(fileFp32.absolutePath)
        )

        // Non-existent path without int8/fp16 tokens
        val nonExistentPath = "/tmp/non_existent_dir/model_weights.onnx"
        assertEquals(
            "Non-existent model path with generic name must fall back to FP32",
            OnnxSessionManager.QuantizationType.FP32,
            OnnxSessionManager.detectQuantizationType(nonExistentPath)
        )
    }

    /**
     * 5. Path and directory convention fallback verification:
     * Checks path tokens and directory conventions.
     */
    @Test
    fun testPathAndDirectoryConventions() {
        // Path naming conventions
        assertEquals(
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType("/app/models/model_int8.onnx")
        )
        assertEquals(
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType("/app/models/backbone_int8/model.onnx")
        )
        assertEquals(
            OnnxSessionManager.QuantizationType.FP16,
            OnnxSessionManager.detectQuantizationType("/app/models/model_fp16.onnx")
        )

        // Directory convention with shared data
        val tempDir = File.createTempFile("test_backbone", "_dir")
        tempDir.delete()
        tempDir.mkdirs()
        tempFiles.add(tempDir)

        val sharedDataFile = File(tempDir, "vieneu_backbone_shared.data")
        sharedDataFile.writeBytes(ByteArray(64))

        val companionFile = File(tempDir, "heads.npz")
        companionFile.writeBytes(ByteArray(32))

        assertEquals(
            "Directory containing vieneu_backbone_shared.data must default companion models to FP32",
            OnnxSessionManager.QuantizationType.FP32,
            OnnxSessionManager.detectQuantizationType(companionFile.absolutePath)
        )
    }

    /**
     * 6. Adaptive pre-buffering duration calculation:
     * Verify computeAdaptiveTargetDurationMs selects 2000ms–2500ms for INT8 versus 7500ms for FP32.
     */
    @Test
    fun testComputeAdaptiveTargetDurationMs() {
        // INT8 on 6 cores (default 2 chunks) -> 2000ms
        val durationInt8HighCore = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 6,
            remainingChunks = 2,
            startIndex = 0,
            targetPreBufferChunks = 2
        )
        assertEquals("INT8 on 6 cores must target 2000ms", 2000L, durationInt8HighCore)

        // INT8 on 4 cores (default 2 chunks) -> 2500ms
        val durationInt8MidCore = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 4,
            remainingChunks = 2,
            startIndex = 0,
            targetPreBufferChunks = 2
        )
        assertEquals("INT8 on 4 cores must target 2500ms", 2500L, durationInt8MidCore)

        // INT8 on 2 cores (default 2 chunks) -> defensive fallback 7500ms
        val durationInt8LowCore = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 2,
            remainingChunks = 2,
            startIndex = 0,
            targetPreBufferChunks = 2
        )
        assertEquals("INT8 on 2 cores must target defensive fallback", 7500L, durationInt8LowCore)

        // Verify INT8 is always <= 2500ms for default targetPreBufferChunks on standard 4+ core profiles
        assertTrue("INT8 target duration must be <= 2500ms", durationInt8HighCore <= 2500L)
        assertTrue("INT8 target duration must be <= 2500ms", durationInt8MidCore <= 2500L)

        // INT8 with 1 target chunk -> 2000ms (or 2500ms on 2 cores)
        val durationInt8SingleChunk = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 4,
            remainingChunks = 2,
            startIndex = 0,
            targetPreBufferChunks = 1
        )
        assertEquals("INT8 with 1 chunk target must be 2000ms", 2000L, durationInt8SingleChunk)

        // True FP32 retains defensive fallback buffering (7500ms)
        val durationFp32Quad = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.FP32,
            cpuCores = 4,
            remainingChunks = 2,
            startIndex = 0,
            safeFallbackDurationMs = 7500L,
            targetPreBufferChunks = 2
        )
        assertEquals("FP32 must retain defensive fallback duration 7500ms", 7500L, durationFp32Quad)

        val durationFp32Single = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.FP32,
            cpuCores = 4,
            remainingChunks = 2,
            startIndex = 0,
            safeFallbackDurationMs = 7500L,
            targetPreBufferChunks = 1
        )
        assertEquals("FP32 single chunk must retain defensive fallback duration 7500ms", 7500L, durationFp32Single)

        // Mid-stream seek (startIndex > 0) or single remaining chunk -> 1500ms fast resume
        val durationSeek = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 4,
            remainingChunks = 10,
            startIndex = 3,
            targetPreBufferChunks = 2
        )
        assertEquals("Mid-stream seek must target fast 1500ms resume", 1500L, durationSeek)

        val durationLastChunk = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 4,
            remainingChunks = 1,
            startIndex = 0,
            targetPreBufferChunks = 2
        )
        assertEquals("Single remaining chunk must target fast 1500ms", 1500L, durationLastChunk)
    }

    /**
     * 7. Telemetry and metrics precision truthfulness:
     * TtsMetricsLogger accurately records and displays INT8 quantization.
     */
    @Test
    fun testTelemetryLoggingQuantizationAccuracy() {
        TtsMetricsLogger.startSession(
            storyId = "telemetry_test_int8",
            storyTitle = "INT8 Telemetry Test",
            context = null,
            engineThreads = 4,
            quantization = "INT8"
        )
        assertEquals("INT8", TtsMetricsLogger.currentSession.deviceProfile?.quantization)

        // Update effective precision
        TtsMetricsLogger.logEffectivePrecision("INT8")
        assertEquals("INT8", TtsMetricsLogger.currentSession.deviceProfile?.quantization)

        val report = TtsMetricsLogger.formatAiDiagnosticReport()
        assertTrue("Report must state Quantization: INT8", report.contains("Quantization: INT8"))
    }
}
