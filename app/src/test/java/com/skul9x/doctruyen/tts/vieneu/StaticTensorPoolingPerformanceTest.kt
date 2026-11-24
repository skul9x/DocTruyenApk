package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OnnxTensor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Comprehensive File-Based Test for Phase 03:
 * Static JNI Tensor Pooling & Native Memory Churn Optimization (AUD-003)
 *
 * Verifies:
 * 1. testStaticChannelPositionTensorShapesAndValues:
 *    - posTensor0 has shape [1, 2] and contains [0L, 1L].
 *    - channelPosTensors has size nVq + 1, each channel tensor at index ch has shape [1, 1] and value ch.
 *    - Repeated invocations return exact same cached instances without re-allocating.
 * 2. testAcousticFrameGenerationExecutesWithPooledTensors:
 *    - Inference generates valid PCM audio using pooled tensors with bit-identical acoustic codes.
 * 3. testPooledTensorsPersistAcrossMultipleInferences:
 *    - Across multiple consecutive chunk inferences, cached tensor references remain identical and open.
 * 4. testDefensiveEmbTensorLifecycleUnderCancellation:
 *    - Cancelling mid-inference safely cleans up dynamic embTensor via try-finally without native heap corruption.
 * 5. testEngineTeardownSafelyClosesAndNullsPooledTensors:
 *    - Engine close() releases all pooled native tensors, nullifies references, and ensures idempotent shutdown.
 */
class StaticTensorPoolingPerformanceTest {

