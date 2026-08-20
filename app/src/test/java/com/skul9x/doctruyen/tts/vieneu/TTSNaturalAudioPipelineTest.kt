package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class TTSNaturalAudioPipelineTest {

    @Test
    fun testSilenceGenerationAccuracy() {
        val sampleRate = 48000

        // Test 180ms (CLAUSE_BREAK)
        val silence180 = PcmUtils.generateSilence(180, sampleRate)
        val expected180Samples = (48000L * 180 / 1000L).toInt() // 8640
        assertEquals(expected180Samples, silence180.size)
        assertTrue(silence180.all { it == 0.toShort() })

        // Test 400ms (SENTENCE_BREAK)
        val silence400 = PcmUtils.generateSilence(400, sampleRate)
        val expected400Samples = (48000L * 400 / 1000L).toInt() // 19200
        assertEquals(expected400Samples, silence400.size)
        assertTrue(silence400.all { it == 0.toShort() })

        // Test 650ms (PARAGRAPH_BREAK)
        val silence650 = PcmUtils.generateSilence(650, sampleRate)
        val expected650Samples = (48000L * 650 / 1000L).toInt() // 31200
        assertEquals(expected650Samples, silence650.size)
        assertTrue(silence650.all { it == 0.toShort() })

        // Test 1000ms (TITLE_BREAK)
        val silence1000 = PcmUtils.generateSilence(1000, sampleRate)
        val expected1000Samples = 48000
        assertEquals(expected1000Samples, silence1000.size)
        assertTrue(silence1000.all { it == 0.toShort() })

        // Test Edge Cases: 0ms and negative
        val silence0 = PcmUtils.generateSilence(0, sampleRate)
        assertEquals(0, silence0.size)

        val silenceNeg = PcmUtils.generateSilence(-100, sampleRate)
        assertEquals(0, silenceNeg.size)
    }

    @Test
    fun testMicroFadeSmoothTransitions() {
        val sampleRate = 48000
        val fadeMs = 5.0f
        val fadeSamples = ((sampleRate * fadeMs) / 1000f).toInt() // 240 samples

        // Create a constant high-amplitude test PCM signal
        val testPcm = ShortArray(4800) { 20000.toShort() }

        val fadedPcm = PcmUtils.applyMicroFade(testPcm, fadeDurationMs = fadeMs, sampleRate = sampleRate)

        assertEquals(testPcm.size, fadedPcm.size)

        // 1. Check start boundary: first sample must be 0 (or close to 0)
        assertEquals(0.toShort(), fadedPcm[0])

        // 2. Check fade-in ramp: monotonically increasing magnitude
        for (i in 1 until fadeSamples) {
            assertTrue("Fade-in sample $i should be >= previous sample", fadedPcm[i] >= fadedPcm[i - 1])
            assertTrue("Fade-in sample $i should be <= original amplitude", fadedPcm[i] <= 20000)
        }

        // 3. Check middle section unaffected
        val midSample = testPcm.size / 2
        assertEquals(20000.toShort(), fadedPcm[midSample])

        // 4. Check end boundary: last sample must be faded near 0
        val n = fadedPcm.size
        assertEquals(0.toShort(), fadedPcm[n - 1])

        // 5. Check fade-out ramp: monotonically decreasing magnitude
        for (i in (n - fadeSamples) until (n - 1)) {
            assertTrue("Fade-out sample $i should be >= next sample", fadedPcm[i] >= fadedPcm[i + 1])
        }
    }

    @Test
    fun testMicroFadeShortBufferSafety() {
        // Buffer smaller than 2 * fade duration
        val shortPcm = ShortArray(10) { 10000.toShort() }
        val faded = PcmUtils.applyMicroFade(shortPcm, fadeDurationMs = 5.0f, sampleRate = 48000)

        assertEquals(shortPcm.size, faded.size)
        // With size 10, fadeSamples is coerced to size/2 = 5
        assertEquals(0.toShort(), faded[0])
        assertEquals(0.toShort(), faded[faded.size - 1])
    }

    @Test
    fun testTextChunkToPauseSampleMapping() {
        val title = "Chuyện Con Mèo Dạy Hải Âu Bay"
        val content = "Một ngày nọ, Zorba nhìn thấy đàn hải âu bay lượn trên bầu trời cảng Hamburg.\n\nNó tự nhủ rằng một ngày nào đó chú mèo cũng có thể bay."

        val chunks = SmartTextSegmenter.segmentStory(title, content)
        assertTrue(chunks.size >= 3)

        // Title chunk should map to TITLE_BREAK (1000ms -> 48000 samples)
        val titleChunk = chunks[0]
        assertEquals(PauseType.TITLE_BREAK, titleChunk.pauseType)
        val titleSilence = PcmUtils.generateSilence(titleChunk.pauseDurationMs)
        assertEquals(48000, titleSilence.size)

        // Content paragraph 1 chunk should end with PARAGRAPH_BREAK (650ms -> 31200 samples)
        val para1Chunk = chunks.first { it.isParagraphEnd && it != titleChunk }
        assertEquals(PauseType.PARAGRAPH_BREAK, para1Chunk.pauseType)
        val para1Silence = PcmUtils.generateSilence(para1Chunk.pauseDurationMs)
        assertEquals(31200, para1Silence.size)
    }

    @Test
    fun testTelemetryLoggingSessionIntegration() {
        TtsMetricsLogger.startSession("story_test_01", "Kiểm tra Pipeline")

        val chunkTiming1 = TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 0,
            totalChunks = 2,
            text = "Tiêu đề câu chuyện thử nghiệm.",
            phonemeCount = 25,
            phonemizeDurationMs = 5,
            inferenceDurationMs = 120,
            audioDurationMs = 1500,
            pauseDurationMs = 1000
        )

        assertEquals(0, chunkTiming1.chunkIndex)
        assertEquals(1000L, chunkTiming1.pauseDurationMs)
        assertTrue(chunkTiming1.rtf > 0.05f)

        // Simulate playback start & finish
        TtsMetricsLogger.recordPlaybackStart(0, starvationDelayMs = 0L)
        TtsMetricsLogger.recordPlaybackComplete(0, 1500L)

        // Second chunk with buffer starvation event
        val chunkTiming2 = TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 1,
            totalChunks = 2,
            text = "Đoạn văn tiếp theo trong câu chuyện.",
            phonemeCount = 30,
            phonemizeDurationMs = 6,
            inferenceDurationMs = 140,
            audioDurationMs = 1800,
            pauseDurationMs = 650
        )
        assertEquals(1, chunkTiming2.chunkIndex)

        // Simulate starvation delay of 45ms
        TtsMetricsLogger.recordPlaybackStart(1, starvationDelayMs = 45L)
        TtsMetricsLogger.recordPlaybackComplete(1, 1800L)

        val sessionSummary = TtsMetricsLogger.endSession()
        assertEquals(2, sessionSummary.chunks.size)
        assertEquals(1, sessionSummary.underrunCount)
        assertEquals(3300L, sessionSummary.totalAudioDurationMs)
        assertEquals(260L, sessionSummary.totalSynthesisDurationMs)
    }
}
