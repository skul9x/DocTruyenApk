package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Comprehensive verification for Phase 03:
 * 1. Dead asset removal & TTS asset integrity (>84MB saved)
 * 2. NpzReader streaming zero-BAOS parsing
 * 3. Exact numerical equivalence between flat 1D embeddings and nested 3D arrays
 * 4. ProGuard / R8 keep rules and ABI build configuration coverage
 */
class AssetCleansingAndBuildOptimizationTest {

    private fun resolveFile(path: String): File {
        val f1 = File(path)
        if (f1.exists()) return f1
        val f2 = File("app", path)
        if (f2.exists()) return f2
        return if (path.startsWith("app/")) File(path.removePrefix("app/")) else f1
    }

    @Test
    fun testDeadAssetAbsenceAndIntegrity() {
        val assetBase = resolveFile("src/main/assets/vieneu")
        assertTrue("VieNeu assets directory should exist: ${assetBase.absolutePath}", assetBase.exists())

        // 1. Verify dead assets are completely purged
        val denoiserDir = File(assetBase, "denoiser")
        assertFalse("Dead asset directory 'denoiser' must not exist", denoiserDir.exists())

        val deadDenoiserFile = File(assetBase, "denoiser/denoiser.onnx")
        assertFalse("Dead asset 'denoiser.onnx' must not exist", deadDenoiserFile.exists())

        val deadCodecOnnx = File(assetBase, "codec/moss_audio_tokenizer_encode.onnx")
        assertFalse("Dead asset 'moss_audio_tokenizer_encode.onnx' must not exist", deadCodecOnnx.exists())

        val deadCodecData = File(assetBase, "codec/moss_audio_tokenizer_encode.data")
        assertFalse("Dead asset 'moss_audio_tokenizer_encode.data' must not exist", deadCodecData.exists())

        // 2. Verify all required core TTS assets remain intact and valid
        val requiredAssets = listOf(
            File(assetBase, "sea_g2p.bin"),
            File(assetBase, "voices_v3_turbo.json"),
            File(assetBase, "backbone/vieneu_prefill.onnx"),
            File(assetBase, "backbone/vieneu_decode_step.onnx"),
            File(assetBase, "backbone/vieneu_acoustic_cached.onnx"),
            File(assetBase, "backbone/vieneu_backbone_shared.data"),
            File(assetBase, "backbone/vieneu_v3_heads.npz"),
            File(assetBase, "backbone/config.json"),
            File(assetBase, "backbone/tokenizer.json"),
            File(assetBase, "codec/moss_audio_tokenizer_decode_full.onnx"),
            File(assetBase, "codec/moss_audio_tokenizer_decode_shared.data"),
            File(assetBase, "codec/moss_audio_tokenizer_decode_step.onnx")
        )

        for (asset in requiredAssets) {
            assertTrue("Required asset ${asset.path} must exist", asset.exists())
            assertTrue("Required asset ${asset.path} must not be empty", asset.length() > 0)
        }
    }

    @Test
    fun testNpzReaderZeroBaosAllocation() {
        // Build synthetic NPY archive
        fun createNpyBytes(shape: IntArray, data: FloatArray): ByteArray {
            val baos = ByteArrayOutputStream()
            // Magic \x93NUMPY
            baos.write(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))
            // Major 1, Minor 0
            baos.write(byteArrayOf(1, 0))

            val shapeStr = if (shape.size == 1) "(${shape[0]},)" else "(${shape.joinToString(", ")})"
            val headerContent = "{'descr': '<f4', 'fortran_order': False, 'shape': $shapeStr, }  \n"
            val headerBytes = headerContent.toByteArray(Charsets.US_ASCII)
            val headerLen = headerBytes.size

            val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(headerLen.toShort()).array()
            baos.write(lenBuf)
            baos.write(headerBytes)

            val dataBuf = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (f in data) {
                dataBuf.putFloat(f)
            }
            baos.write(dataBuf.array())
            return baos.toByteArray()
        }

        val zipBaos = ByteArrayOutputStream()
        ZipOutputStream(zipBaos).use { zos ->
            val arr3DData = FloatArray(16 * 10 * 8) { idx -> (idx * 0.05f) }
            val npyBytes = createNpyBytes(intArrayOf(16, 10, 8), arr3DData)
            val entry = ZipEntry("audio_emb.npy")
            entry.size = npyBytes.size.toLong()
            zos.putNextEntry(entry)
            zos.write(npyBytes)
            zos.closeEntry()

            val textData = FloatArray(5 * 8) { idx -> (idx * 1.5f) }
            val textBytes = createNpyBytes(intArrayOf(5, 8), textData)
            val textEntry = ZipEntry("text_emb.npy")
            textEntry.size = textBytes.size.toLong()
            zos.putNextEntry(textEntry)
            zos.write(textBytes)
            zos.closeEntry()
        }

        // Test streaming parsing directly from InputStream
        val inputStream = ByteArrayInputStream(zipBaos.toByteArray())
        val reader = NpzReader.read(inputStream)

        assertTrue(reader.has("audio_emb"))
        assertTrue(reader.has("text_emb"))

        val channelArrays = reader.getChannelFlatArrays("audio_emb")
        assertNotNull("getChannelFlatArrays should succeed for 3D tensor", channelArrays)
        assertEquals("Should contain 16 channels", 16, channelArrays!!.size)
        assertEquals("Each channel should contain vocab * hiddenSize floats (10 * 8 = 80)", 80, channelArrays[0].size)

