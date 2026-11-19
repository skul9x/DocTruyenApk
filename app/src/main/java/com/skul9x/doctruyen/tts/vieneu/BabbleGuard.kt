package com.skul9x.doctruyen.tts.vieneu

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * BabbleGuard: Audio energy envelope analyzer and anti-hallucination heuristic engine.
 *
 * Detects and mitigates model hallucinations, trailing babble, or boundary static
 * in ultra-short dialogue utterances (1–2 syllables like "Vâng.", "Hả?", "Ừ.").
 */
object BabbleGuard {

    const val DEFAULT_SAMPLE_RATE: Int = 48000
    const val HOP_MS: Int = 10
    const val BURST_THRESH_DB: Float = -24.0f
    const val _BURST_THRESH_DB: Float = -24.0f
    const val MIN_BURST_DURATION_MS: Int = 60
    const val MIN_INTER_BURST_GAP_MS: Int = 80
    const val MAX_SHORT_UTTERANCE_SYLLABLES: Int = 2
    const val MAX_RETRIES: Int = 2

    private val FRAME_MARKUP_RE = Regex("""<\|emotion_\d+\|>|</?en>|\[[\w\s]+\]""")
    private val PUNCTUATION_RE = Regex("""[.,!?;:…"'"\-–—()\[\]{}]+""")

    /**
     * Compute RMS energy envelope using hopMs intervals (default 10ms at 48kHz).
     */
    fun computeRmsEnvelope(
        pcm: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        hopMs: Int = HOP_MS
    ): FloatArray {
        if (pcm.isEmpty() || sampleRate <= 0 || hopMs <= 0) return FloatArray(0)
        val hopSize = (sampleRate * hopMs) / 1000
        if (hopSize <= 0) return FloatArray(0)

        val numHops = (pcm.size + hopSize - 1) / hopSize
        val envelope = FloatArray(numHops)

        for (h in 0 until numHops) {
            val start = h * hopSize
            val end = min(pcm.size, start + hopSize)
            val count = end - start
            if (count <= 0) continue

            var sumSq = 0.0
            for (i in start until end) {
                val s = pcm[i]
                sumSq += s * s
            }
            envelope[h] = sqrt(sumSq / count).toFloat()
        }

        return envelope
    }

    /**
     * Find active speech bursts and return their hop frame boundaries (startHop, endHop).
     * Bursts shorter than minDurationMs (60ms) are discarded.
     * Silent gaps shorter than minGapMs (80ms) are bridged.
     */
    fun findSpeechBursts(
        pcm: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        threshDb: Float = BURST_THRESH_DB,
        minDurationMs: Int = MIN_BURST_DURATION_MS,
        minGapMs: Int = MIN_INTER_BURST_GAP_MS,
        hopMs: Int = HOP_MS
    ): List<Pair<Int, Int>> {
        if (pcm.isEmpty() || sampleRate <= 0 || hopMs <= 0) return emptyList()
        val envelope = computeRmsEnvelope(pcm, sampleRate, hopMs)
        if (envelope.isEmpty()) return emptyList()

        var peakRms = 0f
        for (v in envelope) {
            if (v > peakRms) peakRms = v
        }
        if (peakRms <= 1e-6f) return emptyList()

        val linearThreshold = peakRms * (10.0.pow(threshDb.toDouble() / 20.0)).toFloat()

        // 1. Identify raw active intervals
        val rawBursts = mutableListOf<Pair<Int, Int>>()
        var inBurst = false
        var burstStart = 0

        for (i in envelope.indices) {
            val isActive = envelope[i] >= linearThreshold
            if (isActive && !inBurst) {
                inBurst = true
                burstStart = i
            } else if (!isActive && inBurst) {
                inBurst = false
                rawBursts.add(Pair(burstStart, i))
            }
        }
        if (inBurst) {
            rawBursts.add(Pair(burstStart, envelope.size))
        }

        if (rawBursts.isEmpty()) return emptyList()

        // 2. Bridge gaps shorter than minGapMs (80ms)
        val minGapFrames = (minGapMs + hopMs - 1) / hopMs
        val merged = mutableListOf<Pair<Int, Int>>()
        var current = rawBursts[0]

        for (idx in 1 until rawBursts.size) {
            val next = rawBursts[idx]
            val gapFrames = next.first - current.second
            if (gapFrames < minGapFrames) {
                current = Pair(current.first, next.second)
            } else {
                merged.add(current)
                current = next
            }
        }
        merged.add(current)

        // 3. Filter bursts by minDurationMs (60ms)
        val minDurationFrames = (minDurationMs + hopMs - 1) / hopMs
        val validBursts = mutableListOf<Pair<Int, Int>>()
        for (b in merged) {
            val durationFrames = b.second - b.first
            if (durationFrames >= minDurationFrames) {
                validBursts.add(b)
            }
        }

        return validBursts
    }

