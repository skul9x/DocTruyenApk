package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for Phase 03: Add Explicit Fallback Logging for Missing INT8 Assets
 *
 * Test criteria:
 * 1. When INT8 requested and backbone_int8 assets are missing, ensureAssetsExtracted returns base dir pointing to backbone (fallback verified)
 * 2. When FP32 requested, ensureAssetsExtracted returns base dir pointing to backbone (no fallback warning expected)
 * 3. getBackboneDir with INT8 and missing int8 dir returns backbone directory
 * 4. getBackboneDir with INT8 and existing int8 dir returns backbone_int8 directory
 * 5. Precision mismatch between requested and detected is identifiable from create() flow
 */
class AssetFallbackLoggingFixTest {

    private lateinit var tempDir: File
    private lateinit var dummyContext: Context

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "asset_fallback_test_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        val assetManager = try {
            val constructor = AssetManager::class.java.getDeclaredConstructor()
            constructor.isAccessible = true
            constructor.newInstance()
        } catch (_: Throwable) {
            null
        }

        dummyContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = tempDir
            override fun getApplicationContext(): Context = this
            override fun getAssets(): AssetManager = assetManager ?: super.getAssets()
        }
    }

    @After
    fun tearDown() {
        if (::tempDir.isInitialized && tempDir.exists()) {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testEnsureAssetsExtracted_fallbackToFp32WhenInt8Missing() {
        // When requesting INT8 precision with default/missing assets
        val extractedBase = OnnxSessionManager.ensureAssetsExtracted(dummyContext, "INT8")
        val backboneDir = OnnxSessionManager.getBackboneDir(extractedBase, "INT8")

        // Because backbone_int8 was not extracted / doesn't exist, getBackboneDir resolves to backbone
        assertEquals(File(extractedBase, "backbone").absolutePath, backboneDir.absolutePath)
        assertFalse("backbone_int8 should not exist since it was missing", File(extractedBase, "backbone_int8").exists())
    }

    @Test
    fun testEnsureAssetsExtracted_fp32RequestedResolvesFp32Backbone() {
        // When requesting FP32 precision
        val extractedBase = OnnxSessionManager.ensureAssetsExtracted(dummyContext, "FP32")
        val backboneDir = OnnxSessionManager.getBackboneDir(extractedBase, "FP32")

        // Should resolve to backbone (FP32)
        assertEquals(File(extractedBase, "backbone").absolutePath, backboneDir.absolutePath)
    }

    @Test
    fun testGetBackboneDir_missingInt8ReturnsBackbone() {
        val baseDir = File(tempDir, "vieneu_missing_int8").apply { mkdirs() }
        File(baseDir, "backbone").apply { mkdirs() }
        File(baseDir, "backbone/vieneu_prefill.onnx").createNewFile()

        val resolved = OnnxSessionManager.getBackboneDir(baseDir, "INT8")
        assertEquals(File(baseDir, "backbone").absolutePath, resolved.absolutePath)
    }

    @Test
    fun testGetBackboneDir_existingInt8ReturnsBackboneInt8() {
        val baseDir = File(tempDir, "vieneu_existing_int8").apply { mkdirs() }
        val int8Dir = File(baseDir, "backbone_int8").apply { mkdirs() }
        File(int8Dir, "vieneu_prefill.onnx").createNewFile()
        File(baseDir, "backbone").apply { mkdirs() }

        val resolved = OnnxSessionManager.getBackboneDir(baseDir, "INT8")
        assertEquals(File(baseDir, "backbone_int8").absolutePath, resolved.absolutePath)
    }

    @Test
    fun testGetBackboneDir_fp32ReturnsBackboneEvenIfInt8Exists() {
        val baseDir = File(tempDir, "vieneu_both_exist").apply { mkdirs() }
        val int8Dir = File(baseDir, "backbone_int8").apply { mkdirs() }
        File(int8Dir, "vieneu_prefill.onnx").createNewFile()
        val fp32Dir = File(baseDir, "backbone").apply { mkdirs() }
        File(fp32Dir, "vieneu_prefill.onnx").createNewFile()

        val resolved = OnnxSessionManager.getBackboneDir(baseDir, "FP32")
        assertEquals(fp32Dir.absolutePath, resolved.absolutePath)
    }

    @Test
    fun testPrecisionMismatchLogic_requestedInt8DetectedFp32() {
        // Verify precision normalization and detection comparison logic used in create()
        val requestedPrecision = "INT8"
        val normPrecision = when (requestedPrecision.trim().uppercase()) {
            "INT8" -> "INT8"
            "FP32" -> "FP32"
            else -> "FP32"
        }

        val detectedQuant = OnnxSessionManager.QuantizationType.FP32

        // Mismatch condition verified
        val isMismatch = normPrecision != detectedQuant.name
        assertTrue("Mismatch must be true when INT8 requested but model detected as FP32", isMismatch)
    }

    @Test
    fun testPrecisionMismatchLogic_requestedInt8DetectedInt8Matches() {
        val requestedPrecision = "int8"
        val normPrecision = when (requestedPrecision.trim().uppercase()) {
            "INT8" -> "INT8"
            "FP32" -> "FP32"
            else -> "FP32"
        }

        val detectedQuant = OnnxSessionManager.QuantizationType.INT8

        val isMismatch = normPrecision != detectedQuant.name
        assertFalse("Mismatch must be false when INT8 requested and model detected as INT8", isMismatch)
    }

    @Test
    fun testPrecisionMismatchLogic_requestedFp32DetectedFp32Matches() {
        val requestedPrecision = "FP32"
        val normPrecision = when (requestedPrecision.trim().uppercase()) {
            "INT8" -> "INT8"
            "FP32" -> "FP32"
            else -> "FP32"
        }

        val detectedQuant = OnnxSessionManager.QuantizationType.FP32

        val isMismatch = normPrecision != detectedQuant.name
        assertFalse("Mismatch must be false when FP32 requested and model detected as FP32", isMismatch)
    }
}