        // Verify values
        for (ch in 0 until 16) {
            for (v in 0 until 10) {
                for (h in 0 until 8) {
                    val expected = (ch * 80 + v * 8 + h) * 0.05f
                    val actual = channelArrays[ch][v * 8 + h]
                    assertEquals(expected, actual, 1e-5f)
                }
            }
        }

        // Verify flat 1D extraction
        val flat1D = reader.getFlat1D("audio_emb")
        assertNotNull(flat1D)
        assertEquals(16 * 80, flat1D!!.size)
        assertEquals(0f, flat1D[0], 1e-5f)
    }

    @Test
    fun testFlatEmbeddingNumericalEquivalence() {
        val npzFile = resolveFile("src/main/assets/vieneu/backbone/vieneu_v3_heads.npz")
        assertTrue("vieneu_v3_heads.npz must exist: ${npzFile.absolutePath}", npzFile.exists())

        val reader = NpzReader.read(npzFile)

        // 1. Get flat channel embeddings (16 flat arrays)
        val channelFlat = reader.getChannelFlatArrays("audio_emb")
        assertNotNull("channelFlat must not be null", channelFlat)
        assertEquals("Should have 16 channels", 16, channelFlat!!.size)
        assertEquals("Each channel should have 1024 * 768 floats", 1024 * 768, channelFlat[0].size)

        // 2. Get traditional 3D embeddings (16 * 1024 distinct arrays)
        val audio3D = reader.get3D("audio_emb")
        assertNotNull("audio3D must not be null", audio3D)
        assertEquals(16, audio3D!!.size)
        assertEquals(1024, audio3D[0].size)
        assertEquals(768, audio3D[0][0].size)

        // 3. Assert exact bitwise numerical equivalence across all channels and sampled codes
        val sampleCodes = intArrayOf(0, 1, 5, 23, 100, 255, 512, 777, 1023)
        val H = 768

        for (ch in 0 until 16) {
            val flatChannel = channelFlat[ch]
            for (code in sampleCodes) {
                val flatOffset = code * H
                val array3D = audio3D[ch][code]
                for (h in 0 until H) {
                    val flatVal = flatChannel[flatOffset + h]
                    val arrVal = array3D[h]
                    assertEquals("Mismatch at channel $ch, code $code, dim $h", arrVal, flatVal, 0.0f)
                }
            }
        }

        // 4. Verify 1D flat buffer equivalence with channel slices
        val full1D = reader.getFlat1D("audio_emb")!!
        assertEquals(16 * 1024 * 768, full1D.size)
        for (ch in 0 until 16) {
            val channelOffset = ch * (1024 * 768)
            assertEquals(full1D[channelOffset], channelFlat[ch][0], 0.0f)
            assertEquals(full1D[channelOffset + 1023 * 768 + 767], channelFlat[ch][1023 * 768 + 767], 0.0f)
        }
    }

    @Test
    fun testProGuardRulesCoverage() {
        val proguardFile = resolveFile("proguard-rules.pro")
        assertTrue("proguard-rules.pro must exist: ${proguardFile.absolutePath}", proguardFile.exists())
        val proguardContent = proguardFile.readText(Charsets.UTF_8)

        // 1. Verify ONNX Runtime rule
        assertTrue("ProGuard rules must keep ai.onnxruntime.**", proguardContent.contains("-keep class ai.onnxruntime.**"))

        // 2. Verify JNI & SeaG2P keep rules
        assertTrue("ProGuard rules must keep native methods", proguardContent.contains("native <methods>;"))
        assertTrue("ProGuard rules must keep SeaG2P class", proguardContent.contains("class com.skul9x.doctruyen.tts.vieneu.SeaG2P"))

        // 3. Verify Retrofit & Gson data models keep rules
        assertTrue("ProGuard rules must preserve Retrofit classes", proguardContent.contains("class retrofit2.**"))
        assertTrue("ProGuard rules must preserve network models", proguardContent.contains("class com.skul9x.doctruyen.network.**"))
        assertTrue("ProGuard rules must preserve SerializedName annotations", proguardContent.contains("@com.google.gson.annotations.SerializedName"))
        assertTrue("ProGuard must keep kotlin.coroutines.Continuation", proguardContent.contains("class kotlin.coroutines.Continuation"))
        assertTrue("ProGuard must keep retrofit2.Call", proguardContent.contains("interface retrofit2.Call"))
        assertTrue("ProGuard must keep retrofit2.Response", proguardContent.contains("class retrofit2.Response"))
        assertTrue("ProGuard must keep AnnotationDefault", proguardContent.contains("-keepattributes AnnotationDefault"))

        // 4. Verify build.gradle.kts configuration
        val gradleFile = resolveFile("build.gradle.kts")
        assertTrue("build.gradle.kts must exist: ${gradleFile.absolutePath}", gradleFile.exists())
        val gradleContent = gradleFile.readText(Charsets.UTF_8)

        assertTrue("build.gradle.kts must have isMinifyEnabled = true", gradleContent.contains("isMinifyEnabled = true"))
        assertTrue("build.gradle.kts must configure ABI filters/splits", gradleContent.contains("arm64-v8a"))
    }
}
