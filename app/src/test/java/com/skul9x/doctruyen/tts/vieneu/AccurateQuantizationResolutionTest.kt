package com.skul9x.doctruyen.tts.vieneu

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Comprehensive verification for Phase 02:
 * Robust Quantization Detection & Defensive Prebuffer Margin Restoration (AUD-002).
 *
 * Verifies:
 * 1. `detectQuantizationType` returns `FP32` on files within `src/main/assets/vieneu/backbone`
 *    despite the presence of `vieneu_backbone_shared.data`.
 * 2. `detectQuantizationType` returns `INT8` on files within `src/main/assets/vieneu/backbone_int8`
 *    or standalone files containing quantized ONNX operators (MatMulInteger, DynamicQuantizeLinear, QLinearMatMul).
 * 3. `OnnxSessionManager.createFromFiles` with precision = "FP32" instantiates an engine with FP32 quantizationType,
 *    and with precision = "INT8" instantiates an engine with INT8 quantizationType.
 * 4. `TTSAudioPipeline.computeAdaptiveTargetDurationMs` outputs >= 7500ms for FP32 and <= 2500ms for INT8
 *    under identical CPU core counts.
 */
class AccurateQuantizationResolutionTest {

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

    private fun createTempFile(name: String, content: ByteArray): File {
        val file = File.createTempFile("test_quant_", "_$name")
        FileOutputStream(file).use { it.write(content) }
        tempFiles.add(file)
        return file
    }

    /**
     * Requirement 1:
     * `detectQuantizationType` must return `QuantizationType.FP32` on files within
     * `src/main/assets/vieneu/backbone` despite the presence of `vieneu_backbone_shared.data`.
     */
    @Test
    fun testFp32DetectionInBackboneDespiteSharedData() {
        val candidateDirs = listOf(
            File("app/src/main/assets/vieneu/backbone"),
            File("src/main/assets/vieneu/backbone")
        )
        val backboneDir = candidateDirs.firstOrNull { it.exists() && it.isDirectory }
        assertNotNull("backbone assets directory must exist in workspace", backboneDir)

        // Verify shared data file is present
        val sharedDataFile = File(backboneDir, "vieneu_backbone_shared.data")
        assertTrue("vieneu_backbone_shared.data must exist in backbone dir", sharedDataFile.exists())

        // Verify prefill, decode, and acoustic models are detected as FP32
        val prefillFile = File(backboneDir, "vieneu_prefill.onnx")
        assertTrue("vieneu_prefill.onnx must exist", prefillFile.exists())
        val prefillQuant = OnnxSessionManager.detectQuantizationType(prefillFile.absolutePath)
        assertEquals("vieneu_prefill.onnx in backbone must be detected as FP32",
            OnnxSessionManager.QuantizationType.FP32, prefillQuant)

        val decodeFile = File(backboneDir, "vieneu_decode_step.onnx")
        assertTrue("vieneu_decode_step.onnx must exist", decodeFile.exists())
        val decodeQuant = OnnxSessionManager.detectQuantizationType(decodeFile.absolutePath)
        assertEquals("vieneu_decode_step.onnx in backbone must be detected as FP32",
            OnnxSessionManager.QuantizationType.FP32, decodeQuant)

        val acousticFile = File(backboneDir, "vieneu_acoustic_cached.onnx")
        assertTrue("vieneu_acoustic_cached.onnx must exist", acousticFile.exists())
        val acousticQuant = OnnxSessionManager.detectQuantizationType(acousticFile.absolutePath)
        assertEquals("vieneu_acoustic_cached.onnx in backbone must be detected as FP32",
            OnnxSessionManager.QuantizationType.FP32, acousticQuant)

        // Verify synthetic mock directory named 'backbone' with shared data
        val tempBackbone = File.createTempFile("test_backbone", "_dir")
        tempBackbone.delete()
        tempBackbone.mkdirs()
        tempFiles.add(tempBackbone)

        val mockSharedData = File(tempBackbone, "vieneu_backbone_shared.data")
        mockSharedData.writeBytes(ByteArray(1024))

        val mockModel = File(tempBackbone, "custom_model.onnx")
        mockModel.writeBytes("dummy_graph_without_int8_naming".toByteArray(Charsets.UTF_8))

        val detectedMock = OnnxSessionManager.detectQuantizationType(mockModel.absolutePath)
        assertEquals("Presence of vieneu_backbone_shared.data must NOT falsely force INT8 in backbone",
            OnnxSessionManager.QuantizationType.FP32, detectedMock)
    }

    /**
     * Requirement 2:
     * `detectQuantizationType` returns `INT8` on files within `backbone_int8` or containing int8 operators.
     */
    @Test
    fun testInt8DetectionViaDirectoryAndOperators() {
        // 1. Directory convention with backbone_int8
        assertEquals(
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType("/data/models/backbone_int8/vieneu_prefill.onnx")
        )
        assertEquals(
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType("/app/src/main/assets/vieneu/backbone_int8/model.onnx")
        )

        // 2. Explicit filename naming conventions
        assertEquals(
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType("/data/models/backbone/model_int8.onnx")
        )

        // 3. Standalone files with deep quantized ONNX operators (MatMulInteger, DynamicQuantizeLinear, QLinearMatMul)
        val matmulIntFile = createTempFile("op_matmul.onnx",
            "dummy_header_op_MatMulInteger_tensor_data".toByteArray(Charsets.UTF_8))
        assertEquals("Standalone model with MatMulInteger must be detected as INT8",
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType(matmulIntFile.absolutePath))

        val dynamicQuantFile = createTempFile("op_dynamic.onnx",
            "dummy_header_DynamicQuantizeLinear_weights".toByteArray(Charsets.UTF_8))
        assertEquals("Standalone model with DynamicQuantizeLinear must be detected as INT8",
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType(dynamicQuantFile.absolutePath))

        val qlinearFile = createTempFile("op_qlinear.onnx",
            "dummy_header_QLinearMatMul_activation".toByteArray(Charsets.UTF_8))
        assertEquals("Standalone model with QLinearMatMul must be detected as INT8",
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType(qlinearFile.absolutePath))

        // 4. Standalone files with explicit metadata "quantization: int8"
        val metaInt8File = createTempFile("meta_int8.onnx",
            "onnx_header_quantization: int8_trailer".toByteArray(Charsets.UTF_8))
        assertEquals("Standalone model with metadata quantization: int8 must be detected as INT8",
            OnnxSessionManager.QuantizationType.INT8,
            OnnxSessionManager.detectQuantizationType(metaInt8File.absolutePath))
    }

