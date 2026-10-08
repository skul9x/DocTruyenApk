package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * Verification test for Phase 02: Sliding-Window Repetition Penalty & Top-K Sampling.
 *
 * Verifies:
 * 1. RepetitionHistory window eviction (old tokens evicted after 64 frames and no longer penalized).
 * 2. Long-sentence tonal stability (silence and vowel codes > 64 frames ago not penalized).
 * 3. QuickSelect Top-K equivalence compared to full sorting.
 * 4. Zero heap allocation during the hot sampling loop.
 */
class SlidingWindowRepetitionAndSamplingTest {

    private fun getBaseAssetsDir(): File {
        return if (File("app/src/main/assets/vieneu").exists()) {
            File("app/src/main/assets/vieneu")
        } else {
            File("src/main/assets/vieneu")
        }
    }

    private fun createEngine(): VieNeuOnnxEngine? {
        val baseDir = getBaseAssetsDir()
        val backboneDir = File(baseDir, "backbone")
        val codecDir = File(baseDir, "codec")
        if (!backboneDir.exists() || !codecDir.exists()) return null

        val sessionManager = OnnxSessionManager.createFromFiles(backboneDir, codecDir, threads = 1)
        val config = VieNeuConfig.fromJson(File(backboneDir, "config.json").readText())
        val tokenizer = VieNeuTokenizer.fromFile(File(backboneDir, "tokenizer.json"))
        val heads = NpzReader.read(File(backboneDir, "vieneu_v3_heads.npz"))

        return VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = heads.get2D("text_emb")!!,
            audioEmbeddings = heads.get3D("audio_emb")!!,
            xvecW = heads.get2D("xvec_w"),
            xvecB = heads.getFloatArray("xvec_b"),
            xvecLnW = heads.getFloatArray("xvec_ln_w"),
            xvecLnB = heads.getFloatArray("xvec_ln_b"),
            xvecLnEps = heads.getScalarFloat("xvec_ln_eps") ?: 1e-5f
        )
    }

    /**
     * Scenario 1: Assert that after pushing 65 tokens into a channel,
     * the 1st token is evicted and no longer penalised.
     */
    @Test
    fun testRepetitionHistoryWindowEviction() {
        val windowSize = 64
        val history = RepetitionHistory(nChannels = 16, window = windowSize, vocabSize = 1024)
        val channel = 0

        // 1. Push the 1st token (token 42)
        val firstToken = 42
        history.add(channel, firstToken)

        assertTrue("Channel must contain first token", history.contains(channel, firstToken))
        assertTrue("Channel view must contain first token", history[channel].contains(firstToken))
        assertEquals(1, history.count(channel, firstToken))
        assertEquals(1, history.size(channel))

        // 2. Push 63 other unique tokens (tokens 101..163) to fill the window to capacity 64
        for (i in 1..63) {
            val token = 100 + i
            history.add(channel, token)
            assertTrue("Token $token must be present in window", history.contains(channel, token))
        }

        assertEquals("Window should now be at capacity 64", 64, history.size(channel))
        assertTrue("First token must still be present at frame 64", history.contains(channel, firstToken))
        assertEquals(1, history.count(channel, firstToken))

        // Verify penalty is applied when first token is still in window
        val penalty = 1.2f
        val logits = FloatArray(1024) { 10.0f }
        history.applyPenalty(channel, logits, penalty)
        assertEquals("First token should be penalized while in window", 10.0f / penalty, logits[firstToken], 1e-4f)

        // 3. Push the 65th token (token 999) - should evict the 1st token (token 42)
        val token65 = 999
        history.add(channel, token65)

        assertEquals("Window capacity must remain capped at 64", 64, history.size(channel))
        assertFalse("First token must be evicted after 65th token", history.contains(channel, firstToken))
        assertFalse("Channel view must reflect eviction of first token", history[channel].contains(firstToken))
        assertEquals("Evicted token frequency count must be 0", 0, history.count(channel, firstToken))
        assertTrue("65th token must now be present in window", history.contains(channel, token65))
        assertEquals(1, history.count(channel, token65))

        // 4. Verify evicted token is NO LONGER penalized, while new token IS penalized
        val freshLogits = FloatArray(1024) { 10.0f }
        history.applyPenalty(channel, freshLogits, penalty)

        assertEquals("Evicted token must NOT be penalized", 10.0f, freshLogits[firstToken], 1e-5f)
        assertEquals("65th token must be penalized", 10.0f / penalty, freshLogits[token65], 1e-4f)

        // 5. Test multi-frequency eviction: token pushed twice in window
        val duplicateToken = 500
        history.clear(channel)
        history.add(channel, duplicateToken) // at pos 0
        for (i in 1..30) history.add(channel, 200 + i)
        history.add(channel, duplicateToken) // at pos 31 (occurs twice)
        assertEquals(2, history.count(channel, duplicateToken))
        assertTrue(history.contains(channel, duplicateToken))

        // Push enough to evict pos 0 (the 1st instance), but keep pos 31
        for (i in 32..64) history.add(channel, 300 + i) // evicts pos 0
        assertEquals("Count should decrement to 1 after first eviction", 1, history.count(channel, duplicateToken))
        assertTrue("Token should still be in window due to second occurrence", history.contains(channel, duplicateToken))

        // Push another 32 tokens to evict pos 31
        for (i in 65..96) history.add(channel, 400 + i)
        assertEquals("Count should drop to 0 after second eviction", 0, history.count(channel, duplicateToken))
        assertFalse("Token should now be completely evicted", history.contains(channel, duplicateToken))
    }

    /**
     * Scenario 2: Verify that over 120 frames of continuous synthesis,
     * repetition penalty does not penalize valid recurring silence or vowel codes beyond 64 frames.
     */
    @Test
    fun testLongSentenceTonalStability() {
        val history = RepetitionHistory(nChannels = 16, window = 64, vocabSize = 1024)
        val penalty = 1.2f

        // Initial silence / vowel tokens at frame 0 across all channels (0..15)
        val silenceTokens = IntArray(16) { ch -> ch }
        for (ch in 0 until 16) {
            history.add(ch, silenceTokens[ch])
        }

        // Simulate continuous speech generation from frame 1 up to frame 120 (> 64 frames)
        for (frame in 1 until 120) {
            for (ch in 0 until 16) {
                // Emit distinct speech tokens in range 100..999 so initial silence tokens (0..15) are never repeated
                val speechToken = 100 + ((frame * 16 + ch) % 900)
                history.add(ch, speechToken)
            }
        }

        // At frame 120: Verify that initial silence / vowel tokens from frame 0 are aged out
        for (ch in 0 until 16) {
            val silenceToken = silenceTokens[ch]
            assertEquals("Channel $ch history size must be capped at 64", 64, history.size(ch))
            assertFalse(
                "Initial silence token $silenceToken in channel $ch must be evicted after 120 frames",
                history.contains(ch, silenceToken)
            )
            assertEquals("Silence token frequency count must be 0", 0, history.count(ch, silenceToken))

            // Verify logits for silence token are unaffected by repetition penalty
            val testLogits = FloatArray(1024) { 8.0f }
            history.applyPenalty(ch, testLogits, penalty)

            assertEquals(
                "Valid recurring silence or vowel code beyond 64 frames must not be penalized in channel $ch",
                8.0f,
                testLogits[silenceToken],
                1e-5f
            )
        }
    }

    /**
     * Scenario 3: Assert that quickSelectTopK yields identical top-256 elements
     * compared to full sort across multiple distributions.
     */
    @Test
    fun testQuickSelectTopKEquivalence() {
        val rng = Random(42)

        // Test 1: Random Gaussian / Uniform distribution
        repeat(10) { trial ->
            val logits = FloatArray(1024) { rng.nextFloat() * 20f - 10f }

            val expectedTop256 = logits.indices
                .sortedWith(compareByDescending<Int> { logits[it] }.thenBy { it })
                .take(256)

            val actualTop256 = VieNeuOnnxEngine.quickSelectTopK(logits, 256)

            assertEquals("Output size must be 256", 256, actualTop256.size)
            assertEquals(
                "Top-256 element sets must be strictly identical for trial $trial",
                expectedTop256.toSet(),
                actualTop256.toSet()
            )
            assertEquals(
                "Top-256 elements must be ordered in exact descending order for trial $trial",
                expectedTop256,
                actualTop256.toList()
            )

            // Test k = 25 (standard TTS setting)
            val expectedTop25 = expectedTop256.take(25)
            val actualTop25 = VieNeuOnnxEngine.quickSelectTopK(logits, 25)
            assertEquals("Top-25 elements must match", expectedTop25, actualTop25.toList())
        }

        // Test 2: Logits with duplicate values (ties)
        val duplicateLogits = FloatArray(1024) { (it % 10).toFloat() }
        val expectedDups = duplicateLogits.indices
            .sortedWith(compareByDescending<Int> { duplicateLogits[it] }.thenBy { it })
            .take(256)
        val actualDups = VieNeuOnnxEngine.quickSelectTopK(duplicateLogits, 256)
        assertEquals("Duplicate logits top-256 sets must be equivalent", expectedDups.toSet(), actualDups.toSet())
        assertEquals("Duplicate logits top-256 ordering must be equivalent", expectedDups, actualDups.toList())

        // Test 3: Already sorted logits
        val sortedLogits = FloatArray(1024) { 1024f - it }
        val actualSorted = VieNeuOnnxEngine.quickSelectTopK(sortedLogits, 256)
        val expectedSorted = (0 until 256).toList()
        assertEquals(expectedSorted, actualSorted.toList())

        // Test 4: Reverse sorted logits
        val reverseSortedLogits = FloatArray(1024) { it.toFloat() }
        val actualReverse = VieNeuOnnxEngine.quickSelectTopK(reverseSortedLogits, 256)
        val expectedReverse = (1023 downTo 1023 - 255).toList()
        assertEquals(expectedReverse, actualReverse.toList())

        // Test 5: All identical logits
        val allEqualLogits = FloatArray(1024) { 1.5f }
        val actualAllEqual = VieNeuOnnxEngine.quickSelectTopK(allEqualLogits, 256)
        val expectedAllEqual = (0 until 256).toList()
        assertEquals(expectedAllEqual, actualAllEqual.toList())
    }

    /**
     * Scenario 4: Assert that no arrays or objects are reallocated per step
     * in the hot sampling loop.
     */
    @Test
    fun testZeroAllocationSampling() {
        val engine = createEngine() ?: return
        try {
            val history = RepetitionHistory(nChannels = 16, window = 64, vocabSize = 1024)
            val rng = Random(12345)
            val logits = FloatArray(1024) { rng.nextFloat() * 10f - 5f }

            // 1. Warm-up loop to initialize any lazy state or JIT compilation
            for (step in 0 until 100) {
                val token = (step * 7) % 1024
                history.add(0, token)
                engine.topKSample(
                    logits = logits,
                    k = 25,
                    temperature = 0.8f,
                    topP = 0.95f,
                    repetitionPenalty = 1.2f,
                    repetitionHistory = history,
                    channel = 0
                )
            }

            // 2. Capture identity hash codes of all pre-allocated internal buffers
            fun getFieldIdentity(fieldName: String): Int {
                val field = VieNeuOnnxEngine::class.java.getDeclaredField(fieldName)
                field.isAccessible = true
                val obj = field.get(engine)
                return System.identityHashCode(obj)
            }

            val initialWorkingLogitsId = getFieldIdentity("workingLogitsBuffer")
            val initialAllIndicesId = getFieldIdentity("allIndicesBuffer")
            val initialTopKIndicesId = getFieldIdentity("topKIndicesBuffer")
            val initialTopKLogitsId = getFieldIdentity("topKLogitsBuffer")
            val initialProbsId = getFieldIdentity("probsBuffer")

            // 3. Runtime ThreadMXBean memory check via reflection (available on OpenJDK/JVM)
            val threadBean = try {
                val mfClass = Class.forName("java.lang.management.ManagementFactory")
                mfClass.getMethod("getThreadMXBean").invoke(null)
            } catch (_: Exception) {
                null
            }

            val isSupported = try {
                val suppMethod = threadBean?.javaClass?.getMethod("isThreadAllocatedMemorySupported")
                (suppMethod?.invoke(threadBean) as? Boolean) == true
            } catch (_: Exception) {
                false
            }

            val getThreadAllocatedBytesMethod = if (isSupported) {
                try {
                    threadBean?.javaClass?.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
                } catch (_: Exception) {
                    null
                }
            } else {
                null
            }

            val currentThreadId = Thread.currentThread().id
            val startBytes = (getThreadAllocatedBytesMethod?.invoke(threadBean, currentThreadId) as? Long) ?: 0L

            val numSteps = 500
            for (step in 0 until numSteps) {
                val token = (step * 13) % 1024
                history.add(0, token)
                engine.topKSample(
                    logits = logits,
                    k = 25,
                    temperature = 0.8f,
                    topP = 0.95f,
                    repetitionPenalty = 1.2f,
                    repetitionHistory = history,
                    channel = 0
                )

                // Verify buffers remain strictly identical (zero reallocations per step)
                assertEquals("workingLogitsBuffer must not be reallocated at step $step", initialWorkingLogitsId, getFieldIdentity("workingLogitsBuffer"))
                assertEquals("allIndicesBuffer must not be reallocated at step $step", initialAllIndicesId, getFieldIdentity("allIndicesBuffer"))
                assertEquals("topKIndicesBuffer must not be reallocated at step $step", initialTopKIndicesId, getFieldIdentity("topKIndicesBuffer"))
                assertEquals("topKLogitsBuffer must not be reallocated at step $step", initialTopKLogitsId, getFieldIdentity("topKLogitsBuffer"))
                assertEquals("probsBuffer must not be reallocated at step $step", initialProbsId, getFieldIdentity("probsBuffer"))
            }

            if (getThreadAllocatedBytesMethod != null) {
                val endBytes = getThreadAllocatedBytesMethod.invoke(threadBean, currentThreadId) as Long
                val allocatedBytes = endBytes - startBytes
                assertEquals(
                    "Hot sampling loop must execute with exactly ZERO heap allocations ($numSteps steps)",
                    0L,
                    allocatedBytes
                )
            }
        } finally {
            engine.close()
        }
    }
}