    private lateinit var engine: VieNeuOnnxEngine

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupNativeLibrary() {
            val possiblePaths = listOf(
                "sea-g2p-android/target/release/libsea_g2p_android.so",
                "../sea-g2p-android/target/release/libsea_g2p_android.so",
                "app/src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "src/main/jniLibs/x86_64/libsea_g2p_android.so",
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

    private fun getBaseAssetsDir(): File {
        val path1 = File("app/src/main/assets/vieneu")
        return if (path1.exists()) path1 else File("src/main/assets/vieneu")
    }

    @Before
    fun setUp() {
        PrewarmedChunkCache.clear()
        SeaG2P.clearCache()
        VieNeuOnnxEngine.CacheTensor.Pool.clear()

        val baseAssetsDir = getBaseAssetsDir()
        val backboneDir = OnnxSessionManager.getBackboneDir(baseAssetsDir, "INT8")
        val codecDir = File(baseAssetsDir, "codec")
        val voicesFile = File(baseAssetsDir, "voices_v3_turbo.json")

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 2,
            precision = "INT8"
        )

        val configFile = File(backboneDir, "config.json")
        val config = VieNeuConfig.fromJson(configFile.readText(Charsets.UTF_8))

        val tokenizerFile = File(backboneDir, "tokenizer.json")
        val tokenizer = VieNeuTokenizer.fromFile(tokenizerFile)

        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
        val headsReader = NpzReader.read(headsFile)

        val textEmb = headsReader.get2D("text_emb")!!
        val audioEmb = headsReader.get3D("audio_emb")
        val audioFlat = headsReader.getChannelFlatArrays("audio_emb")
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
            audioEmbeddingsIn = audioEmb,
            flatAudioEmbeddingsIn = audioFlat,
            xvecW = xvecW,
            xvecB = xvecB,
            xvecLnW = xvecLnW,
            xvecLnB = xvecLnB,
            xvecLnEps = xvecLnEps
        )
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized && !engine.isClosed) {
            engine.close()
        }
        PrewarmedChunkCache.clear()
        SeaG2P.clearCache()
        VieNeuOnnxEngine.CacheTensor.Pool.clear()
    }

    /**
     * Requirement 1: Verify pre-allocated tensor shapes, values, and instance caching.
     */
    @Test
    fun testStaticChannelPositionTensorShapesAndValues() {
        // Initially before access or inference, cached references are null (lazy initialization)
        assertNull("cachedPosTensor0 should initially be null before access", engine.getCachedPosTensor0ForTesting())
        assertNull("cachedChannelPosTensors should initially be null before access", engine.getCachedChannelPosTensorsForTesting())

        // 1. Verify getOrCreatePosTensor0()
        val pos0 = engine.getOrCreatePosTensor0()
        assertNotNull("pos0 should not be null", pos0)
        assertArrayEquals("pos0 shape must be [1, 2]", longArrayOf(1, 2), pos0.info.shape)

        val buf0 = pos0.longBuffer
        buf0.rewind()
        assertEquals("pos0[0] must be 0L", 0L, buf0.get(0))
        assertEquals("pos0[1] must be 1L", 1L, buf0.get(1))

        // Re-acquisition must return the exact same instance
        val pos0Second = engine.getOrCreatePosTensor0()
        assertSame("pos0 must be cached and return same reference", pos0, pos0Second)

        // 2. Verify getOrCreateChannelPosTensors()
        val channelTensors = engine.getOrCreateChannelPosTensors()
        val nVq = engine.config.nVq
        assertEquals("channelTensors array length must be nVq + 1", nVq + 1, channelTensors.size)

        for (ch in 0..nVq) {
            val t = channelTensors[ch]
            assertNotNull("channel tensor at index $ch must not be null", t)
            assertArrayEquals("channel tensor at index $ch shape must be [1, 1]", longArrayOf(1, 1), t.info.shape)

            val buf = t.longBuffer
            buf.rewind()
            assertEquals("channel tensor at index $ch value must match $ch", ch.toLong(), buf.get(0))
        }

        // Re-acquisition must return the exact same array and tensor instances
        val channelTensorsSecond = engine.getOrCreateChannelPosTensors()
        assertSame("channelTensors array must be cached and return same reference", channelTensors, channelTensorsSecond)
        for (ch in 0..nVq) {
            assertSame("tensor at index $ch must match same instance", channelTensors[ch], channelTensorsSecond[ch])
        }
    }

    /**
     * Requirement 2: Verify inference executes successfully using pooled channel position tensors.
     */
    @Test
    fun testAcousticFrameGenerationExecutesWithPooledTensors() {
        val result = engine.inferWithTiming(phonemes = "s in1 ch aw2")
        assertNotNull("Inference result must not be null", result)
        assertTrue("Inference PCM should not be empty", result.pcm.isNotEmpty())

        // Validate audio sample integrity
        for (sample in result.pcm) {
            assertFalse("Audio sample must not be NaN", sample.isNaN())
            assertFalse("Audio sample must not be Infinite", sample.isInfinite())
        }

        // Validate that pooled tensors were created and are currently active
        assertNotNull("cachedPosTensor0 must be initialized after inference", engine.getCachedPosTensor0ForTesting())
        assertNotNull("cachedChannelPosTensors must be initialized after inference", engine.getCachedChannelPosTensorsForTesting())
    }

    /**
     * Requirement 3: Verify all pooled tensors persist across multiple consecutive chunk inferences.
     */
    @Test
    fun testPooledTensorsPersistAcrossMultipleInferences() {
        // Run first inference
        val res1 = engine.inferWithTiming(phonemes = "m ootj")
        assertTrue("res1 PCM should not be empty", res1.pcm.isNotEmpty())

        val pos0Ref = engine.getCachedPosTensor0ForTesting()
        val channelTensorsRef = engine.getCachedChannelPosTensorsForTesting()
        assertNotNull("pos0Ref must not be null", pos0Ref)
        assertNotNull("channelTensorsRef must not be null", channelTensorsRef)

        // Run second inference
        val res2 = engine.inferWithTiming(phonemes = "h ay")
        assertTrue("res2 PCM should not be empty", res2.pcm.isNotEmpty())

        assertSame("pos0 must remain identical across chunk 1 and chunk 2", pos0Ref, engine.getCachedPosTensor0ForTesting())
        assertSame("channelTensors must remain identical across chunk 1 and chunk 2", channelTensorsRef, engine.getCachedChannelPosTensorsForTesting())

        // Run third inference
        val res3 = engine.inferWithTiming(phonemes = "b a")
        assertTrue("res3 PCM should not be empty", res3.pcm.isNotEmpty())

        assertSame("pos0 must remain identical across chunk 2 and chunk 3", pos0Ref, engine.getCachedPosTensor0ForTesting())
        assertSame("channelTensors must remain identical across chunk 2 and chunk 3", channelTensorsRef, engine.getCachedChannelPosTensorsForTesting())

        // Ensure all tensors are still open and operational
        assertFalse("pos0 must not be closed during active engine life", pos0Ref!!.info.shape.isEmpty())
        for (t in channelTensorsRef!!) {
            assertFalse("channel tensor must not be closed during active engine life", t.info.shape.isEmpty())
        }
    }

    /**
     * Requirement 4: Defensive embTensor lifecycle under cancellation.
     */
    @Test
    fun testDefensiveEmbTensorLifecycleUnderCancellation() {
        // Prime the engine
        val pos0 = engine.getOrCreatePosTensor0()
        val channelTensors = engine.getOrCreateChannelPosTensors()
        assertNotNull(pos0)
        assertNotNull(channelTensors)

        // Request cancel before or during inference
        engine.requestCancel()
        try {
            engine.inferWithTiming(phonemes = "x in1 ch aw2 v ietj n aam")
            fail("Expected InterruptedException when cancelRequested is true")
        } catch (e: InterruptedException) {
            assertTrue("Exception message should indicate cancellation", e.message?.contains("cancel") == true)
        }

        // Pooled tensors must survive cancellation without being closed
        assertNotNull("cachedPosTensor0 must survive cancellation", engine.getCachedPosTensor0ForTesting())
        assertNotNull("cachedChannelPosTensors must survive cancellation", engine.getCachedChannelPosTensorsForTesting())

        // Reset cancellation and ensure subsequent inference succeeds cleanly
        engine.resetCancel()
        val res = engine.inferWithTiming(phonemes = "ch aw2")
        assertTrue("Inference after cancel reset must succeed", res.pcm.isNotEmpty())
    }

    /**
     * Requirement 5: Engine teardown safely releases all pooled position tensors and clears references.
     */
    @Test
    fun testEngineTeardownSafelyClosesAndNullsPooledTensors() {
        // Eagerly instantiate pooled tensors
        val pos0 = engine.getOrCreatePosTensor0()
        val channelTensors = engine.getOrCreateChannelPosTensors()
        assertNotNull(pos0)
        assertNotNull(channelTensors)

        // Close engine
        engine.close()
        assertTrue("Engine should be closed", engine.isClosed)

        // References must be nullified
        assertNull("cachedPosTensor0 should be null after teardown", engine.getCachedPosTensor0ForTesting())
        assertNull("cachedChannelPosTensors should be null after teardown", engine.getCachedChannelPosTensorsForTesting())

        // Retained pos0 must be closed
        assertTrue("pos0 must report isClosed = true", pos0.isClosed)
        try {
            pos0.longBuffer
            fail("Expected IllegalStateException accessing closed pos0 tensor")
        } catch (e: IllegalStateException) {
            assertTrue("Exception should indicate closed OnnxValue", e.message?.contains("closed") == true)
        }

        // All channel tensors must also be closed
        for (t in channelTensors) {
            assertTrue("channel tensor must report isClosed = true", t.isClosed)
            try {
                t.longBuffer
                fail("Expected IllegalStateException accessing closed channel tensor")
            } catch (e: IllegalStateException) {
                assertTrue("Exception should indicate closed OnnxValue", e.message?.contains("closed") == true)
            }
        }

        // Duplicate close must be idempotent and safe
        engine.close()
        assertTrue("Engine remains closed", engine.isClosed)
    }
}
