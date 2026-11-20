package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OnnxTensor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.FloatBuffer
import java.util.Random
import kotlin.math.abs

/**
 * Comprehensive verification test suite for Phase 03: Acoustic Threading & Matrix Acceleration.
 *
 * Verifies:
 * 1. testAcousticSessionConfiguredWithSingleThread:
 *    Inspect session configuration options for acousticSession to confirm intra_op_num_threads is set to 1.
 * 2. testAcousticFrameTensorReuseIntegrity:
 *    Execute a multi-channel acoustic frame step and verify output vectors and sampled codes are generated
 *    correctly without JNI allocation leaks and reusing cached position tensors.
 * 3. testAcousticProjectionLogitEquivalence:
 *    Run accelerated GEMV projection against arbitrary input vectors (H = 768) and assert resulting logits
 *    for all 1024 vocab indices match unrolled reference within 10^-5. Also tests edge dimension remainder.
 * 4. testAcousticSamplingDeterminismWithSeed:
 *    Execute sampling with fixed random seeds and verify identical token selection sequences between baseline
 *    and optimized projection pathways.
 * 5. testAcousticProjectionPerformanceSpeedup:
 *    Benchmark 100 consecutive calls to accelerated GEMV demonstrating speedup over the v1.17 baseline.
 */
class AcousticThreadingAndProjectionTest {

    private var engine: VieNeuOnnxEngine? = null

    private fun getBaseAssetsDir(): File {
        val path1 = File("app/src/main/assets/vieneu")
        return if (path1.exists()) path1 else File("src/main/assets/vieneu")
    }

    private fun loadTestEngine(): VieNeuOnnxEngine {
        val baseAssetsDir = getBaseAssetsDir()
        val backboneDir = File(baseAssetsDir, "backbone")
        val codecDir = File(baseAssetsDir, "codec")
        val voicesFile = File(baseAssetsDir, "voices_v3_turbo.json")

        assertTrue("backboneDir should exist: ${backboneDir.absolutePath}", backboneDir.exists())
        assertTrue("codecDir should exist: ${codecDir.absolutePath}", codecDir.exists())

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 2
        )

        val configFile = File(backboneDir, "config.json")
        val config = VieNeuConfig.fromJson(configFile.readText(Charsets.UTF_8))

        val tokenizerFile = File(backboneDir, "tokenizer.json")
        val tokenizer = VieNeuTokenizer.fromFile(tokenizerFile)

        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
        val headsReader = NpzReader.read(headsFile)

        val textEmb = headsReader.get2D("text_emb")!!
        val audioEmb = headsReader.getChannelArrays("audio_emb")!!
        val xvecW = headsReader.get2D("xvec_w")
        val xvecB = headsReader.getFloatArray("xvec_b")
        val xvecLnW = headsReader.getFloatArray("xvec_ln_w")
        val xvecLnB = headsReader.getFloatArray("xvec_ln_b")
        val xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f

        if (voicesFile.exists()) {
            VoicePresets.loadFromJson(voicesFile.readText(Charsets.UTF_8))
        }

