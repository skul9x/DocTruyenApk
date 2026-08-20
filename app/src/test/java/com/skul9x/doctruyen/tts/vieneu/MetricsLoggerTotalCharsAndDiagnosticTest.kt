package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MetricsLoggerTotalCharsAndDiagnosticTest {

    @Before
    fun setUp() {
        // Reset session state
        TtsMetricsLogger.startSession(storyId = "test_story_01", storyTitle = "Tấm Cám")
    }

    @Test
    fun testDynamicTotalCharsAccumulationAndAiDiagnosticThroughput() {
        // Initially 0 chars
        assertEquals(0, TtsMetricsLogger.currentSession.totalChars)

        // Chunk 1: 19 chars
        val chunk1Text = "Ngày xửa ngày xưa.." // length = 19
        assertEquals(19, chunk1Text.length)
        TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 0,
            totalChunks = 3,
            text = chunk1Text,
            phonemeCount = 15,
            phonemizeDurationMs = 10,
            inferenceDurationMs = 200,
            audioDurationMs = 1000
        )
        assertEquals(19, TtsMetricsLogger.currentSession.totalChars)

        // Chunk 2: 67 chars
        val chunk2Text = "Có hai chị em cùng cha khác mẹ, chị tên là Tấm và em tên là Cám...." // 67 chars
        assertEquals(67, chunk2Text.length)
        TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 1,
            totalChunks = 3,
            text = chunk2Text,
            phonemeCount = 50,
            phonemizeDurationMs = 25,
            inferenceDurationMs = 800,
            audioDurationMs = 3000
        )
        assertEquals(86, TtsMetricsLogger.currentSession.totalChars)

        // Chunk 3: 67 chars
        val chunk3Text = "Tấm thì chăm chỉ hiền lành, còn Cám thì lười biếng được nuông chiều" // 67 chars
        assertEquals(67, chunk3Text.length)
        TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 2,
            totalChunks = 3,
            text = chunk3Text,
            phonemeCount = 52,
            phonemizeDurationMs = 20,
            inferenceDurationMs = 1000,
            audioDurationMs = 3500
        )
        assertEquals(153, TtsMetricsLogger.currentSession.totalChars)

        // Verify session end and AI Diagnostic report throughput calculation
        val session = TtsMetricsLogger.endSession()
        assertEquals(153, session.totalChars)
        assertEquals(2000L, session.totalSynthesisDurationMs) // 200 + 800 + 1000 = 2000ms = 2.0s
        assertEquals(7500L, session.totalAudioDurationMs) // 1000 + 3000 + 3500 = 7500ms

        val diagnosticReport = TtsMetricsLogger.formatAiDiagnosticReport(session)
        assertTrue(diagnosticReport.contains("Story: 153 chars | 3 chunks"))
        // Throughput should be 153 chars / 2.0s = 76.5 chars/s
        assertTrue(diagnosticReport.contains("Throughput: 76.5 chars/s") || diagnosticReport.contains("76.5"))
    }

    @Test
    fun testExplicitInitialStoryCharsPreserved() {
        // When initialStoryChars is explicitly provided at session start
        TtsMetricsLogger.startSession(
            storyId = "test_story_02",
            storyTitle = "Thạch Sanh",
            initialStoryChars = 500
        )

        assertEquals(500, TtsMetricsLogger.currentSession.totalChars)

        // Adding chunk should keep totalChars = 500
        TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 0,
            totalChunks = 1,
            text = "Thạch Sanh dũng cảm chém Chằn Tinh.",
            phonemeCount = 30,
            phonemizeDurationMs = 15,
            inferenceDurationMs = 500,
            audioDurationMs = 2000
        )

        val session = TtsMetricsLogger.endSession()
        assertEquals(500, session.totalChars)
        val report = TtsMetricsLogger.formatAiDiagnosticReport(session)
        assertTrue(report.contains("Story: 500 chars"))
    }
}
