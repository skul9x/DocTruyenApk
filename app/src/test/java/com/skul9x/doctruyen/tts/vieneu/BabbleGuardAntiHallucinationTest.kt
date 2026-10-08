package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Verification test for Phase 04: Babble Guard Anti-Hallucination & Burst Detection.
 *
 * Test Scenarios:
 * 1. testBurstCountCalculation: Verify burst counting on synthetic 1-burst vs 2-burst audio signals.
 * 2. testBabbleSuspectDetection: Assert that 1-syllable utterance with 2 speech bursts is flagged as suspect.
 * 3. testFrameCapCeilingTrigger: Assert that 1-syllable utterance hitting frame cap (13 frames) triggers babble suspect.
 * 4. testCandidateSelectionPreference: Verify babblePrefer correctly selects clean 1-burst candidate over 2-burst noisy candidate.
 */
class BabbleGuardAntiHallucinationTest {

    private val sampleRate = 48000

    @Before
    fun setUp() {
        TtsMetricsLogger.startSession(storyId = "test_story", storyTitle = "Test")
    }

    /**
     * Helper to synthesize test PCM containing specified segments of (durationMs, isTone).
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
     * Test Scenario 1: testBurstCountCalculation
     * Verify burst counting on synthetic 1-burst vs 2-burst audio signals.
     */
    @Test
    fun testBurstCountCalculation() {
        // 1-burst signal: 50ms silence, 150ms tone (>= 60ms), 50ms silence
        val oneBurstPcm = generateSyntheticPcm(
            listOf(
                50 to false,
                150 to true,
                50 to false
            )
        )
        val count1 = BabbleGuard.countSpeechBursts(oneBurstPcm, sampleRate)
        assertEquals("Single continuous tone (150ms) must count as 1 burst", 1, count1)
        assertEquals("countBursts alias must return identical result", 1, BabbleGuard.countBursts(oneBurstPcm, sampleRate))

        // 2-burst signal: 50ms silence, 150ms tone, 120ms silence (>= 80ms min gap), 150ms tone, 50ms silence
        val twoBurstPcm = generateSyntheticPcm(
            listOf(
                50 to false,
                150 to true,
                120 to false,
                150 to true,
                50 to false
            )
        )
        val count2 = BabbleGuard.countSpeechBursts(twoBurstPcm, sampleRate)
        assertEquals("Two tones separated by 120ms silence must count as 2 bursts", 2, count2)

        // Gap bridging: 150ms tone, 40ms silence (< 80ms min gap), 150ms tone -> bridged into 1 burst
        val bridgedPcm = generateSyntheticPcm(
            listOf(
                50 to false,
                150 to true,
                40 to false,
                150 to true,
                50 to false
            )
        )
        val bridgedCount = BabbleGuard.countSpeechBursts(bridgedPcm, sampleRate)
        assertEquals("Two tones separated by <80ms gap must be bridged into 1 burst", 1, bridgedCount)

        // Transient suppression: 30ms tone (< 60ms min burst duration) -> 0 bursts
        val transientPcm = generateSyntheticPcm(
            listOf(
                50 to false,
                30 to true,
                50 to false
            )
        )
        val transientCount = BabbleGuard.countSpeechBursts(transientPcm, sampleRate)
        assertEquals("Transient noise under 60ms must be discarded (0 bursts)", 0, transientCount)

        // Pure silence -> 0 bursts
        val silencePcm = FloatArray(sampleRate) // 1 second silence
        assertEquals(0, BabbleGuard.countSpeechBursts(silencePcm, sampleRate))

        // Non-functional requirement check: execution efficiency < 2ms on typical short PCM (< 2.0s)
        val twoSecPcm = generateSyntheticPcm(listOf(200 to true, 100 to false, 300 to true, 1400 to false))
        // Warm-up
        repeat(5) { BabbleGuard.countSpeechBursts(twoSecPcm, sampleRate) }
        val startNano = System.nanoTime()
        val iters = 50
        repeat(iters) { BabbleGuard.countSpeechBursts(twoSecPcm, sampleRate) }
        val avgDurationMs = (System.nanoTime() - startNano) / (iters * 1_000_000.0)
        assertTrue("Burst analysis must take < 2ms (measured avg: ${avgDurationMs}ms)", avgDurationMs < 2.0)
    }

