package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Phase 02: Storage & Memory Footprint Optimization Test Suite.
 *
 * Required Verifications:
 * 1. testEnsureAssetsExtractedExcludesNpzAndJson():
 *    Execute ensureAssetsExtracted with a mock asset directory and assert that .onnx and .data
 *    files exist on disk, while .npz and .json files are strictly excluded.
 * 2. testStreamingNpzReaderDirectChannels():
 *    Parse vieneu_v3_heads.npz using the streaming reader and compare all float values of
 *    audio_emb against baseline values, verifying exact IEEE 754 equality (0.0 error).
 * 3. testDirectStreamingHeapEfficiency():
 *    Verify that reading a mock 10MB .npy stream does not allocate a monolithic intermediate
 *    byte array matching the entry size, reading chunks bounded by <= 64KB.
 */
class StorageAndNpzStreamingTest {

    private lateinit var tempRoot: File
    private lateinit var assetsRoot: File
    private lateinit var filesRoot: File
    private lateinit var dummyContext: Context

    private var originalAssetListProvider: ((Context, String) -> Array<String>?)? = null
    private var originalAssetOpenProvider: ((Context, String) -> InputStream)? = null

    @Before
    fun setUp() {
        tempRoot = File(System.getProperty("java.io.tmpdir"), "storage_npz_test_${System.currentTimeMillis()}").apply {
            mkdirs()
        }
        assetsRoot = File(tempRoot, "mock_assets").apply { mkdirs() }
        filesRoot = File(tempRoot, "mock_files").apply { mkdirs() }

        setupMockAssetTree(assetsRoot)

        dummyContext = object : ContextWrapper(null) {
            override fun getFilesDir(): File = filesRoot
            override fun getApplicationContext(): Context = this
        }

        originalAssetListProvider = OnnxSessionManager.assetListProvider
        originalAssetOpenProvider = OnnxSessionManager.assetOpenProvider

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
        OnnxSessionManager.assetListProvider = originalAssetListProvider
        OnnxSessionManager.assetOpenProvider = originalAssetOpenProvider
        if (::tempRoot.isInitialized && tempRoot.exists()) {
            tempRoot.deleteRecursively()
        }
    }

    private fun setupMockAssetTree(root: File) {
        val vieneuDir = File(root, "vieneu").apply { mkdirs() }
        val fp32Dir = File(vieneuDir, "backbone").apply { mkdirs() }
        val int8Dir = File(vieneuDir, "backbone_int8").apply { mkdirs() }
        val codecDir = File(vieneuDir, "codec").apply { mkdirs() }

        // Binary files that SHOULD be extracted
        File(fp32Dir, "vieneu_prefill.onnx").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(fp32Dir, "vieneu_decode_step.onnx").writeBytes(byteArrayOf(5, 6, 7, 8))
        File(fp32Dir, "vieneu_acoustic_cached.onnx").writeBytes(byteArrayOf(9, 10))
        File(fp32Dir, "vieneu_backbone_shared.data").writeBytes(byteArrayOf(11, 12, 13))

        File(int8Dir, "vieneu_prefill.onnx").writeBytes(byteArrayOf(1, 2, 3, 4))
        File(int8Dir, "vieneu_decode_step.onnx").writeBytes(byteArrayOf(5, 6, 7, 8))
        File(int8Dir, "vieneu_acoustic_cached.onnx").writeBytes(byteArrayOf(9, 10))
        File(int8Dir, "vieneu_backbone_shared.data").writeBytes(byteArrayOf(11, 12, 13))

        File(codecDir, "moss_audio_tokenizer_decode_full.onnx").writeBytes(byteArrayOf(20, 21))
        File(codecDir, "moss_audio_tokenizer_decode_shared.data").writeBytes(byteArrayOf(22, 23))
        File(vieneuDir, "sea_g2p.bin").writeBytes(byteArrayOf(30, 31, 32))

        // Ancillary files that MUST NOT be extracted
        File(fp32Dir, "config.json").writeText("{\"precision\": \"fp32\"}")
        File(fp32Dir, "tokenizer.json").writeText("{\"vocab\": {}}")
        File(fp32Dir, "vieneu_v3_heads.npz").writeBytes(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))

