package com.skul9x.doctruyen.tts.vieneu

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Verification test for Phase 05: Matrix Unrolling (ILP) & Acoustic Tail Trimming.
 *
 * Scenarios:
 * 1. testUnrolledDotProductNumericalEquivalence:
 *    Compare unrolled 4-accumulator dot product against unoptimized scalar loop over random 768-dim
 *    vectors across 1024 vocabulary rows, including tail handling ($H % 4 != 0$).
 * 2. testAcousticTailTrimmingOnSyntheticBabble:
 *    Generate synthetic waveform with 1 main speech burst followed by 2 trailing babble bursts;
 *    assert trimBabbleTail() cuts after burst 1 and applies a smooth micro-fade.
 * 3. testPreserveLegitimateBursts:
 *    Assert that waveform matching exact syllable count is NOT altered by trimming.
 * 4. testInferTimingTrimmingBypass:
 *    Verify that when trimming succeeds, TtsMetricsLogger logs 0 neural retry passes.
 */
class MatrixOptimizationAndAcousticTrimmingTest {

    private val sampleRate = 48000
    private var engine: VieNeuOnnxEngine? = null

    private fun getBaseAssetsDir(): File {
        val path1 = File("app/src/main/assets/vieneu")
        return if (path1.exists()) path1 else File("src/main/assets/vieneu")
    }

    @Before
    fun setUp() {
        TtsMetricsLogger.startSession(storyId = "test_phase_05", storyTitle = "Phase 05 Test")
    }

    @After
    fun tearDown() {
        engine?.close()
        engine = null
    }

    /**
     * Helper to generate synthetic PCM with alternating tone/silence intervals.
     */
    private fun generateSyntheticPcm(
        segments: List<Pair<Int, Boolean>>,
        frequency: Float = 440f,
        amplitude: Float = 0.8f
    ): FloatArray {
        var totalSamples = 0
        for ((durationMs, _) in segments) {
            totalSamples += (sampleRate * durationMs) / 1000
        }
        val pcm = FloatArray(totalSamples)
        var offset = 0

        for ((durationMs, isTone) in segments) {
            val count = (sampleRate * durationMs) / 1000
            for (i in 0 until count) {
                if (isTone) {
                    val t = i.toDouble() / sampleRate
                    pcm[offset + i] = (amplitude * sin(2.0 * PI * frequency * t)).toFloat()
                } else {
                    pcm[offset + i] = 0f
                }
            }
            offset += count
        }
        return pcm
    }

    /**
     * Test Scenario 1: testUnrolledDotProductNumericalEquivalence
     * Compare unrolled 4-accumulator dot product against unoptimized scalar loop over random 768-dim
     * vectors across 1024 vocabulary rows. Also verify tail handling when H % 4 != 0.
     */
    @Test
    fun testUnrolledDotProductNumericalEquivalence() {
        val H = 768
        val vocabSize = 1024
        val rng = Random(42)

        val vec = FloatArray(H) { (rng.nextFloat() * 2f) - 1f }
        val flatEmb = FloatArray(vocabSize * H) { (rng.nextFloat() * 2f) - 1f }

        // 1. Verify equivalence across all 1024 vocabulary rows for H = 768 (evenly divisible by 4)
        var maxDiff = 0f
        for (v in 0 until vocabSize) {
            val offset = v * H
            val scalar = VieNeuOnnxEngine.computeScalarDot(vec, flatEmb, offset, H)
            val unrolled = VieNeuOnnxEngine.computeUnrolledDot(vec, flatEmb, offset, H)
            val diff = abs(unrolled - scalar)
            if (diff > maxDiff) maxDiff = diff
            assertTrue(
                "Row $v dot product mismatch: scalar=$scalar, unrolled=$unrolled, diff=$diff",
                diff < 1e-4f
            )
        }
        println("Max float difference between unrolled and scalar over 1024 rows: $maxDiff")

        // 2. Test tail handling with H not divisible by 4 (e.g. H = 771, remainder = 3)
        val oddH = 771
        val oddVec = FloatArray(oddH) { (rng.nextFloat() * 2f) - 1f }
        val oddFlatEmb = FloatArray(10 * oddH) { (rng.nextFloat() * 2f) - 1f }
        for (v in 0 until 10) {
            val offset = v * oddH
            val scalar = VieNeuOnnxEngine.computeScalarDot(oddVec, oddFlatEmb, offset, oddH)
            val unrolled = VieNeuOnnxEngine.computeUnrolledDot(oddVec, oddFlatEmb, offset, oddH)
            val diff = abs(unrolled - scalar)
            assertTrue(
                "Odd H row $v dot product mismatch: scalar=$scalar, unrolled=$unrolled, diff=$diff",
                diff < 1e-4f
            )
        }

        // 3. Performance Micro-benchmark: Verify unrolled loop efficiency
        // Warm-up
        repeat(5) {
            for (v in 0 until vocabSize) {
                VieNeuOnnxEngine.computeScalarDot(vec, flatEmb, v * H, H)
                VieNeuOnnxEngine.computeUnrolledDot(vec, flatEmb, v * H, H)
            }
        }

        val iterations = 50
        val t0 = System.nanoTime()
        repeat(iterations) {
            for (v in 0 until vocabSize) {
                VieNeuOnnxEngine.computeScalarDot(vec, flatEmb, v * H, H)
            }
        }
        val scalarTimeNs = System.nanoTime() - t0

        val t1 = System.nanoTime()
        repeat(iterations) {
            for (v in 0 until vocabSize) {
                VieNeuOnnxEngine.computeUnrolledDot(vec, flatEmb, v * H, H)
            }
        }
        val unrolledTimeNs = System.nanoTime() - t1

        println("Scalar dot product time: ${scalarTimeNs / 1_000_000.0}ms, Unrolled dot product time: ${unrolledTimeNs / 1_000_000.0}ms")
        assertTrue(
            "Unrolled dot product should be faster or comparable to scalar (unrolled: ${unrolledTimeNs}ns, scalar: ${scalarTimeNs}ns)",
            unrolledTimeNs <= scalarTimeNs * 1.15
        )
    }

