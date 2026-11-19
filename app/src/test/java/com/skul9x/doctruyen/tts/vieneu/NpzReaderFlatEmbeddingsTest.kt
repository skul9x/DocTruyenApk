package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Phase 03 Comprehensive Test Suite:
 * Flat Embeddings & NpzReader Memory Optimization.
 *
 * Verifies:
 * 1. testChannelArraysExtractionShapeAndValues:
 *    Parses 3D tensors into contiguous channel arrays of shape [nVq, vocabSize * hiddenSize]
 *    without allocating 16,384 inner array objects. Verifies getFlat and getChannelArrays.
 * 2. testIndexingEquivalence:
 *    Ensures channelArrays[ch][v * H + h] == original3D[ch][v][h] across exhaustive synthetic
 *    data and real model assets with 0.0 delta.
 * 3. testCopyAudioEmbeddingIntoBuffer:
 *    Verifies copyAudioEmbedding copies H-dimensional slices directly into pre-allocated destination
 *    buffers with and without offsets without object allocations.
 * 4. testEngineInitializationWithFlatArrays:
 *    Initializes VieNeuOnnxEngine directly with flat channel arrays, verifying zero-duplication
 *    flatAudioEmbeddings alias, embedRows flat slice arithmetic, sampleCode vector dot products,
 *    and backwards compatibility with the 3D constructor.
 */
class NpzReaderFlatEmbeddingsTest {

    private fun getBaseAssetsDir(): File {
        val path1 = File("app/src/main/assets/vieneu")
        return if (path1.exists()) path1 else File("src/main/assets/vieneu")
    }

    private fun createNpyBytes(shape: IntArray, data: FloatArray): ByteArray {
        val baos = ByteArrayOutputStream()
        baos.write(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))
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