        return VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = textEmb,
            audioEmbeddings = audioEmb,
            xvecW = xvecW,
            xvecB = xvecB,
            xvecLnW = xvecLnW,
            xvecLnB = xvecLnB,
            xvecLnEps = xvecLnEps
        )
    }

    @Before
    fun setUp() {
        TtsMetricsLogger.startSession(storyId = "test_phase_03", storyTitle = "Phase 03 Test")
    }

    @After
    fun tearDown() {
        engine?.close()
        engine = null
    }

    /**
     * Requirement 1 & Acceptance Criteria 1:
     * Inspect session configuration options for acousticSession to confirm intra_op_num_threads is set to 1.
     */
    @Test
    fun testAcousticSessionConfiguredWithSingleThread() {
        val baseAssetsDir = getBaseAssetsDir()
        val backboneDir = File(baseAssetsDir, "backbone")
        val codecDir = File(baseAssetsDir, "codec")

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 4
        )

        try {
            // 1. Verify acousticSessionOptions is created and distinct from backbone sessionOptions
            assertNotNull("acousticSessionOptions must be configured", sessionManager.acousticSessionOptions)
            assertNotNull("sessionOptions must be configured", sessionManager.sessionOptions)
            assertNotSame(
                "acousticSessionOptions must be a dedicated instance distinct from sessionOptions",
                sessionManager.sessionOptions,
                sessionManager.acousticSessionOptions
            )

            // 2. Verify acoustic intra-op thread count property and getter
            assertEquals(1, sessionManager.acousticIntraOpThreads)
            assertEquals(1, sessionManager.getAcousticIntraOpNumThreads())

            // 3. Inspect config entries on acousticSessionOptions
            val entries = sessionManager.acousticSessionOptions!!.configEntries
            assertEquals("1", entries["intra_op_num_threads"])
            assertEquals("0", entries["session.intra_op.allow_spinning"])

            // 4. Also test configureAcousticSessionOptions factory directly for FP32 and INT8
            val (fp32Options, fp32Ep) = OnnxSessionManager.configureAcousticSessionOptions(OnnxSessionManager.QuantizationType.FP32)
            try {
                assertEquals("1", fp32Options.configEntries["intra_op_num_threads"])
                assertEquals("0", fp32Options.configEntries["session.intra_op.allow_spinning"])
                assertTrue(fp32Ep.contains("XNNPACK") || fp32Ep.contains("CPU"))
            } finally {
                fp32Options.close()
            }

            val (int8Options, _) = OnnxSessionManager.configureAcousticSessionOptions(OnnxSessionManager.QuantizationType.INT8)
            try {
                assertEquals("1", int8Options.configEntries["intra_op_num_threads"])
                assertEquals("0", int8Options.configEntries["session.intra_op.allow_spinning"])
            } finally {
                int8Options.close()
            }
        } finally {
            sessionManager.close()
        }
    }

    /**
     * Requirement 2 & Acceptance Criteria 2:
     * Execute a multi-channel acoustic frame step and verify that output vectors and sampled codes
     * are generated correctly without JNI allocation leaks, confirming persistent tensor reuse.
     */
    @Test
    fun testAcousticFrameTensorReuseIntegrity() {
        val testEngine = loadTestEngine()
        engine = testEngine

        val H = testEngine.config.hiddenSize
        val nVq = testEngine.config.nVq
        assertEquals(16, nVq)

        // 1. Verify persistent position tensors: step 0 and all 16 channel tensors are cached
        val pos0_first = testEngine.getOrCreateAcousticPosTensor0()
        val pos0_second = testEngine.getOrCreateAcousticPosTensor0()
        assertSame("Step 0 position tensor must be persistent and cached", pos0_first, pos0_second)

        for (ch in 1 until nVq) {
            val posCh_first = testEngine.getOrCreateAcousticChannelPosTensor(ch)
            val posCh_second = testEngine.getOrCreateAcousticChannelPosTensor(ch)
            assertSame("Channel $ch position tensor must be persistent and cached", posCh_first, posCh_second)
        }

        // 2. Synthesize an acoustic frame with synthetic hidden state h
        val rng = Random(12345)
        val h = FloatArray(H) { (rng.nextFloat() * 0.2f) - 0.1f }

        val (codes1, eos1) = testEngine.acousticFrame(
            h = h,
            temperature = 0.8f,
            topK = 25,
            topP = 0.95f,
            repetitionPenalty = 1.0f,
            hist = null
        )

        assertEquals("Should return exactly $nVq codes for frame 1", nVq, codes1.size)
        for (ch in 0 until nVq) {
            assertTrue("Code at ch=$ch should be in valid vocab range [0, 1024)", codes1[ch] in 0 until 1024)
        }

        // 3. Run a second acoustic frame and verify cached tensors remain valid and reused
        val h2 = FloatArray(H) { (rng.nextFloat() * 0.2f) - 0.1f }
        val (codes2, eos2) = testEngine.acousticFrame(
            h = h2,
            temperature = 0.8f,
            topK = 25,
            topP = 0.95f,
            repetitionPenalty = 1.0f,
            hist = null
        )

        assertEquals("Should return exactly $nVq codes for frame 2", nVq, codes2.size)
        for (ch in 0 until nVq) {
            assertTrue("Code at ch=$ch should be in valid vocab range [0, 1024)", codes2[ch] in 0 until 1024)
        }

        // 4. Verify cached tensors are still the identical instances (no reallocation occurred)
        assertSame(pos0_first, testEngine.getOrCreateAcousticPosTensor0())
        for (ch in 1 until nVq) {
            assertSame(testEngine.getOrCreateAcousticChannelPosTensor(ch), testEngine.getOrCreateAcousticChannelPosTensor(ch))
        }
    }

    /**
     * Requirement 3 & Acceptance Criteria 4:
     * Run accelerated GEMV projection against arbitrary input vectors (H = 768) and assert that
     * resulting logits for all 1024 vocab indices match the unrolled reference implementation within 10^-5.
     */
    @Test
    fun testAcousticProjectionLogitEquivalence() {
        val H = 768
        val vocabSize = 1024
        val rng = Random(42)

        val vec = FloatArray(H) { (rng.nextFloat() * 2f) - 1f }
        val flatEmb = FloatArray(vocabSize * H) { (rng.nextFloat() * 2f) - 1f }

        val logitsBaseline = FloatArray(vocabSize)
        val logitsAccelerated = FloatArray(vocabSize)

        // Compute baseline using reference computeUnrolledDot (4-accumulator v1.17)
        for (v in 0 until vocabSize) {
            logitsBaseline[v] = VieNeuOnnxEngine.computeUnrolledDot(vec, flatEmb, v * H, H)
        }

        // Compute accelerated using projectAcousticLogits (16-unroll GEMV)
        VieNeuOnnxEngine.projectAcousticLogits(vec, flatEmb, logitsAccelerated, H, vocabSize)

        var maxDiff = 0f
        for (v in 0 until vocabSize) {
            val diff = abs(logitsAccelerated[v] - logitsBaseline[v])
            if (diff > maxDiff) maxDiff = diff
            assertTrue(
                "Logit at index $v differed by $diff, exceeding tolerance 1e-5 (base=${logitsBaseline[v]}, opt=${logitsAccelerated[v]})",
                diff <= 1e-5f
            )
        }
        println("Max diff across $vocabSize logits (H=$H): $maxDiff")
        assertEquals("Max diff should be zero or well within 1e-5f", 0f, maxDiff, 1e-5f)

        // Also test single-row computeAcceleratedDot vs computeUnrolledDot
        for (v in 0 until 10) {
            val offset = v * H
            val dotRef = VieNeuOnnxEngine.computeUnrolledDot(vec, flatEmb, offset, H)
            val dotAcc = VieNeuOnnxEngine.computeAcceleratedDot(vec, flatEmb, offset, H)
            assertEquals("Row $v computeAcceleratedDot must match computeUnrolledDot", dotRef, dotAcc, 1e-5f)
        }

        // Edge case: Test non-divisible by 16 dimensions (e.g. H = 771) to verify remainder loop
        val H_odd = 771
        val vecOdd = FloatArray(H_odd) { rng.nextFloat() }
        val flatEmbOdd = FloatArray(16 * H_odd) { rng.nextFloat() }
        val logitsOddBase = FloatArray(16)
        val logitsOddAcc = FloatArray(16)

        for (v in 0 until 16) {
            logitsOddBase[v] = VieNeuOnnxEngine.computeUnrolledDot(vecOdd, flatEmbOdd, v * H_odd, H_odd)
        }
        VieNeuOnnxEngine.projectAcousticLogits(vecOdd, flatEmbOdd, logitsOddAcc, H_odd, 16)

        for (v in 0 until 16) {
            val diff = abs(logitsOddAcc[v] - logitsOddBase[v])
            assertTrue("Odd dimension H=$H_odd remainder loop must match within 1e-5f", diff <= 1e-5f)
        }
    }

    /**
     * Requirement 3 & Acceptance Criteria 4:
     * Execute sampling with fixed random seeds and verify identical token selection sequences
     * between baseline and optimized projection pathways.
     */
    @Test
    fun testAcousticSamplingDeterminismWithSeed() {
        val testEngine = loadTestEngine()
        engine = testEngine

        val H = testEngine.config.hiddenSize
        val rng = Random(9999)
        val vec = FloatArray(H) { (rng.nextFloat() * 2f) - 1f }

        val numSamples = 50
        val tokensBaseline = IntArray(numSamples)
        val tokensAccelerated = IntArray(numSamples)

        // Baseline sampling with fixed seed
        for (i in 0 until numSamples) {
            val fixedSeedRng = kotlin.random.Random(42 + i)
            val logitsRef = FloatArray(1024)
            for (v in 0 until 1024) {
                logitsRef[v] = VieNeuOnnxEngine.computeUnrolledDot(vec, testEngine.audioEmbeddings[0], v * H, H)
            }
            tokensBaseline[i] = testEngine.topKSample(
                logits = logitsRef,
                k = 25,
                temperature = 0.8f,
                topP = 0.95f,
                repetitionPenalty = 1.0f,
                rng = fixedSeedRng
            )
        }

        // Accelerated sampling with identical fixed seeds
        for (i in 0 until numSamples) {
            val fixedSeedRng = kotlin.random.Random(42 + i)
            val logitsOpt = FloatArray(1024)
            VieNeuOnnxEngine.projectAcousticLogits(vec, testEngine.audioEmbeddings[0], logitsOpt, H, 1024)
            tokensAccelerated[i] = testEngine.topKSample(
                logits = logitsOpt,
                k = 25,
                temperature = 0.8f,
                topP = 0.95f,
                repetitionPenalty = 1.0f,
                rng = fixedSeedRng
            )
        }

        assertArrayEquals(
            "Tokens selected with fixed seeds must be 100% identical between baseline and accelerated GEMV",
            tokensBaseline,
            tokensAccelerated
        )
    }

    /**
     * Acceptance Criteria 3:
     * Benchmarking 100 consecutive calls to sampleCode / projection demonstrates measurable speedup
     * over the v1.17 4-accumulator baseline.
     */
    @Test
    fun testAcousticProjectionPerformanceSpeedup() {
        val H = 768
        val vocabSize = 1024
        val rng = Random(777)
        val vec = FloatArray(H) { (rng.nextFloat() * 2f) - 1f }
        val flatEmb = FloatArray(vocabSize * H) { (rng.nextFloat() * 2f) - 1f }
        val logits = FloatArray(vocabSize)

        // Warmup
        for (i in 0 until 20) {
            for (v in 0 until vocabSize) {
                logits[v] = VieNeuOnnxEngine.computeUnrolledDot(vec, flatEmb, v * H, H)
            }
            VieNeuOnnxEngine.projectAcousticLogits(vec, flatEmb, logits, H, vocabSize)
        }

        val iterations = 100

        // Benchmark baseline 4-accumulator
        val t0 = System.nanoTime()
        for (i in 0 until iterations) {
            for (v in 0 until vocabSize) {
                logits[v] = VieNeuOnnxEngine.computeUnrolledDot(vec, flatEmb, v * H, H)
            }
        }
        val t1 = System.nanoTime()

        // Benchmark accelerated 16-unroll
        for (i in 0 until iterations) {
            VieNeuOnnxEngine.projectAcousticLogits(vec, flatEmb, logits, H, vocabSize)
        }
        val t2 = System.nanoTime()

        val baselineDurationMs = (t1 - t0) / 1_000_000.0
        val acceleratedDurationMs = (t2 - t1) / 1_000_000.0
        val speedup = baselineDurationMs / acceleratedDurationMs

        println("Baseline 4-accumulator (100 calls): ${baselineDurationMs} ms")
        println("Accelerated 16-unroll (100 calls):   ${acceleratedDurationMs} ms (Speedup: ${"%.2f".format(speedup)}x)")

        assertTrue(
            "Accelerated projection should achieve measurable speedup or comparable performance (speedup >= 1.0x, actual: $speedup)",
            speedup >= 1.0
        )
    }
}