        File(int8Dir, "config.json").writeText("{\"precision\": \"int8\"}")
        File(int8Dir, "tokenizer.json").writeText("{\"vocab\": {}}")
        File(int8Dir, "vieneu_v3_heads.npz").writeBytes(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))

        File(codecDir, "codec_browser_onnx_meta.json").writeText("{\"codec\": true}")
        File(vieneuDir, "voices_v3_turbo.json").writeText("{\"presets\": []}")
    }

    /**
     * Requirement 1:
     * Execute ensureAssetsExtracted with mock asset directory and assert that .onnx, .data, and .bin
     * files exist on disk, while .npz and .json are strictly excluded.
     */
    @Test
    fun testEnsureAssetsExtractedExcludesNpzAndJson() {
        val extractedBase = OnnxSessionManager.ensureAssetsExtracted(dummyContext, "FP32", forceReExtraction = true)
        val fp32Dir = File(extractedBase, "backbone")
        val codecDir = File(extractedBase, "codec")

        // Whitelisted native binaries MUST exist
        assertTrue("vieneu_prefill.onnx must exist", File(fp32Dir, "vieneu_prefill.onnx").exists())
        assertTrue("vieneu_decode_step.onnx must exist", File(fp32Dir, "vieneu_decode_step.onnx").exists())
        assertTrue("vieneu_acoustic_cached.onnx must exist", File(fp32Dir, "vieneu_acoustic_cached.onnx").exists())
        assertTrue("vieneu_backbone_shared.data must exist", File(fp32Dir, "vieneu_backbone_shared.data").exists())
        assertTrue("codec onnx file must exist", File(codecDir, "moss_audio_tokenizer_decode_full.onnx").exists())
        assertTrue("codec data file must exist", File(codecDir, "moss_audio_tokenizer_decode_shared.data").exists())
        assertTrue("sea_g2p.bin must exist", File(extractedBase, "sea_g2p.bin").exists())

        // Excluded files MUST NOT exist on disk
        assertFalse("vieneu_v3_heads.npz must NOT be extracted to disk", File(fp32Dir, "vieneu_v3_heads.npz").exists())
        assertFalse("config.json must NOT be extracted to disk", File(fp32Dir, "config.json").exists())
        assertFalse("tokenizer.json must NOT be extracted to disk", File(fp32Dir, "tokenizer.json").exists())
        assertFalse("codec json must NOT be extracted to disk", File(codecDir, "codec_browser_onnx_meta.json").exists())
        assertFalse("voices_v3_turbo.json must NOT be extracted to disk", File(extractedBase, "voices_v3_turbo.json").exists())
    }

    /**
     * Requirement 2:
     * Parse vieneu_v3_heads.npz using the streaming reader and compare all float values of
     * audio_emb against baseline array, verifying exact IEEE 754 equality (0.0 error).
     */
    @Test
    fun testStreamingNpzReaderDirectChannels() {
        val rootCandidates = listOf(
            File("app/src/main/assets/vieneu/backbone/vieneu_v3_heads.npz"),
            File("src/main/assets/vieneu/backbone/vieneu_v3_heads.npz")
        )
        val realNpzFile = rootCandidates.firstOrNull { it.exists() }
        assertNotNull("vieneu_v3_heads.npz asset must exist in project workspace", realNpzFile)

        // 1. Read with streaming NpzReader
        val streamingReader = NpzReader.read(realNpzFile!!)
        val audioChannels = streamingReader.getChannelArrays("audio_emb")
        assertNotNull("audio_emb channels must not be null", audioChannels)
        assertEquals("audio_emb must have 16 channels", 16, audioChannels!!.size)

        val channelStride = 1024 * 768
        for (ch in 0 until 16) {
            assertEquals("Channel $ch stride must be 1024 * 768", channelStride, audioChannels[ch].size)
        }

        val totalElements = audioChannels.sumOf { it.size }
        assertTrue("Total float elements must be at least 12,288,000", totalElements >= 12_288_000)

        // 2. Read baseline directly from zip entry using standard manual parsing
        val baselineChannels = Array(16) { FloatArray(channelStride) }
        ZipInputStream(FileInputStream(realNpzFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "audio_emb.npy") {
                    val fullBytes = NpzReader.readAllBytes(zis, entry.size)
                    val rawNpy = NpzReader.parseNpy(fullBytes)
                    assertNotNull(rawNpy)
                    for (ch in 0 until 16) {
                        System.arraycopy(rawNpy!!.data, ch * channelStride, baselineChannels[ch], 0, channelStride)
                    }
                    break
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        // 3. Verify exact IEEE 754 bit-for-bit equality (0.0 diff) across all elements
        for (ch in 0 until 16) {
            val streamCh = audioChannels[ch]
            val baseCh = baselineChannels[ch]
            for (i in 0 until channelStride) {
                assertEquals(
                    "Channel $ch index $i float value must match baseline with exact IEEE 754 precision",
                    baseCh[i],
                    streamCh[i],
                    0.0f
                )
            }
        }
    }

    /**
     * Requirement 3:
     * Verify that reading a mock 10MB .npy stream does not allocate a monolithic intermediate
     * byte array matching the entry size, reading chunks bounded by <= 64KB.
     */
    @Test
    fun testDirectStreamingHeapEfficiency() {
        // Construct a synthetic 10MB raw .npy byte stream
        // 10 channels, 256 rows, 1024 cols = 2,621,440 floats = 10,485,760 bytes (~10MB)
        val d0 = 10
        val d1 = 256
        val d2 = 1024
        val totalFloats = d0 * d1 * d2
        val mockFloats = FloatArray(totalFloats) { (it % 1000).toFloat() * 0.01f }
        val rawDataSize = totalFloats * 4L
        assertTrue("Raw tensor data must be at least 10MB", rawDataSize >= 10L * 1024L * 1024L)

        val headerDict = "{'descr': '<f4', 'fortran_order': False, 'shape': ($d0, $d1, $d2), }"
        val headerLen = ((headerDict.length + 10 + 63) / 64) * 64 - 10
        val paddedHeader = headerDict.padEnd(headerLen - 1, ' ') + "\n"

        val prefix = ByteArray(10)
        prefix[0] = 0x93.toByte()
        prefix[1] = 'N'.code.toByte()
        prefix[2] = 'U'.code.toByte()
        prefix[3] = 'M'.code.toByte()
        prefix[4] = 'P'.code.toByte()
        prefix[5] = 'Y'.code.toByte()
        prefix[6] = 1 // major
        prefix[7] = 0 // minor
        prefix[8] = (headerLen and 0xFF).toByte()
        prefix[9] = ((headerLen shr 8) and 0xFF).toByte()

        val npyBaos = ByteArrayOutputStream()
        npyBaos.write(prefix)
        npyBaos.write(paddedHeader.toByteArray(Charsets.US_ASCII))

        val bb = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        while (offset < totalFloats) {
            val chunkSize = minOf(64 * 1024 / 4, totalFloats - offset)
            bb.clear()
            for (k in 0 until chunkSize) {
                bb.putFloat(mockFloats[offset + k])
            }
            npyBaos.write(bb.array(), 0, chunkSize * 4)
            offset += chunkSize
        }

        val rawNpyBytes = npyBaos.toByteArray()
        assertTrue("Raw .npy bytes must exceed 10MB", rawNpyBytes.size >= 10 * 1024 * 1024)

        // Read using a monitored stream to verify read chunk sizes
        class MonitoredStream(inner: InputStream) : FilterInputStream(inner) {
            var maxRequestedChunk: Int = 0
            var readCount: Int = 0

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                maxRequestedChunk = maxOf(maxRequestedChunk, len)
                readCount++
                return super.read(b, off, len)
            }
        }

        // 1. Verify direct streaming from the 10MB .npy stream
        val monitoredStream = MonitoredStream(ByteArrayInputStream(rawNpyBytes))
        val npyArray = NpzReader.readNpyFromStream(monitoredStream)
        assertNotNull("Direct streaming parser must parse 10MB tensor", npyArray)
        val channels = npyArray!!.channels
        assertNotNull("Direct channels must be populated", channels)
        assertEquals(d0, channels!!.size)
        assertEquals(d1 * d2, channels[0].size)

        // Verify float values
        for (ch in 0 until d0) {
            for (idx in 0 until d1 * d2) {
                val globalIdx = ch * (d1 * d2) + idx
                assertEquals(mockFloats[globalIdx], channels[ch][idx], 0.0f)
            }
        }

        // Memory buffer assertions:
        // 1. Max chunk size must be <= 64KB (NpzReader.STREAM_BUFFER_SIZE)
        assertTrue(
            "Max requested read chunk must be <= 64KB, was: ${monitoredStream.maxRequestedChunk}",
            monitoredStream.maxRequestedChunk <= NpzReader.STREAM_BUFFER_SIZE
        )
        // 2. Must NEVER allocate or request a monolithic 10MB chunk
        assertTrue(
            "Must not allocate monolithic buffer matching 10MB entry size",
            monitoredStream.maxRequestedChunk < 1_000_000
        )
        // 3. Must perform multiple chunked reads (10MB / 64KB ~= 160 reads)
        assertTrue(
            "Should have performed multiple streamed chunk reads, count: ${monitoredStream.readCount}",
            monitoredStream.readCount >= 150
        )

        // 2. Verify reading via NPZ archive wrapper
        val zipBaos = ByteArrayOutputStream()
        ZipOutputStream(zipBaos).use { zos ->
            zos.putNextEntry(ZipEntry("tensor.npy"))
            zos.write(rawNpyBytes)
            zos.closeEntry()
        }
        val npzReader = NpzReader.read(ByteArrayInputStream(zipBaos.toByteArray()))
        val npzChannels = npzReader.getChannelArrays("tensor")
        assertNotNull("NPZ reader must parse tensor from archive", npzChannels)
        assertEquals(d0, npzChannels!!.size)
        assertEquals(mockFloats[0], npzChannels[0][0], 0.0f)
    }
}
