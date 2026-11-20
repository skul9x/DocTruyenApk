package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Phase 02: Dual-Asset Packaging & Runtime Resolution Verification
 *
 * Test criteria:
 * 1. When both backbone and backbone_int8 assets are present, requesting INT8 extracts and selects backbone_int8.
 * 2. When both backbone and backbone_int8 assets are present, requesting FP32 extracts and selects backbone.
 * 3. getBackboneDir resolves to backbone_int8 for INT8 request and backbone for FP32 request in dual-asset environment.
 * 4. INT8 model file path passes quantization detection as QuantizationType.INT8.
 * 5. Switching requested precision from INT8 to FP32 correctly points to the corresponding model directory without state pollution.
 * 6. Ancillary files (config.json, tokenizer.json, vieneu_v3_heads.npz) are excluded from disk extraction to save flash memory.
 * 7. Asset extraction efficiency: avoid re-copying existing intact model assets.
 * 8. Fallback to FP32 if INT8 assets are missing.
 */
class DualAssetExtractionAndDetectionTest {

    private lateinit var tempRoot: File
    private lateinit var assetsRoot: File
    private lateinit var filesRoot: File
    private lateinit var dummyContext: Context

    @Before
    fun setUp() {
        tempRoot = File(System.getProperty("java.io.tmpdir"), "dual_asset_test_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        assetsRoot = File(tempRoot, "mock_assets").apply { mkdirs() }
        filesRoot = File(tempRoot, "mock_files").apply { mkdirs() }

        setupMockAssetTree(assetsRoot)

        dummyContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesRoot
            override fun getApplicationContext(): Context = this
        }

        OnnxSessionManager.assetListProvider = { _, path ->
            val normalizedPath = path.trim().trim('/')
            val target = if (normalizedPath.isEmpty()) assetsRoot else File(assetsRoot, normalizedPath)
            if (!target.exists()) null
            else if (target.isFile) emptyArray()
            else target.list()
        }

        OnnxSessionManager.assetOpenProvider = { _, path ->
            val normalizedPath = path.trim().trim('/')
            val target = File(assetsRoot, normalizedPath)
            if (!target.exists()) {
                throw java.io.FileNotFoundException("Asset not found: $path at ${target.absolutePath}")
            }
            FileInputStream(target)
        }
    }

    @After
    fun tearDown() {
        OnnxSessionManager.assetListProvider = null
        OnnxSessionManager.assetOpenProvider = null
        if (::tempRoot.isInitialized && tempRoot.exists()) {
            tempRoot.deleteRecursively()
        }
    }

    private fun setupMockAssetTree(root: File) {
        val vieneuDir = File(root, "vieneu").apply { mkdirs() }
        val fp32Dir = File(vieneuDir, "backbone").apply { mkdirs() }
        val int8Dir = File(vieneuDir, "backbone_int8").apply { mkdirs() }
        val codecDir = File(vieneuDir, "codec").apply { mkdirs() }

        // Create dummy FP32 model files (standard binary header with "quantization:fp32")
        createMockOnnxFile(File(fp32Dir, "vieneu_prefill.onnx"), "fp32")
        createMockOnnxFile(File(fp32Dir, "vieneu_decode_step.onnx"), "fp32")
        createMockOnnxFile(File(fp32Dir, "vieneu_acoustic_cached.onnx"), "fp32")
        File(fp32Dir, "config.json").writeText("{\"n_vq\": 16, \"hidden_size\": 768, \"precision\": \"fp32\"}")
        File(fp32Dir, "tokenizer.json").writeText("{\"vocab\": {\"hello\": 1}}")
        File(fp32Dir, "vieneu_v3_heads.npz").writeBytes(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))

