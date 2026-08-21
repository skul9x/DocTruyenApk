package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OrtEnvironment
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AcousticEngineAndNpzOptimizationTest {

    @Test
    fun testReadAllBytesWithVariousExpectedSizes() {
        val testData = "Hello Antigravity Performance Test! Stream I/O Benchmark.".toByteArray(Charsets.UTF_8)
        val dataSize = testData.size.toLong()

        // 1. Exact match expectedSize
        val stream1 = ByteArrayInputStream(testData)
        val res1 = NpzReader.readAllBytes(stream1, dataSize)
        assertArrayEquals("Should match full content when expectedSize equals actual size", testData, res1)

        // 2. Expected size larger than actual stream
        val stream2 = ByteArrayInputStream(testData)
        val res2 = NpzReader.readAllBytes(stream2, dataSize + 100L)
        assertArrayEquals("Should match full content when expectedSize is larger", testData, res2)

        // 3. Expected size smaller than actual stream
        val stream3 = ByteArrayInputStream(testData)
        val res3 = NpzReader.readAllBytes(stream3, (dataSize - 5).coerceAtLeast(1L))
        assertEquals("Should read up to truncated expected size when smaller", (dataSize - 5).coerceAtLeast(1L).toInt(), res3.size)

        // 4. Default / unknown size (-1)
        val stream4 = ByteArrayInputStream(testData)
        val res4 = NpzReader.readAllBytes(stream4, -1L)
        assertArrayEquals("Should match full content when expectedSize is -1", testData, res4)

        // 5. Zero or negative expectedSize
        val stream5 = ByteArrayInputStream(testData)
        val res5 = NpzReader.readAllBytes(stream5, 0L)
        assertArrayEquals("Should match full content when expectedSize is 0", testData, res5)
    }

    @Test
    fun testNpzReaderSyntheticArchiveParsing() {
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

        // Build synthetic zip containing scalar, 2D, and 3D arrays
        val zipBaos = ByteArrayOutputStream()
        ZipOutputStream(zipBaos).use { zos ->
            // 1. Scalar float
            val scalarBytes = createNpyBytes(intArrayOf(1), floatArrayOf(42.5f))
            val entry1 = ZipEntry("scalar_test.npy")
            entry1.size = scalarBytes.size.toLong()
            zos.putNextEntry(entry1)
            zos.write(scalarBytes)
            zos.closeEntry()

            // 2. 2D float array (2, 3)
            val arr2DData = floatArrayOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f)
            val arr2DBytes = createNpyBytes(intArrayOf(2, 3), arr2DData)
            val entry2 = ZipEntry("arr2d.npy")
            entry2.size = arr2DBytes.size.toLong()
            zos.putNextEntry(entry2)
            zos.write(arr2DBytes)
            zos.closeEntry()

            // 3. 3D float array (2, 2, 2)
            val arr3DData = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f)
            val arr3DBytes = createNpyBytes(intArrayOf(2, 2, 2), arr3DData)
            val entry3 = ZipEntry("arr3d.npy")
            entry3.size = arr3DBytes.size.toLong()
            zos.putNextEntry(entry3)
            zos.write(arr3DBytes)
            zos.closeEntry()
        }

        val npzStream = ByteArrayInputStream(zipBaos.toByteArray())
        val reader = NpzReader.read(npzStream)

        assertTrue(reader.has("scalar_test"))
        assertTrue(reader.has("arr2d"))
        assertTrue(reader.has("arr3d"))

        val scalar = reader.getScalarFloat("scalar_test")
        assertNotNull(scalar)
        assertEquals(42.5f, scalar!!, 1e-5f)

        val mat2D = reader.get2D("arr2d")
        assertNotNull(mat2D)
        assertEquals(2, mat2D!!.size)
        assertEquals(3, mat2D[0].size)
        assertEquals(1.0f, mat2D[0][0], 1e-5f)
        assertEquals(6.0f, mat2D[1][2], 1e-5f)

        val mat3D = reader.get3D("arr3d")
        assertNotNull(mat3D)
        assertEquals(2, mat3D!!.size)
        assertEquals(2, mat3D[0].size)
        assertEquals(2, mat3D[0][0].size)
        assertEquals(0.1f, mat3D[0][0][0], 1e-5f)
        assertEquals(0.8f, mat3D[1][1][1], 1e-5f)
    }

    @Test
    fun testAcousticDummyPastTensorCachingAndShape() {
        val backboneDir = File("src/main/assets/vieneu/backbone")
        val codecDir = File("src/main/assets/vieneu/codec")
        if (!backboneDir.exists() || !codecDir.exists()) {
            println("Skipping ONNX model session loading: model assets not present in local test directory")
            return
        }

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 1
        )

        val configFile = File(backboneDir, "config.json")
        val config = VieNeuConfig.fromJson(configFile.readText(Charsets.UTF_8))
        val tokenizerFile = File(backboneDir, "tokenizer.json")
        val tokenizer = VieNeuTokenizer.fromFile(tokenizerFile)
        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
        val headsReader = NpzReader.read(headsFile)

        val textEmb = headsReader.get2D("text_emb")!!
        val audioEmb = headsReader.get3D("audio_emb")!!

        val engine = VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = textEmb,
            audioEmbeddings = audioEmb,
            xvecW = headsReader.get2D("xvec_w"),
            xvecB = headsReader.getFloatArray("xvec_b"),
            xvecLnW = headsReader.getFloatArray("xvec_ln_w"),
            xvecLnB = headsReader.getFloatArray("xvec_ln_b"),
            xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f
        )

        try {
            val dummyK1 = engine.getOrCreateEmptyPastK()
            val dummyK2 = engine.getOrCreateEmptyPastK()
            assertSame("getOrCreateEmptyPastK should return identical cached instance across calls", dummyK1, dummyK2)

            val dummyV1 = engine.getOrCreateEmptyPastV()
            val dummyV2 = engine.getOrCreateEmptyPastV()
            assertSame("getOrCreateEmptyPastV should return identical cached instance across calls", dummyV1, dummyV2)

            val expectedShape = longArrayOf(1, config.localNumAttentionHeads.toLong(), 0L, config.localHeadDim.toLong())
            assertArrayEquals("Dummy past_k shape must match [1, nHLoc, 0, hdLoc]", expectedShape, dummyK1.info.shape)
            assertArrayEquals("Dummy past_v shape must match [1, nHLoc, 0, hdLoc]", expectedShape, dummyV1.info.shape)
        } finally {
            engine.close()
            assertTrue("Engine should be closed", engine.isClosed)
        }
    }
}
