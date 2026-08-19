package com.skul9x.doctruyen.tts.vieneu

/**
 * Utility functions for PCM audio data conversion and text processing.
 */
object PcmUtils {

    /**
     * Convert float32 [-1.0, 1.0] PCM samples to int16 [-32768, 32767] PCM samples.
     */
    fun floatToShort(floatPcm: FloatArray): ShortArray {
        return ShortArray(floatPcm.size) { i ->
            (floatPcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    /**
     * Convert int16 [-32768, 32767] PCM samples to float32 [-1.0, 1.0] PCM samples.
     */
    fun shortToFloat(shortPcm: ShortArray): FloatArray {
        return FloatArray(shortPcm.size) { i ->
            shortPcm[i].toFloat() / 32767f
        }
    }

    /**
     * Split text into sentence chunks using [SmartTextSegmenter].
     * Preserves backward compatibility while leveraging linguistic and abbreviation rules.
     */
    fun splitIntoSentences(text: String, maxCharsPerChunk: Int = SmartTextSegmenter.DEFAULT_MAX_CHARS): List<String> {
        return SmartTextSegmenter.segment(text, maxCharsPerChunk).map { it.text }
    }

    /**
     * Split text into structured [TextChunk] list with pause metadata.
     */
    fun splitIntoChunks(text: String, maxCharsPerChunk: Int = SmartTextSegmenter.DEFAULT_MAX_CHARS): List<TextChunk> {
        return SmartTextSegmenter.segment(text, maxCharsPerChunk)
    }

    /**
     * Generate acoustic silence buffer (16-bit PCM zeroes) for a given pause duration in milliseconds.
     * N = sampleRate * (pauseMs / 1000.0)
     */
    fun generateSilence(pauseMs: Int, sampleRate: Int = VieNeuConfig.SAMPLE_RATE): ShortArray {
        if (pauseMs <= 0) return ShortArray(0)
        val sampleCount = ((sampleRate.toLong() * pauseMs) / 1000L).toInt()
        return ShortArray(sampleCount)
    }

    /**
     * Apply micro-fading (fade-in at start, fade-out at end) to eliminate acoustic pop/click discontinuities.
     * Uses linear or half-cosine ramp over fadeDurationMs (default 5ms).
     *
     * @param pcm16 Audio samples array
     * @param fadeDurationMs Duration of fade in milliseconds (default 5ms)
     * @param sampleRate Audio sample rate (default 48000 Hz)
     * @return New ShortArray with smooth micro-fades applied
     */
    fun applyMicroFade(
        pcm16: ShortArray,
        fadeDurationMs: Float = 5.0f,
        sampleRate: Int = VieNeuConfig.SAMPLE_RATE
    ): ShortArray {
        if (pcm16.isEmpty()) return ShortArray(0)
        val result = pcm16.copyOf()
        val fadeSamples = ((sampleRate * fadeDurationMs) / 1000f).toInt().coerceAtMost(pcm16.size / 2)
        if (fadeSamples <= 0) return result

        // Fade in: [0..fadeSamples-1] ramp 0.0 -> 1.0
        for (i in 0 until fadeSamples) {
            val factor = i.toFloat() / fadeSamples.toFloat()
            result[i] = (result[i] * factor).toInt().toShort()
        }

        // Fade out: [size - fadeSamples..size - 1] ramp 1.0 -> 0.0
        val n = result.size
        for (i in 0 until fadeSamples) {
            val factor = (fadeSamples - 1 - i).toFloat() / fadeSamples.toFloat()
            result[n - fadeSamples + i] = (result[n - fadeSamples + i] * factor).toInt().toShort()
        }

        return result
    }
}