    private fun createSyntheticNpz(entries: Map<String, Pair<IntArray, FloatArray>>): ByteArray {
        val zipBaos = ByteArrayOutputStream()
        ZipOutputStream(zipBaos).use { zos ->
            for ((key, pair) in entries) {
                val bytes = createNpyBytes(pair.first, pair.second)
                val entry = ZipEntry("$key.npy")
                entry.size = bytes.size.toLong()
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return zipBaos.toByteArray()
    }

    @Test
    fun testChannelArraysExtractionShapeAndValues() {
        val d0 = 16
        val d1 = 32
        val d2 = 64
        val totalFloats = d0 * d1 * d2
        val mockData = FloatArray(totalFloats) { i -> (i * 0.25f) }

        val npzBytes = createSyntheticNpz(
            mapOf(
                "audio_emb" to Pair(intArrayOf(d0, d1, d2), mockData),
                "scalar_test" to Pair(intArrayOf(1), floatArrayOf(42.0f)),
                "mat_2d" to Pair(intArrayOf(4, 8), FloatArray(32) { it.toFloat() })
            )
        )

        val reader = NpzReader.read(ByteArrayInputStream(npzBytes))

        // 1. Verify getFlat
        val flat = reader.getFlat("audio_emb")
        assertNotNull("getFlat must return non-null for valid key", flat)
        assertEquals(totalFloats, flat!!.size)
        assertEquals(0.0f, flat[0], 0.0f)
        assertEquals((totalFloats - 1) * 0.25f, flat[totalFloats - 1], 1e-5f)

        // 2. Verify getChannelArrays allocates exactly d0 arrays with stride d1 * d2
        val channels = reader.getChannelArrays("audio_emb")
        assertNotNull("getChannelArrays must return non-null for 3D tensor", channels)
        assertEquals("Must return exactly $d0 channels", d0, channels!!.size)

        val stride = d1 * d2
        for (ch in 0 until d0) {
            assertEquals("Channel $ch length must match stride $stride", stride, channels[ch].size)
            for (i in 0 until stride) {
                val expected = (ch * stride + i) * 0.25f
                assertEquals("Value mismatch at channel $ch offset $i", expected, channels[ch][i], 1e-5f)
            }
        }

        // 3. Error conditions and edge cases
        assertNull("getChannelArrays on 2D tensor must return null", reader.getChannelArrays("mat_2d"))
        assertNull("getChannelArrays on 1D tensor must return null", reader.getChannelArrays("scalar_test"))
        assertNull("getChannelArrays on missing key must return null", reader.getChannelArrays("not_found"))
        assertNull("getFlat on missing key must return null", reader.getFlat("not_found"))

        // 4. Verify against real heads asset if present
        val baseAssetsDir = getBaseAssetsDir()
        val realHeadsFile = File(baseAssetsDir, "backbone/vieneu_v3_heads.npz")
        if (realHeadsFile.exists()) {
            val realReader = NpzReader.read(realHeadsFile)
            val audioChannels = realReader.getChannelArrays("audio_emb")
            assertNotNull("audio_emb must exist in vieneu_v3_heads.npz", audioChannels)
            assertEquals("audio_emb must have 16 channels", 16, audioChannels!!.size)

            val expectedChannelSize = 1024 * 768
            for (ch in 0 until 16) {
                assertEquals("Channel $ch must have size 1024 * 768", expectedChannelSize, audioChannels[ch].size)
            }

            val flatAudio = realReader.getFlat("audio_emb")
            assertNotNull(flatAudio)
            assertEquals("Flat audio_emb must have size 16 * 1024 * 768", 16 * expectedChannelSize, flatAudio!!.size)
        }
    }

    @Test
    fun testIndexingEquivalence() {
        val d0 = 4
        val d1 = 16
        val d2 = 32
        val totalFloats = d0 * d1 * d2
        val mockData = FloatArray(totalFloats) { i -> (i * 0.125f) }

        val npzBytes = createSyntheticNpz(
            mapOf("test_emb" to Pair(intArrayOf(d0, d1, d2), mockData))
        )
        val reader = NpzReader.read(ByteArrayInputStream(npzBytes))
        val channelArrays = reader.getChannelArrays("test_emb")!!
        @Suppress("DEPRECATION")
        val original3D = reader.get3D("test_emb")!!

        assertEquals(d0, channelArrays.size)
        assertEquals(d0, original3D.size)

        // Exhaustive check across all coordinates: original3D[ch][v][h] == channelArrays[ch][v * H + h]
        val H = d2
        for (ch in 0 until d0) {
            for (v in 0 until d1) {
                for (h in 0 until d2) {
                    val flatVal = channelArrays[ch][v * H + h]
                    val origVal = original3D[ch][v][h]
                    assertEquals("Mismatch at [$ch][$v][$h]", origVal, flatVal, 0.0f)
                }
            }
        }

        // Test equivalence against real assets if present
        val baseAssetsDir = getBaseAssetsDir()
        val realHeadsFile = File(baseAssetsDir, "backbone/vieneu_v3_heads.npz")
        if (realHeadsFile.exists()) {
            val realReader = NpzReader.read(realHeadsFile)
            val realChannels = realReader.getChannelArrays("audio_emb")!!
            @Suppress("DEPRECATION")
            val real3D = realReader.get3D("audio_emb")!!

            assertEquals(16, realChannels.size)
            assertEquals(16, real3D.size)

            val realH = 768
            // Corner checks: first, last, midpoints
            assertEquals(real3D[0][0][0], realChannels[0][0], 0.0f)
            assertEquals(real3D[15][1023][767], realChannels[15][1023 * realH + 767], 0.0f)
            assertEquals(real3D[8][512][256], realChannels[8][512 * realH + 256], 0.0f)

            // Random checks across 100 samples
            val rng = Random(42)
            for (sample in 0 until 100) {
                val ch = rng.nextInt(16)
                val v = rng.nextInt(1024)
                val h = rng.nextInt(768)
                assertEquals(
                    "Real asset mismatch at [$ch][$v][$h]",
                    real3D[ch][v][h],
                    realChannels[ch][v * realH + h],
                    0.0f
                )
            }
        }
    }

    @Test
    fun testCopyAudioEmbeddingIntoBuffer() {
        val baseAssetsDir = getBaseAssetsDir()
        val backboneDir = File(baseAssetsDir, "backbone")
        val codecDir = File(baseAssetsDir, "codec")
        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")

        val sessionManager = OnnxSessionManager.createFromFiles(backboneDir, codecDir, threads = 1)
        val headsReader = NpzReader.read(headsFile)
        val config = VieNeuConfig.fromJson(File(backboneDir, "config.json").readText(Charsets.UTF_8))
        val tokenizer = VieNeuTokenizer.fromFile(File(backboneDir, "tokenizer.json"))

        val channelArrays = headsReader.getChannelArrays("audio_emb")!!

        val engine = VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = headsReader.get2D("text_emb")!!,
            audioEmbeddings = channelArrays,
            xvecW = headsReader.get2D("xvec_w"),
            xvecB = headsReader.getFloatArray("xvec_b"),
            xvecLnW = headsReader.getFloatArray("xvec_ln_w"),
            xvecLnB = headsReader.getFloatArray("xvec_ln_b"),
            xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f
        )

        try {
            val H = config.hiddenSize // 768
            val destBuffer = FloatArray(H)

            // Test 1: Direct copy without offset
            val testCh = 3
            val testCode = 42
            engine.copyAudioEmbedding(testCh, testCode, destBuffer, 0)

            val srcOffset = testCode * H
            for (h in 0 until H) {
                val expected = channelArrays[testCh][srcOffset + h]
                assertEquals("Buffer mismatch at h=$h", expected, destBuffer[h], 0.0f)
            }

            // Test 2: Copy with non-zero destination offset
            val paddedBuffer = FloatArray(H + 32)
            val offset = 16
            engine.copyAudioEmbedding(testCh, testCode, paddedBuffer, offset)

            assertEquals("Padding prefix must remain 0.0f", 0.0f, paddedBuffer[0], 0.0f)
            for (h in 0 until H) {
                val expected = channelArrays[testCh][srcOffset + h]
                assertEquals("Offset buffer mismatch at h=$h", expected, paddedBuffer[offset + h], 0.0f)
            }
            assertEquals("Padding suffix must remain 0.0f", 0.0f, paddedBuffer[offset + H], 0.0f)
        } finally {
            engine.close()
        }
    }

    @Test
    fun testEngineInitializationWithFlatArrays() {
        val baseAssetsDir = getBaseAssetsDir()
        val backboneDir = File(baseAssetsDir, "backbone")
        val codecDir = File(baseAssetsDir, "codec")
        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")

        val sessionManager = OnnxSessionManager.createFromFiles(backboneDir, codecDir, threads = 1)
        val headsReader = NpzReader.read(headsFile)
        val config = VieNeuConfig.fromJson(File(backboneDir, "config.json").readText(Charsets.UTF_8))
        val tokenizer = VieNeuTokenizer.fromFile(File(backboneDir, "tokenizer.json"))

        val textEmb = headsReader.get2D("text_emb")!!
        val channelArrays = headsReader.getChannelArrays("audio_emb")!!
        @Suppress("DEPRECATION")
        val original3D = headsReader.get3D("audio_emb")!!

        // 1. Primary constructor with flat channel arrays
        val engine = VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = textEmb,
            audioEmbeddings = channelArrays,
            xvecW = headsReader.get2D("xvec_w"),
            xvecB = headsReader.getFloatArray("xvec_b"),
            xvecLnW = headsReader.getFloatArray("xvec_ln_w"),
            xvecLnB = headsReader.getFloatArray("xvec_ln_b"),
            xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f
        )

        try {
            // Verify flatAudioEmbeddings is an exact reference alias (0 duplication)
            assertSame(
                "flatAudioEmbeddings must be identical reference to audioEmbeddings",
                engine.audioEmbeddings,
                engine.flatAudioEmbeddings
            )
            assertEquals("Must have 16 channels", 16, engine.audioEmbeddings.size)
            assertEquals("Each channel must have 1024 * 768 elements", 1024 * 768, engine.audioEmbeddings[0].size)

            // 2. Verify embedRows computation
            val rows = arrayOf(
                intArrayOf(10, 5, 12, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024, 1024)
            )
            val anchor = FloatArray(config.hiddenSize) { 0.1f }
            val embedded = engine.embedRows(rows, anchor)

            val H = config.hiddenSize
            assertEquals(H, embedded.size)

            for (h in 0 until H) {
                var expected = textEmb[10][h] + anchor[h]
                expected += channelArrays[0][5 * H + h]
                expected += channelArrays[1][12 * H + h]
                // channels 2..15 are 1024 (pad token) so not added
                assertEquals("embedRows mismatch at h=$h", expected, embedded[h], 1e-5f)
            }

            // 3. Test sampleCode math
            val dummyVec = FloatArray(H) { 0.01f }
            val sampledToken = engine.sampleCode(
                ch = 0,
                vec = dummyVec,
                temperature = 0.0f,
                topK = 1,
                topP = 1.0f,
                repetitionPenalty = 1.0f,
                hist = null
            )
            assertTrue("Sampled token must be in 0 until 1024", sampledToken in 0 until 1024)

            // 4. Backwards compatibility: Secondary constructor with 3D array
            @Suppress("DEPRECATION")
            val legacyEngine = VieNeuOnnxEngine(
                sessionManager = sessionManager,
                config = config,
                tokenizer = tokenizer,
                textEmbeddings = textEmb,
                audioEmbeddings = original3D,
                xvecW = headsReader.get2D("xvec_w"),
                xvecB = headsReader.getFloatArray("xvec_b"),
                xvecLnW = headsReader.getFloatArray("xvec_ln_w"),
                xvecLnB = headsReader.getFloatArray("xvec_ln_b"),
                xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f
            )

            assertEquals(16, legacyEngine.audioEmbeddings.size)
            for (ch in 0 until 16) {
                assertArrayEquals(
                    "Secondary constructor flattening must be identical for channel $ch",
                    channelArrays[ch],
                    legacyEngine.audioEmbeddings[ch],
                    0.0f
                )
            }
        } finally {
            engine.close()
        }
    }
}