    /**
     * Requirement 3:
     * `OnnxSessionManager.createFromFiles` with precision = "FP32" resolves to `QuantizationType.FP32`,
     * and with precision = "INT8" resolves to `QuantizationType.INT8`.
     */
    @Test
    fun testCreateFromFilesPrecisionResolution() {
        val rootDirs = listOf(
            File("app/src/main/assets/vieneu"),
            File("src/main/assets/vieneu")
        )
        val baseAssetsDir = rootDirs.firstOrNull { it.exists() }
        assertNotNull("assets/vieneu directory must exist", baseAssetsDir)

        val backboneFp32Dir = File(baseAssetsDir, "backbone")
        val codecDir = File(baseAssetsDir, "codec")
        val backboneInt8Dir = File(baseAssetsDir, "backbone_int8")

        // Populate backbone_int8 if absent for unit test execution
        var createdTempInt8Dir = false
        if (!backboneInt8Dir.exists()) {
            backboneInt8Dir.mkdirs()
            backboneFp32Dir.listFiles()?.forEach { file ->
                file.copyTo(File(backboneInt8Dir, file.name), overwrite = true)
            }
            createdTempInt8Dir = true
        }

        try {
            // 1. FP32 session creation
            val sessionFp32 = OnnxSessionManager.createFromFiles(
                backboneDir = backboneFp32Dir,
                codecDir = codecDir,
                threads = 2,
                precision = "FP32"
            )
            assertNotNull("FP32 session manager must be instantiated", sessionFp32)
            assertEquals("createFromFiles with precision = FP32 must strictly yield QuantizationType.FP32",
                OnnxSessionManager.QuantizationType.FP32, sessionFp32.quantizationType)
            sessionFp32.close()

            // 2. INT8 session creation
            val sessionInt8 = OnnxSessionManager.createFromFiles(
                backboneDir = backboneInt8Dir,
                codecDir = codecDir,
                threads = 2,
                precision = "INT8"
            )
            assertNotNull("INT8 session manager must be instantiated", sessionInt8)
            assertEquals("createFromFiles with precision = INT8 must strictly yield QuantizationType.INT8",
                OnnxSessionManager.QuantizationType.INT8, sessionInt8.quantizationType)
            sessionInt8.close()
        } finally {
            if (createdTempInt8Dir && backboneInt8Dir.exists()) {
                backboneInt8Dir.deleteRecursively()
            }
        }
    }

    /**
     * Requirement 4:
     * `TTSAudioPipeline.computeAdaptiveTargetDurationMs` outputs >= 7500ms for FP32
     * and <= 2500ms for INT8 under identical core counts.
     */
    @Test
    fun testAdaptiveTargetDurationMargins() {
        val testCores = listOf(2, 4, 6, 8)

        for (cores in testCores) {
            val durationFp32 = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
                quantizationType = OnnxSessionManager.QuantizationType.FP32,
                cpuCores = cores,
                remainingChunks = 2,
                startIndex = 0,
                safeFallbackDurationMs = 7500L,
                targetPreBufferChunks = 2
            )
            assertTrue("FP32 on $cores cores must require >= 7500ms defensive buffer margin, got $durationFp32 ms",
                durationFp32 >= 7500L)

            val durationInt8 = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
                quantizationType = OnnxSessionManager.QuantizationType.INT8,
                cpuCores = cores,
                remainingChunks = 2,
                startIndex = 0,
                safeFallbackDurationMs = 7500L,
                targetPreBufferChunks = 2
            )

            if (cores >= 4) {
                assertTrue("INT8 on $cores cores must target aggressive <= 2500ms prebuffer, got $durationInt8 ms",
                    durationInt8 <= 2500L)
                assertTrue("FP32 duration ($durationFp32 ms) must exceed INT8 duration ($durationInt8 ms) on $cores cores",
                    durationFp32 > durationInt8)
            }
        }

        // Test single chunk target
        val durationFp32Single = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.FP32,
            cpuCores = 4,
            remainingChunks = 2,
            startIndex = 0,
            safeFallbackDurationMs = 7500L,
            targetPreBufferChunks = 1
        )
        assertEquals("FP32 single chunk must maintain 7500ms defensive prebuffer", 7500L, durationFp32Single)

        val durationInt8Single = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 4,
            remainingChunks = 2,
            startIndex = 0,
            safeFallbackDurationMs = 7500L,
            targetPreBufferChunks = 1
        )
        assertEquals("INT8 single chunk must maintain 2000ms prebuffer", 2000L, durationInt8Single)
    }
}
