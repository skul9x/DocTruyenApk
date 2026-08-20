package com.skul9x.doctruyen.tts.vieneu
 
import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
 
class StageLatencyBreakdownTest {
 
    @Before
    fun setUp() {
        DebugLogger.clear()
    }
 
    @After
    fun tearDown() {
        DebugLogger.clear()
    }
 
    @Test
    fun testInferenceTimingDataStructures() {
        val timing = VieNeuOnnxEngine.InferenceTiming(
            backboneDurationMs = 320L,
            codecDurationMs = 80L
        )
        val dummyPcm = FloatArray(100) { 0.1f }
        val result = VieNeuOnnxEngine.EngineInferenceResult(
            pcm = dummyPcm,
            timing = timing
        )
 
        assertEquals(320L, result.timing.backboneDurationMs)
        assertEquals(80L, result.timing.codecDurationMs)
        assertEquals(400L, result.timing.totalDurationMs)
        assertEquals(100, result.pcm.size)
    }
 
    @Test
    fun testStageLatencyBreakdownInMetricsLogger() {
        TtsMetricsLogger.startSession(storyId = "test_stage_latency", storyTitle = "Truyện Test Stage")
 
        val g2pDuration = 12L
        val backboneDuration = 350L
        val codecDuration = 95L
        val inferDuration = backboneDuration + codecDuration // 445L
        val audioProcessDuration = 8L
        val audioDuration = 2000L
        val pauseDuration = 400L
 
        val chunk = TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 0,
            totalChunks = 1,
            text = "Một buổi sáng mùa thu trong lành.",
            phonemeCount = 18,
            phonemizeDurationMs = g2pDuration,
            inferenceDurationMs = inferDuration,
            audioDurationMs = audioDuration,
            pauseDurationMs = pauseDuration,
            backboneDurationMs = backboneDuration,
            codecDurationMs = codecDuration,
            audioProcessDurationMs = audioProcessDuration
        )
 
        // 1. Verify stage-by-stage non-zero metrics stored in chunk timing
        assertEquals(0, chunk.chunkIndex)
        assertEquals(1, chunk.totalChunks)
        assertEquals(18, chunk.phonemeCount)
        assertEquals(12L, chunk.phonemizeDurationMs)
        assertEquals(350L, chunk.backboneDurationMs)
        assertEquals(95L, chunk.codecDurationMs)
        assertEquals(445L, chunk.inferenceDurationMs)
        assertEquals(8L, chunk.audioProcessDurationMs)
        assertEquals(2000L, chunk.audioDurationMs)
        assertEquals(400L, chunk.pauseDurationMs)
 
        // 2. Verify total inference duration equals backbone + codec
        assertEquals(chunk.inferenceDurationMs, chunk.backboneDurationMs + chunk.codecDurationMs)
 
        // 3. Verify RTF computation
        val expectedRtf = 445f / 2000f
        assertEquals(expectedRtf, chunk.rtf, 0.001f)
 
        // 4. Verify session snapshot includes chunk with complete stage latencies
        val session = TtsMetricsLogger.endSession()
        assertEquals(1, session.chunks.size)
        val recordedChunk = session.chunks[0]
        assertEquals(12L, recordedChunk.phonemizeDurationMs)
        assertEquals(350L, recordedChunk.backboneDurationMs)
        assertEquals(95L, recordedChunk.codecDurationMs)
        assertEquals(8L, recordedChunk.audioProcessDurationMs)
        assertEquals(445L, recordedChunk.inferenceDurationMs)
 
        // 5. Verify log output
        val logs = DebugLogger.getFormattedLogs(LogType.TTS_TIMING)
        assertTrue(logs.contains("[Chunk 1/1]"))
        assertTrue(logs.contains("g2p=12ms"))
        assertTrue(logs.contains("infer=445ms"))
    }
}
