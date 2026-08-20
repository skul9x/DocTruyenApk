package com.skul9x.doctruyen.tts.vieneu

import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiSelfDiagnosticIntegrationTest {

    @Before
    fun setUp() {
        DebugLogger.clear()
    }

    @After
    fun tearDown() {
        DebugLogger.clear()
    }

    @Test
    fun testEndToEndTelemetryLifecycleAndAiDiagnosticReport() {
        val deviceProfile = DeviceProfile(
            model = "Pixel 8 Pro",
            manufacturer = "Google",
            socModel = "Tensor G3",
            cpuCores = 9,
            totalRamMb = 12288L,
            availableRamMb = 6144L,
            engineThreads = 4,
            quantization = "FP32"
        )
        val startMem = MemorySnapshot(
            nativeHeapAllocatedMb = 85f,
            nativeHeapTotalMb = 120f,
            jvmHeapUsedMb = 25f
        )
        val startThermal = ThermalSnapshot(
            batteryTempCelsius = 35.5f,
            thermalThrottlingStatus = "NONE"
        )

        // 1. Initiate session with device profile, memory & thermal snapshots
        TtsMetricsLogger.startSession(
            storyId = "story_e2e_404",
            storyTitle = "Võ Luyện Đỉnh Phong - Chương 1",
            deviceProfile = deviceProfile,
            startMemory = startMem,
            startThermal = startThermal
        )

        // 2. Simulate pre-buffering
        TtsMetricsLogger.recordPreBufferingStart()
        TtsMetricsLogger.recordPreBufferingComplete(bufferedChunks = 2, bufferedDurationMs = 3500L)

        // 3. Synthesize Chunk 0 (Title)
        val chunk0 = TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 0,
            totalChunks = 3,
            text = "Võ Luyện Đỉnh Phong - Chương 1: Bắt đầu tu luyện.",
            phonemeCount = 35,
            phonemizeDurationMs = 12L,
            inferenceDurationMs = 450L,
            audioDurationMs = 2000L,
            pauseDurationMs = 1000L,
            backboneDurationMs = 350L,
            codecDurationMs = 95L,
            audioProcessDurationMs = 5L
        )

        // 4. Synthesize Chunk 1
        val chunk1 = TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 1,
            totalChunks = 3,
            text = "Dương Khai là một đệ tử quét rác tại Lăng Tiêu Các.",
            phonemeCount = 42,
            phonemizeDurationMs = 15L,
            inferenceDurationMs = 520L,
            audioDurationMs = 2200L,
            pauseDurationMs = 400L,
            backboneDurationMs = 410L,
            codecDurationMs = 105L,
            audioProcessDurationMs = 5L
        )

        // 5. Synthesize Chunk 2
        val chunk2 = TtsMetricsLogger.recordChunkSynthesis(
            chunkIndex = 2,
            totalChunks = 3,
            text = "Hắn vô tình nhặt được một cuốn Hắc Thư không chữ bí ẩn.",
            phonemeCount = 45,
            phonemizeDurationMs = 14L,
            inferenceDurationMs = 530L,
            audioDurationMs = 2300L,
            pauseDurationMs = 650L,
            backboneDurationMs = 415L,
            codecDurationMs = 110L,
            audioProcessDurationMs = 5L
        )

        // 6. Playback stream events
        TtsMetricsLogger.recordPlaybackStart(chunkIndex = 0, starvationDelayMs = 0L)
        TtsMetricsLogger.recordPlaybackComplete(chunkIndex = 0, actualPlaybackDurationMs = 2000L)

        TtsMetricsLogger.recordPlaybackStart(chunkIndex = 1, starvationDelayMs = 0L)
        TtsMetricsLogger.recordPlaybackComplete(chunkIndex = 1, actualPlaybackDurationMs = 2200L)

        TtsMetricsLogger.recordPlaybackStart(chunkIndex = 2, starvationDelayMs = 0L)
        TtsMetricsLogger.recordPlaybackComplete(chunkIndex = 2, actualPlaybackDurationMs = 2300L)

        // 7. Complete session with end snapshots
        val endMem = MemorySnapshot(
            nativeHeapAllocatedMb = 92f,
            nativeHeapTotalMb = 125f,
            jvmHeapUsedMb = 28f
        )
        val endThermal = ThermalSnapshot(
            batteryTempCelsius = 36.8f,
            thermalThrottlingStatus = "NONE"
        )

        val sessionResult = TtsMetricsLogger.endSession(
            endMemory = endMem,
            endThermal = endThermal
        )

        // 8. Assert session metrics integrity
        assertEquals("story_e2e_404", sessionResult.storyId)
        assertEquals("Võ Luyện Đỉnh Phong - Chương 1", sessionResult.storyTitle)
        assertEquals(3, sessionResult.chunks.size)
        assertEquals(0, sessionResult.underrunCount)
        assertEquals(6500L, sessionResult.totalAudioDurationMs)
        assertEquals(1500L, sessionResult.totalSynthesisDurationMs)
        assertTrue("Average RTF must be fast (< 0.5x)", sessionResult.averageRtf < 0.5f)

        // 9. Assert stage latency percentages
        val breakdown = TtsMetricsLogger.calculateStagePercentages(sessionResult)
        assertEquals(3, sessionResult.chunks.size)
        assertTrue(breakdown.g2pDurationMs > 0)
        assertTrue(breakdown.backboneDurationMs > 0)
        assertTrue(breakdown.codecDurationMs > 0)
        assertTrue(breakdown.audioProcessDurationMs > 0)
        val sumPercents = breakdown.g2pPercent + breakdown.backbonePercent + breakdown.codecPercent + breakdown.audioProcessPercent
        assertEquals(100.0f, sumPercents, 0.05f)

        // 10. Assert presence and structure of AI Diagnostic Report in DebugLogger
        val formattedLogs = DebugLogger.getFormattedLogs(LogType.TTS_TIMING)
        assertTrue("TTS timing logs must not be empty", formattedLogs.isNotEmpty())
        assertTrue("Diagnostic report banner must be present in logs", formattedLogs.contains("========================== 📊 AI PERFORMANCE DIAGNOSTIC =========================="))

        // Verify the 5 required sections
        // 1. [DEVICE PROFILE]
        assertTrue(formattedLogs.contains("[DEVICE PROFILE]"))
        assertTrue(formattedLogs.contains("Google Pixel 8 Pro | SoC: Tensor G3 (9 Cores)"))
        assertTrue(formattedLogs.contains("Runtime Engine: VieNeu ONNX | Threads: 4 | Quantization: FP32"))

        // 2. [SESSION METRICS]
        assertTrue(formattedLogs.contains("[SESSION METRICS]"))
        assertTrue(formattedLogs.contains("3 chunks | Total Speech Duration: 6.5s"))
        assertTrue(formattedLogs.contains("Total Inference Time: 1.5s"))
        assertTrue(formattedLogs.contains("Buffer Starvations: 0 lần"))
        assertTrue(formattedLogs.contains("Memory Footprint: Native Heap: 85MB ➔ 92MB (Delta: +7MB, No Leak)"))
        assertTrue(formattedLogs.contains("Thermal Profile: 35.5°C ➔ 36.8°C (+1.3°C) | Throttling: NONE"))

        // 3. [LATENCY PROFILING PER STAGE]
        assertTrue(formattedLogs.contains("[LATENCY PROFILING PER STAGE]"))
        assertTrue(formattedLogs.contains("G2P Processing:"))
        assertTrue(formattedLogs.contains("ONNX Backbone:"))
        assertTrue(formattedLogs.contains("ONNX Decoder Codec:"))
        assertTrue(formattedLogs.contains("Audio Enqueue/PCM:"))

        // 4. [ACTIVE AI FLAGS & BOTTLENECK ANALYSIS]
        assertTrue(formattedLogs.contains("[ACTIVE AI FLAGS & BOTTLENECK ANALYSIS]"))
        assertTrue(formattedLogs.contains("Không phát hiện bottleneck hiệu năng. Hệ thống hoạt động tối ưu."))

        // 5. [🎯 AI ACTIONABLE RECOMMENDATIONS]
        assertTrue(formattedLogs.contains("[🎯 AI ACTIONABLE RECOMMENDATIONS]"))
        assertTrue(formattedLogs.contains("Cấu hình hiện tại đạt hiệu năng tốt, không cần thay đổi."))
    }
}