    /**
     * Test Scenario 2: testAcousticTailTrimmingOnSyntheticBabble
     * Generate synthetic waveform with 1 main speech burst followed by 2 trailing babble bursts;
     * assert trimBabbleTail() cuts after burst 1 and applies a smooth micro-fade.
     */
    @Test
    fun testAcousticTailTrimmingOnSyntheticBabble() {
        // Construct 3-burst synthetic PCM:
        // Burst 1 (target syllable): 160ms tone
        // Silence: 120ms (> 80ms minGap)
        // Burst 2 (babble burst 1): 80ms tone
        // Silence: 100ms
        // Burst 3 (babble burst 2): 80ms tone
        // Silence: 50ms
        val pcm = generateSyntheticPcm(
            listOf(
                50 to false,
                160 to true,
                120 to false,
                80 to true,
                100 to false,
                80 to true,
                50 to false
            )
        )

        val initialBursts = BabbleGuard.countSpeechBursts(pcm, sampleRate)
        assertEquals("Initial synthetic audio must contain exactly 3 distinct bursts", 3, initialBursts)

        val bursts = BabbleGuard.findSpeechBursts(pcm, sampleRate)
        assertEquals(3, bursts.size)

        // Trim targeting 1 syllable
        val trimmed = BabbleGuard.trimBabbleTail(pcm, targetSyllables = 1, sampleRate = sampleRate)
        assertNotNull("Trimming should succeed and return non-null for excess bursts", trimmed)
        val trimmedPcm = trimmed!!

        assertTrue("Trimmed audio must be significantly shorter than original", trimmedPcm.size < pcm.size)

        val trimmedBursts = BabbleGuard.countSpeechBursts(trimmedPcm, sampleRate)
        assertEquals("Trimmed audio must contain exactly 1 syllable burst", 1, trimmedBursts)

        // Verify 5ms micro-fade: last sample must be attenuated to near zero
        val fadeSamples = (sampleRate * 5) / 1000
        assertTrue("Trimmed length must exceed fade window", trimmedPcm.size > fadeSamples)
        val lastSample = trimmedPcm.last()
        assertTrue(
            "Last sample must be smoothly faded down to near zero (got: $lastSample)",
            abs(lastSample) < 1e-3f
        )

        // Also verify trimming with targetSyllables = 2 on a 3-burst signal
        val trimmed2 = BabbleGuard.trimBabbleTail(pcm, targetSyllables = 2, sampleRate = sampleRate)
        assertNotNull(trimmed2)
        assertEquals("Targeting 2 syllables must preserve exactly 2 bursts", 2, BabbleGuard.countSpeechBursts(trimmed2!!, sampleRate))
        assertTrue("Targeting 2 syllables must yield longer audio than targeting 1 syllable", trimmed2.size > trimmedPcm.size)
    }