    /**
     * Test Scenario 2: testBabbleSuspectDetection
     * Assert that 1-syllable utterance with 2 speech bursts is flagged as suspect.
     */
    @Test
    fun testBabbleSuspectDetection() {
        // 1-syllable utterance with 2 bursts -> suspect hallucination / trailing noise
        assertTrue(
            "1-syllable utterance with 2 speech bursts must be flagged as suspect",
            BabbleGuard.isBabbleSuspect(burstCount = 2, syllableCount = 1)
        )

        // 1-syllable utterance with 1 burst -> clean, not suspect
        assertFalse(
            "1-syllable utterance with 1 speech burst must not be flagged as suspect",
            BabbleGuard.isBabbleSuspect(burstCount = 1, syllableCount = 1)
        )

        // 2-syllable utterance with 2 bursts -> normal, not suspect
        assertFalse(
            "2-syllable utterance with 2 speech bursts must not be suspect",
            BabbleGuard.isBabbleSuspect(burstCount = 2, syllableCount = 2)
        )

        // 2-syllable utterance with 3 bursts -> suspect hallucination
        assertTrue(
            "2-syllable utterance with 3 bursts must be flagged as suspect",
            BabbleGuard.isBabbleSuspect(burstCount = 3, syllableCount = 2)
        )

        // Long utterance (> 2 syllables) bypasses energy envelope guard
        assertFalse(
            "Long utterance (3 syllables) with 4 bursts must bypass guard (not suspect)",
            BabbleGuard.isBabbleSuspect(burstCount = 4, syllableCount = 3)
        )

        // Direct PCM overload verification
        val twoBurstPcm = generateSyntheticPcm(
            listOf(50 to false, 150 to true, 100 to false, 150 to true, 50 to false)
        )
        assertTrue(
            "PCM with 2 bursts on 1-syllable utterance must be flagged as suspect",
            BabbleGuard.isBabbleSuspect(pcm = twoBurstPcm, syllableCount = 1, sampleRate = sampleRate)
        )

        val oneBurstPcm = generateSyntheticPcm(
            listOf(50 to false, 150 to true, 50 to false)
        )
        assertFalse(
            "PCM with 1 burst on 1-syllable utterance must not be suspect",
            BabbleGuard.isBabbleSuspect(pcm = oneBurstPcm, syllableCount = 1, sampleRate = sampleRate)
        )

        // Syllable counting rules
        assertEquals(1, BabbleGuard.countSyllables("Vâng."))
        assertEquals(1, BabbleGuard.countSyllables("Hả?"))
        assertEquals(1, BabbleGuard.countSyllables("Ừ."))
        assertEquals(2, BabbleGuard.countSyllables("Đúng rồi."))
        assertEquals(3, BabbleGuard.countSyllables("Không phải đâu."))
        assertEquals(1, BabbleGuard.countSyllables("<|emotion_1|>tʃˈaː2w."))
        assertTrue(BabbleGuard.isShortUtterance(1))
        assertTrue(BabbleGuard.isShortUtterance(2))
        assertFalse(BabbleGuard.isShortUtterance(3))
    }