    /**
     * Alias for findSpeechBursts with standard defaults.
     */
    fun findActiveBursts(
        pcm: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        hopMs: Int = HOP_MS
    ): List<Pair<Int, Int>> = findSpeechBursts(
        pcm = pcm,
        sampleRate = sampleRate,
        hopMs = hopMs
    )

    /**
     * Count distinct active speech bursts.
     * Bursts shorter than minDurationMs (60ms) are discarded.
     * Silent gaps shorter than minGapMs (80ms) are bridged.
     */
    fun countSpeechBursts(
        pcm: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        threshDb: Float = BURST_THRESH_DB,
        minDurationMs: Int = MIN_BURST_DURATION_MS,
        minGapMs: Int = MIN_INTER_BURST_GAP_MS
    ): Int {
        return findSpeechBursts(
            pcm = pcm,
            sampleRate = sampleRate,
            threshDb = threshDb,
            minDurationMs = minDurationMs,
            minGapMs = minGapMs,
            hopMs = HOP_MS
        ).size
    }

    /**
     * Alias for countSpeechBursts.
     */
    fun countBursts(
        pcm: FloatArray,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        threshDb: Float = BURST_THRESH_DB,
        minDurationMs: Int = MIN_BURST_DURATION_MS,
        minGapMs: Int = MIN_INTER_BURST_GAP_MS
    ): Int = countSpeechBursts(pcm, sampleRate, threshDb, minDurationMs, minGapMs)

    /**
     * Trims trailing hallucination/babble bursts directly in the PCM acoustic domain.
     *
     * If the detected speech bursts exceed targetSyllables:
     * - Discards bursts after the targetSyllables-th burst.
     * - Preserves a natural decay tail (~80ms).
     * - Applies a 5ms micro-fade down to 0 at the cut point to eliminate clicks/pops.
     *
     * Returns the trimmed FloatArray if excess bursts were successfully trimmed, or null if no trimming needed.
     */
    fun trimBabbleTail(
        pcm: FloatArray,
        targetSyllables: Int,
        sampleRate: Int = DEFAULT_SAMPLE_RATE,
        hopMs: Int = HOP_MS
    ): FloatArray? {
        if (pcm.isEmpty() || targetSyllables <= 0) return null
        val bursts = findActiveBursts(pcm, sampleRate, hopMs)
        if (bursts.size <= targetSyllables) return null // No excess bursts

        val targetBurst = bursts[targetSyllables - 1]
        val nextBurst = bursts[targetSyllables]
        val hopSize = (sampleRate * hopMs) / 1000
        val decaySamples = (sampleRate * 80) / 1000

        val maxCut = nextBurst.first * hopSize
        val targetCut = (targetBurst.second * hopSize) + decaySamples
        val cutSample = min(pcm.size, min(maxCut, targetCut))

        if (cutSample <= 0) return null
        val trimmed = pcm.copyOf(cutSample)

        // Apply 5ms micro-fade down to zero at the cut point
        val fadeSamples = (sampleRate * 5) / 1000
        val fadeStart = max(0, trimmed.size - fadeSamples)
        for (i in fadeStart until trimmed.size) {
            val factor = 1.0f - (i - fadeStart).toFloat() / fadeSamples
            trimmed[i] *= factor
        }
        return trimmed
    }

    /**
     * Count syllables in Vietnamese text or phonemes.
     */
    fun countSyllables(textOrPhonemes: String): Int {
        if (textOrPhonemes.isBlank()) return 0
        val strippedMarkup = FRAME_MARKUP_RE.replace(textOrPhonemes, " ")
        val cleaned = PUNCTUATION_RE.replace(strippedMarkup, " ").trim()
        if (cleaned.isBlank()) return 0
        val tokens = cleaned.split(Regex("""\s+""")).filter { it.isNotBlank() }
        return tokens.size
    }

    /**
     * Checks if utterance syllable count qualifies as short utterance (<= 2 syllables).
     */
    fun isShortUtterance(syllableCount: Int): Boolean {
        return syllableCount in 1..MAX_SHORT_UTTERANCE_SYLLABLES
    }