        // Create dummy INT8 model files (binary header with "quantization:int8")
        createMockOnnxFile(File(int8Dir, "vieneu_prefill.onnx"), "int8")
        createMockOnnxFile(File(int8Dir, "vieneu_decode_step.onnx"), "int8")
        createMockOnnxFile(File(int8Dir, "vieneu_acoustic_cached.onnx"), "int8")
        File(int8Dir, "config.json").writeText("{\"n_vq\": 16, \"hidden_size\": 768, \"precision\": \"int8\"}")
        File(int8Dir, "tokenizer.json").writeText("{\"vocab\": {\"hello\": 1}}")
        File(int8Dir, "vieneu_v3_heads.npz").writeBytes(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))

        // Create common files
        createMockOnnxFile(File(codecDir, "moss_audio_tokenizer_decode_full.onnx"), "fp32")
        File(vieneuDir, "voices_v3_turbo.json").writeText("{\"presets\": []}")
    }

    private fun createMockOnnxFile(file: File, quantType: String) {
        val header = "ONNX_MODEL_BINARY_MOCK_METADATA_HEADER"
        val quantMetadata = "quantization=$quantType"
        val padding = "0".repeat(256)
        file.writeBytes((header + "_" + quantMetadata + "_" + padding).toByteArray(Charsets.UTF_8))
    }

    @Test
    fun testEnsureAssetsExtracted_int8RequestedExtractsInt8Backbone() {
        val extractedBase = OnnxSessionManager.ensureAssetsExtracted(dummyContext, "INT8")
        val int8BackboneDir = File(extractedBase, "backbone_int8")
        val fp32BackboneDir = File(extractedBase, "backbone")

        assertTrue("backbone_int8 directory should be extracted", int8BackboneDir.exists())
        assertTrue("vieneu_prefill.onnx in int8 directory should exist", File(int8BackboneDir, "vieneu_prefill.onnx").exists())
        assertTrue("vieneu_decode_step.onnx in int8 directory should exist", File(int8BackboneDir, "vieneu_decode_step.onnx").exists())
        assertTrue("vieneu_acoustic_cached.onnx in int8 directory should exist", File(int8BackboneDir, "vieneu_acoustic_cached.onnx").exists())

        // Ancillary files in int8 are excluded from disk extraction (loaded directly from assets)
        assertFalse("config.json in int8 directory should not exist (loaded from assets)", File(int8BackboneDir, "config.json").exists())
        assertFalse("tokenizer.json in int8 directory should not exist (loaded from assets)", File(int8BackboneDir, "tokenizer.json").exists())
        assertFalse("vieneu_v3_heads.npz in int8 directory should not exist (loaded from assets)", File(int8BackboneDir, "vieneu_v3_heads.npz").exists())

        // Common files
        assertTrue("codec directory should exist", File(extractedBase, "codec").exists())
        assertTrue("moss_audio_tokenizer_decode_full.onnx should exist", File(extractedBase, "codec/moss_audio_tokenizer_decode_full.onnx").exists())

        // Resolution
        val resolvedDir = OnnxSessionManager.getBackboneDir(extractedBase, "INT8")
        assertEquals(int8BackboneDir.absolutePath, resolvedDir.absolutePath)
        assertEquals(OnnxSessionManager.QuantizationType.INT8, OnnxSessionManager.detectQuantizationType(File(resolvedDir, "vieneu_prefill.onnx").absolutePath))
    }

    @Test
    fun testEnsureAssetsExtracted_fp32RequestedExtractsFp32BackboneEvenIfInt8AssetsExist() {
        val extractedBase = OnnxSessionManager.ensureAssetsExtracted(dummyContext, "FP32")
        val fp32BackboneDir = File(extractedBase, "backbone")

        assertTrue("backbone directory should be extracted", fp32BackboneDir.exists())
        assertTrue("vieneu_prefill.onnx in backbone directory should exist", File(fp32BackboneDir, "vieneu_prefill.onnx").exists())
        assertTrue("vieneu_decode_step.onnx in backbone directory should exist", File(fp32BackboneDir, "vieneu_decode_step.onnx").exists())
        assertTrue("vieneu_acoustic_cached.onnx in backbone directory should exist", File(fp32BackboneDir, "vieneu_acoustic_cached.onnx").exists())

        // Ancillary files in fp32 are excluded from disk extraction (loaded directly from assets)
        assertFalse("config.json in backbone directory should not exist (loaded from assets)", File(fp32BackboneDir, "config.json").exists())
        assertFalse("tokenizer.json in backbone directory should not exist (loaded from assets)", File(fp32BackboneDir, "tokenizer.json").exists())
        assertFalse("vieneu_v3_heads.npz in backbone directory should not exist (loaded from assets)", File(fp32BackboneDir, "vieneu_v3_heads.npz").exists())

        // Resolution
        val resolvedDir = OnnxSessionManager.getBackboneDir(extractedBase, "FP32")
        assertEquals(fp32BackboneDir.absolutePath, resolvedDir.absolutePath)
        assertEquals(OnnxSessionManager.QuantizationType.FP32, OnnxSessionManager.detectQuantizationType(File(resolvedDir, "vieneu_prefill.onnx").absolutePath))
    }

    @Test
    fun testDynamicPrecisionSwitching_dualAssetCoexistenceWithoutCollision() {
        // Step 1: Initial extraction as INT8
        val base1 = OnnxSessionManager.ensureAssetsExtracted(dummyContext, "INT8")
        val int8Dir = OnnxSessionManager.getBackboneDir(base1, "INT8")
        assertEquals(File(base1, "backbone_int8").absolutePath, int8Dir.absolutePath)
        assertEquals(OnnxSessionManager.QuantizationType.INT8, OnnxSessionManager.detectQuantizationType(File(int8Dir, "vieneu_prefill.onnx").absolutePath))

        // Step 2: Switch to FP32 (extracts FP32 backbone into same base without clobbering int8)
        val base2 = OnnxSessionManager.ensureAssetsExtracted(dummyContext, "FP32")
        val fp32Dir = OnnxSessionManager.getBackboneDir(base2, "FP32")
        assertEquals(File(base2, "backbone").absolutePath, fp32Dir.absolutePath)
        assertEquals(OnnxSessionManager.QuantizationType.FP32, OnnxSessionManager.detectQuantizationType(File(fp32Dir, "vieneu_prefill.onnx").absolutePath))

        // Both directories must coexist cleanly
        assertTrue("Both backbone and backbone_int8 must coexist", File(base2, "backbone").exists() && File(base2, "backbone_int8").exists())

        // Step 3: Switch back to INT8 (should resolve to backbone_int8 without state pollution)
        val base3 = OnnxSessionManager.ensureAssetsExtracted(dummyContext, "INT8")
        val reSelectedInt8 = OnnxSessionManager.getBackboneDir(base3, "INT8")
        assertEquals(File(base3, "backbone_int8").absolutePath, reSelectedInt8.absolutePath)
        assertEquals(OnnxSessionManager.QuantizationType.INT8, OnnxSessionManager.detectQuantizationType(File(reSelectedInt8, "vieneu_prefill.onnx").absolutePath))

        // FP32 request still resolves to backbone
        val reSelectedFp32 = OnnxSessionManager.getBackboneDir(base3, "FP32")
        assertEquals(File(base3, "backbone").absolutePath, reSelectedFp32.absolutePath)
        assertEquals(OnnxSessionManager.QuantizationType.FP32, OnnxSessionManager.detectQuantizationType(File(reSelectedFp32, "vieneu_prefill.onnx").absolutePath))
    }

    @Test
    fun testExtractionEfficiency_doesNotOverwriteExistingIntactFiles() {
        // Extract once
        val base = OnnxSessionManager.ensureAssetsExtracted(dummyContext, "INT8")
        val int8Prefill = File(base, "backbone_int8/vieneu_prefill.onnx")
        assertTrue(int8Prefill.exists())

        // Write a marker timestamp/length or touch
        val customMarker = "CUSTOM_MODIFIED_CONTENT_TEST"
        int8Prefill.writeText(customMarker)

        // Run ensureAssetsExtracted again
        OnnxSessionManager.ensureAssetsExtracted(dummyContext, "INT8")

        // Because file already exists and length > 0, it shouldn't be overwritten
        assertEquals("File should not be overwritten on repeated extraction", customMarker, int8Prefill.readText())
    }

    @Test
    fun testFallbackToFp32_whenInt8AssetsMissing() {
        // Delete INT8 assets from mock tree
        File(assetsRoot, "vieneu/backbone_int8").deleteRecursively()

        val extractedBase = OnnxSessionManager.ensureAssetsExtracted(dummyContext, "INT8")
        val resolvedDir = OnnxSessionManager.getBackboneDir(extractedBase, "INT8")

        // Should fall back to backbone (FP32)
        assertEquals(File(extractedBase, "backbone").absolutePath, resolvedDir.absolutePath)
        assertTrue(File(extractedBase, "backbone/vieneu_prefill.onnx").exists())
        assertEquals(OnnxSessionManager.QuantizationType.FP32, OnnxSessionManager.detectQuantizationType(File(resolvedDir, "vieneu_prefill.onnx").absolutePath))
    }
}