    /**
     * Test Scenario 3: testFrameCapCeilingTrigger
     * Assert that 1-syllable utterance hitting frame cap (13 frames) triggers babble suspect.
     */
    @Test
    fun testFrameCapCeilingTrigger() {
        val singleWordCap = VieNeuOnnxEngine.SINGLE_WORD_MAX_FRAMES // 13 frames

        // 1-syllable utterance hitting frame ceiling (13 frames >= 13 - 1) triggers suspect even with 1 burst
        assertTrue(
            "1-syllable utterance hitting frame cap (13 frames) must trigger babble suspect",
            BabbleGuard.isBabbleSuspect(
                burstCount = 1,
                syllableCount = 1,
                generatedFrames = 13,
                capFrames = singleWordCap
            )
        )

        // 1-syllable utterance hitting cap - 1 (12 frames >= 13 - 1) triggers suspect
        assertTrue(
            "1-syllable utterance reaching frame cap ceiling (12 frames) must trigger babble suspect",
            BabbleGuard.isBabbleSuspect(
                burstCount = 1,
                syllableCount = 1,
                generatedFrames = 12,
                capFrames = singleWordCap
            )
        )

        // 1-syllable utterance with normal frame count (8 frames < 12) with 1 burst does NOT trigger suspect
        assertFalse(
            "1-syllable utterance safely below frame cap with 1 burst must not be suspect",
            BabbleGuard.isBabbleSuspect(
                burstCount = 1,
                syllableCount = 1,
                generatedFrames = 8,
                capFrames = singleWordCap
            )
        )

        // Utterance with > 2 syllables does NOT trigger babble suspect even if reaching frame cap
        assertFalse(
            "Multi-syllable utterance (> 2 syllables) hitting cap must bypass babble guard",
            BabbleGuard.isBabbleSuspect(
                burstCount = 1,
                syllableCount = 3,
                generatedFrames = 13,
                capFrames = singleWordCap
            )
        )
    }

    /**
     * Test Scenario 4: testCandidateSelectionPreference
     * Verify babblePrefer correctly selects clean 1-burst candidate over 2-burst noisy candidate.
     */
    @Test
    fun testCandidateSelectionPreference() {
        // Clean 1-burst candidate (excess bursts = 0)
        val cleanCandidate = BabbleGuard.SynthesisCandidate(
            pcm = FloatArray(24000), // 0.5s
            burstCount = 1,
            syllableCount = 1,
            frameCount = 8
        )

        // Noisy 2-burst candidate (excess bursts = 1)
        val noisyCandidate = BabbleGuard.SynthesisCandidate(
            pcm = FloatArray(48000), // 1.0s
            burstCount = 2,
            syllableCount = 1,
            frameCount = 13
        )

        // Function invoke preference: clean candidate preferred
        val preferred1 = BabbleGuard.babblePrefer(cleanCandidate, noisyCandidate)
        assertEquals("Clean 1-burst candidate must be selected over noisy 2-burst candidate", cleanCandidate, preferred1)

        val preferred2 = BabbleGuard.babblePrefer(noisyCandidate, cleanCandidate)
        assertEquals("Preference order must be symmetric regardless of argument position", cleanCandidate, preferred2)

        // Comparator contract verification
        assertTrue(
            "babblePrefer.compare(clean, noisy) must be negative",
            BabbleGuard.babblePrefer.compare(cleanCandidate, noisyCandidate) < 0
        )
        assertTrue(
            "babblePrefer.compare(noisy, clean) must be positive",
            BabbleGuard.babblePrefer.compare(noisyCandidate, cleanCandidate) > 0
        )

        // Collection minWith verification
        val candidates = listOf(noisyCandidate, cleanCandidate)
        val best = candidates.minWithOrNull(BabbleGuard.babblePrefer)
        assertEquals("minWithOrNull using babblePrefer must pick clean candidate", cleanCandidate, best)

        // Tie-breaker on equal excess bursts: candidate with shorter duration is preferred
        val shortCandidate = BabbleGuard.SynthesisCandidate(
            pcm = FloatArray(12000),
            burstCount = 1,
            syllableCount = 1,
            frameCount = 5
        )
        val longCandidate = BabbleGuard.SynthesisCandidate(
            pcm = FloatArray(24000),
            burstCount = 1,
            syllableCount = 1,
            frameCount = 8
        )
        val tieWinner = BabbleGuard.babblePrefer(shortCandidate, longCandidate)
        assertEquals("Equal excess burst tie must select shorter duration candidate", shortCandidate, tieWinner)

        // Telemetry logging integration verification
        val initialRetryCount = TtsMetricsLogger.getBabbleRetryCount()
        TtsMetricsLogger.recordBabbleRetry(
            phonemes = "vˈaːŋ1.",
            retryAttempt = 1,
            burstCount = 2,
            syllableCount = 1,
            reason = "excess_bursts"
        )
        assertEquals(initialRetryCount + 1, TtsMetricsLogger.getBabbleRetryCount())
        assertEquals(initialRetryCount + 1, TtsMetricsLogger.currentSession.babbleRetryCount)
    }
}
