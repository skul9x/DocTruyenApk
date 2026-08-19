package com.skul9x.doctruyen.tts.vieneu

import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TtsMetricsLoggerTest {

    @Before
    fun setUp() {
        DebugLogger.clear()
    }

    @After
    fun tearDown() {
        DebugLogger.clear()
    }

    @Test
    fun testRtfCalculation() {
        TtsMetricsLogger.startSession(storyId = "101", storyTitle = "Truyện Kiều")

        val chunk = TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 0,
            totalChunks = 2,
            text = "Trăm năm trong cõi người ta",
            phonemeCount = 15,
            phonemizeDurationMs = 5L,
            inferenceDurationMs = 400L,
            audioDurationMs = 2000L,
            pauseDurationMs = 300L
        )

        // Expected RTF = 400 / 2000 = 0.20
        assertEquals(0.20f, chunk.rtf, 0.001f)
        assertEquals(0, chunk.chunkIndex)
        assertEquals(2, chunk.totalChunks)
        assertEquals(15, chunk.phonemeCount)
        assertEquals(5L, chunk.phonemizeDurationMs)
        assertEquals(400L, chunk.inferenceDurationMs)
        assertEquals(2000L, chunk.audioDurationMs)
        assertEquals(300L, chunk.pauseDurationMs)
    }

    @Test
    fun testTtftAndSessionAggregation() {
        TtsMetricsLogger.startSession(storyId = "102", storyTitle = "Lục Vân Tiên")

        TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 0,
            totalChunks = 2,
            text = "Vân Tiên ghé lại bên đàng",
            phonemeCount = 12,
            phonemizeDurationMs = 4L,
            inferenceDurationMs = 300L,
            audioDurationMs = 1500L
        )

        TtsMetricsLogger.recordPlaybackStart(chunkIndex = 0, starvationDelayMs = 0L)
        TtsMetricsLogger.recordPlaybackComplete(chunkIndex = 0, actualPlaybackDurationMs = 1500L)

        TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 1,
            totalChunks = 2,
            text = "Bẻ cây làm gậy tìm đàng chạy vô",
            phonemeCount = 14,
            phonemizeDurationMs = 6L,
            inferenceDurationMs = 500L,
            audioDurationMs = 2500L
        )

        TtsMetricsLogger.recordPlaybackStart(chunkIndex = 1, starvationDelayMs = 50L)
        TtsMetricsLogger.recordPlaybackComplete(chunkIndex = 1, actualPlaybackDurationMs = 2500L)

        val session = TtsMetricsLogger.endSession()

        assertEquals("102", session.storyId)
        assertEquals("Lục Vân Tiên", session.storyTitle)
        assertEquals(2, session.chunks.size)
        assertEquals(4000L, session.totalAudioDurationMs) // 1500 + 2500
        assertEquals(800L, session.totalSynthesisDurationMs) // 300 + 500
        // Expected Avg RTF = 800 / 4000 = 0.20
        assertEquals(0.20f, session.averageRtf, 0.001f)
        assertEquals(1, session.underrunCount)
        assertTrue(session.timeToFirstAudioMs >= 0L)
    }

    @Test
    fun testBufferUnderrunDetection() {
        TtsMetricsLogger.startSession(storyId = "103", storyTitle = "Test Starvation")

        TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 0,
            totalChunks = 1,
            text = "Một đoạn văn ngắn kiểm tra.",
            phonemeCount = 10,
            phonemizeDurationMs = 2L,
            inferenceDurationMs = 150L,
            audioDurationMs = 1000L
        )

        TtsMetricsLogger.recordUnderrun(chunkIndex = 0, delayMs = 120L)

        val session = TtsMetricsLogger.currentSession
        assertEquals(1, session.underrunCount)

        val logs = DebugLogger.getFormattedLogs(LogType.TTS_TIMING)
        assertTrue(logs.contains("[Buffer Starvation] Chunk #0 delayed by 120ms"))
    }

    @Test
    fun testFormattedLogStringOutput() {
        TtsMetricsLogger.startSession(storyId = "104", storyTitle = "Test Log Format")

        val chunk = TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 0,
            totalChunks = 5,
            text = "Kiểm tra định dạng log chuỗi chi tiết.",
            phonemeCount = 16,
            phonemizeDurationMs = 8L,
            inferenceDurationMs = 250L,
            audioDurationMs = 1200L,
            pauseDurationMs = 200L
        )

        val chunkLog = TtsMetricsLogger.formatChunkLog(chunk)
        assertTrue(chunkLog.contains("[Chunk 1/5]"))
        assertTrue(chunkLog.contains("chars="))
        assertTrue(chunkLog.contains("phonemes=16"))
        assertTrue(chunkLog.contains("g2p=8ms"))
        assertTrue(chunkLog.contains("infer=250ms"))
        assertTrue(chunkLog.contains("audio=1200ms"))
        assertTrue(chunkLog.contains("rtf=0.21x"))
        assertTrue(chunkLog.contains("pause=200ms"))

        val session = TtsMetricsLogger.endSession()
        val sessionSummary = TtsMetricsLogger.formatSessionSummary(session)
        assertTrue(sessionSummary.contains("[TTS Session Summary]"))
        assertTrue(sessionSummary.contains("• Story: Test Log Format (104)"))
        assertTrue(sessionSummary.contains("• TTFT (Time to First Audio):"))
        assertTrue(sessionSummary.contains("• Chunks Synthesized: 1"))
        assertTrue(sessionSummary.contains("• Avg RTF:"))
        assertTrue(sessionSummary.contains("• Total Inference Time: 250 ms"))
        assertTrue(sessionSummary.contains("• Total Audio Duration: 1200 ms"))
    }
}