    /**
     * Test Scenario 3: testPreserveLegitimateBursts
     * Assert that waveform matching exact syllable count is NOT altered by trimming.
     */
    @Test
    fun testPreserveLegitimateBursts() {
        // Construct 2-burst synthetic PCM matching 2-syllable utterance:
        val twoBurstPcm = generateSyntheticPcm(
            listOf(
                50 to false,
                150 to true,
                120 to false,
                150 to true,
                50 to false
            )
        )
        val burstCount = BabbleGuard.countSpeechBursts(twoBurstPcm, sampleRate)
        assertEquals(2, burstCount)

        // Calling trimBabbleTail for 2 syllables should return null (preserve intact)
        val trimmed = BabbleGuard.trimBabbleTail(twoBurstPcm, targetSyllables = 2, sampleRate = sampleRate)
        assertNull("trimBabbleTail must return null when burstCount <= targetSyllables", trimmed)

        // Calling trimBabbleTail for 3 syllables on 2-burst audio should also return null
        val trimmed3 = BabbleGuard.trimBabbleTail(twoBurstPcm, targetSyllables = 3, sampleRate = sampleRate)
        assertNull("trimBabbleTail must return null when targetSyllables > burstCount", trimmed3)

        // Clean 1-burst audio with targetSyllables = 1 should return null
        val oneBurstPcm = generateSyntheticPcm(
            listOf(
                50 to false,
                150 to true,
                50 to false
            )
        )
        val trimmed1 = BabbleGuard.trimBabbleTail(oneBurstPcm, targetSyllables = 1, sampleRate = sampleRate)
        assertNull("trimBabbleTail must return null for 1-burst audio when targetSyllables = 1", trimmed1)
    }

    /**
     * Test Scenario 4: testInferTimingTrimmingBypass
     * Verify that when acoustic trimming succeeds on suspect short utterance,
     * TtsMetricsLogger logs 0 neural retry passes.
     */
    @Test
    fun testInferTimingTrimmingBypass() {
        val baseAssetsDir = getBaseAssetsDir()
        val backboneDir = File(baseAssetsDir, "backbone")
        val codecDir = File(baseAssetsDir, "codec")
        val voicesFile = File(baseAssetsDir, "voices_v3_turbo.json")

        assertTrue(backboneDir.exists())
        assertTrue(codecDir.exists())

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

        // Generate synthetic PCM: 1 main burst + 1 babble burst (total 2 bursts for 1-syllable input)
        val suspectPcm = generateSyntheticPcm(
            listOf(
                50 to false,
                160 to true,
                120 to false,
                90 to true,
                50 to false
            )
        )
        assertEquals("Generated suspect audio must have 2 bursts", 2, BabbleGuard.countSpeechBursts(suspectPcm, sampleRate))

        var synthesizePassCallCount = 0

        // Subclass VieNeuOnnxEngine to intercept synthesizePass and produce suspect 2-burst audio on pass 0
        val testEngine = object : VieNeuOnnxEngine(
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
        ) {
            override fun synthesizePass(
                phonemes: String,
                speakerEmb: FloatArray?,
                refCodes: Array<IntArray>?,
                temperature: Float,
                topK: Int,
                topP: Float,
                maxNewFrames: Int,
                repetitionPenalty: Float
            ): SynthesisPassResult {
                synthesizePassCallCount++
                return SynthesisPassResult(
                    pcm = suspectPcm.copyOf(),
                    frames = List(8) { IntArray(config.nVq) },
                    backboneDurationMs = 120L,
                    codecDurationMs = 30L
                )
            }
        }
        engine = testEngine

        // Reset metrics logger
        TtsMetricsLogger.startSession(storyId = "bypass_test", storyTitle = "Bypass Test")
        assertEquals(0, TtsMetricsLogger.getBabbleRetryCount())

        // "Vâng." is a 1-syllable word. Initial pass returns 2 bursts (suspect!).
        val result = testEngine.inferWithTiming("Vâng.")

        // Verify that synthesizePass was only called ONCE (the initial pass, zero retries!)
        assertEquals("synthesizePass should be called exactly once due to acoustic trimming bypass", 1, synthesizePassCallCount)

        // Verify that 0 neural retry passes were recorded in TtsMetricsLogger
        assertEquals("Babble retry count must be 0 when acoustic trimming successfully resolves babble", 0, TtsMetricsLogger.getBabbleRetryCount())

        // Verify returned PCM has exactly 1 burst (trimmed to match 1 syllable)
        val finalBursts = BabbleGuard.countSpeechBursts(result.pcm, sampleRate)
        assertEquals("Output PCM must have exactly 1 syllable burst after acoustic trimming", 1, finalBursts)
        assertTrue("Output PCM size should be trimmed smaller than the original suspect PCM", result.pcm.size < suspectPcm.size)
    }
}