    /**
     * Heuristic to determine if an utterance is suspect of hallucinating babble.
     */
    fun isBabbleSuspect(
        burstCount: Int,
        syllableCount: Int,
        generatedFrames: Int = 0,
        capFrames: Int = Int.MAX_VALUE
    ): Boolean {
        // Triggered only on short utterances (syllableCount <= 2)
        if (syllableCount <= 0 || syllableCount > MAX_SHORT_UTTERANCE_SYLLABLES) {
            return false
        }
        // Suspect if frames reached the frame ceiling (frames >= capFrames - 1)
        if (capFrames < Int.MAX_VALUE && generatedFrames >= capFrames - 1) {
            return true
        }
        // Suspect if burstCount > syllableCount (model hallucinated extra syllables/trailing noises)
        return burstCount > syllableCount
    }

    fun isBabbleSuspect(
        pcm: FloatArray,
        syllableCount: Int,
        generatedFrames: Int = 0,
        capFrames: Int = Int.MAX_VALUE,
        sampleRate: Int = DEFAULT_SAMPLE_RATE
    ): Boolean {
        if (syllableCount <= 0 || syllableCount > MAX_SHORT_UTTERANCE_SYLLABLES) {
            return false
        }
        if (capFrames < Int.MAX_VALUE && generatedFrames >= capFrames - 1) {
            return true
        }
        val bursts = countSpeechBursts(pcm, sampleRate)
        return bursts > syllableCount
    }

    fun isBabbleSuspect(candidate: SynthesisCandidate, capFrames: Int = Int.MAX_VALUE): Boolean {
        return isBabbleSuspect(
            burstCount = candidate.burstCount,
            syllableCount = candidate.syllableCount,
            generatedFrames = candidate.frameCount,
            capFrames = capFrames
        )
    }

    /**
     * Candidate synthesis result for evaluation and selection.
     */
    data class SynthesisCandidate(
        val pcm: FloatArray = FloatArray(0),
        val burstCount: Int = 0,
        val syllableCount: Int = 1,
        val frameCount: Int = 0,
        val timing: VieNeuOnnxEngine.InferenceTiming? = null,
        val durationSamples: Int = pcm.size,
        val durationMs: Long = if (pcm.isNotEmpty()) (pcm.size * 1000L) / DEFAULT_SAMPLE_RATE else 0L
    ) {
        val bursts: Int get() = burstCount
        val syllables: Int get() = syllableCount
        val frames: Int get() = frameCount
        val duration: Long get() = durationMs
        val excessBursts: Int get() = max(0, burstCount - syllableCount)

        constructor(
            pcm: FloatArray,
            syllableCount: Int = 1,
            frameCount: Int = 0,
            timing: VieNeuOnnxEngine.InferenceTiming? = null
        ) : this(
            pcm = pcm,
            burstCount = countSpeechBursts(pcm),
            syllableCount = syllableCount,
            frameCount = frameCount,
            timing = timing,
            durationSamples = pcm.size,
            durationMs = (pcm.size * 1000L) / DEFAULT_SAMPLE_RATE
        )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SynthesisCandidate) return false
            return burstCount == other.burstCount &&
                    syllableCount == other.syllableCount &&
                    frameCount == other.frameCount &&
                    durationSamples == other.durationSamples &&
                    pcm.contentEquals(other.pcm)
        }

        override fun hashCode(): Int {
            var result = pcm.contentHashCode()
            result = 31 * result + burstCount
            result = 31 * result + syllableCount
            result = 31 * result + frameCount
            result = 31 * result + durationSamples
            return result
        }
    }

    fun createCandidate(
        pcm: FloatArray,
        frameCount: Int = 0,
        syllableCount: Int = 1,
        timing: VieNeuOnnxEngine.InferenceTiming? = null
    ): SynthesisCandidate {
        val bursts = countSpeechBursts(pcm)
        return SynthesisCandidate(
            pcm = pcm,
            burstCount = bursts,
            syllableCount = syllableCount,
            frameCount = frameCount,
            timing = timing
        )
    }

    /**
     * Candidate comparator and selector:
     * Prefers candidate with fewer excess bursts, breaking ties with shorter duration.
     */
    object BabblePreferComparator : Comparator<SynthesisCandidate> {
        override fun compare(a: SynthesisCandidate, b: SynthesisCandidate): Int {
            val excessDiff = a.excessBursts.compareTo(b.excessBursts)
            if (excessDiff != 0) return excessDiff
            return a.durationSamples.compareTo(b.durationSamples)
        }

        operator fun invoke(a: SynthesisCandidate, b: SynthesisCandidate): SynthesisCandidate {
            return if (compare(a, b) <= 0) a else b
        }
    }

    val babblePrefer: BabblePreferComparator = BabblePreferComparator
}

typealias BabbleCandidate = BabbleGuard.SynthesisCandidate
