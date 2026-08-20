package com.skul9x.doctruyen.tts.vieneu

import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiDiagnosticMetricsTest {

    @Before
    fun setUp() {
        DebugLogger.clear()
    }

    @After
    fun tearDown() {
        DebugLogger.clear()
    }

    @Test
    fun testStagePercentagesCalculation() {
        val session = TtsSessionMetrics(
            chunks = listOf(
                TtsChunkTiming(
                    chunkIndex = 0,
                    totalChunks = 2,
                    textSnippet = "Chunk 1",
                    charCount = 20,
                    phonemeCount = 15,
                    phonemizeDurationMs = 10L,
                    inferenceDurationMs = 490L,
                    audioDurationMs = 2000L,
                    rtf = 0.245f,
                    backboneDurationMs = 390L,
                    codecDurationMs = 100L,
                    audioProcessDurationMs = 0L
                ),
                TtsChunkTiming(
                    chunkIndex = 1,
                    totalChunks = 2,
                    textSnippet = "Chunk 2",
                    charCount = 30,
                    phonemeCount = 25,
                    phonemizeDurationMs = 15L,
                    inferenceDurationMs = 485L,
                    audioDurationMs = 2000L,
                    rtf = 0.2425f,
                    backboneDurationMs = 385L,
                    codecDurationMs = 100L,
                    audioProcessDurationMs = 0L
                )
            )
        )

        val breakdown = TtsMetricsLogger.calculateStagePercentages(session)
        // Total = (10+15) + (390+385) + (100+100) + (0+0) = 25 + 775 + 200 + 0 = 1000ms
        assertEquals(1000L, breakdown.totalMeasuredDurationMs)
        assertEquals(25L, breakdown.g2pDurationMs)
        assertEquals(775L, breakdown.backboneDurationMs)
        assertEquals(200L, breakdown.codecDurationMs)
        assertEquals(0L, breakdown.audioProcessDurationMs)

        assertEquals(2.5f, breakdown.g2pPercent, 0.01f)
        assertEquals(77.5f, breakdown.backbonePercent, 0.01f)
        assertEquals(20.0f, breakdown.codecPercent, 0.01f)
        assertEquals(0.0f, breakdown.audioProcessPercent, 0.01f)

        val totalPercent = breakdown.g2pPercent + breakdown.backbonePercent + breakdown.codecPercent + breakdown.audioProcessPercent
        assertEquals(100.0f, totalPercent, 0.01f)
    }

    @Test
    fun testAllSevenAiDiagnosticFlagRulesAndRecommendations() {
        val startMem = MemorySnapshot(nativeHeapAllocatedMb = 100f)
        val endMem = MemorySnapshot(nativeHeapAllocatedMb = 160f) // Delta = +60MB > 50MB (MEMORY_LEAK_SUSPECTED)

        val startThermal = ThermalSnapshot(batteryTempCelsius = 36.0f, thermalThrottlingStatus = "NONE")
        val endThermal = ThermalSnapshot(batteryTempCelsius = 43.5f, thermalThrottlingStatus = "SEVERE") // THERMAL_THROTTLED

        val longText = "A".repeat(130) // > 120 chars (CHUNK_TOO_LONG)

        val chunk1 = TtsChunkTiming(
            chunkIndex = 0,
            totalChunks = 2,
            textSnippet = "Chunk 1",
            charCount = 30,
            phonemeCount = 20,
            phonemizeDurationMs = 65L, // > 50ms (G2P_OVERHEAD)
            inferenceDurationMs = 2500L,
            audioDurationMs = 2000L, // RTF = 1.25x > 1.0f (CPU_BOUND_RTF_HIGH)
            rtf = 1.25f,
            starvationDelayMs = 250L,
            backboneDurationMs = 2000L,
            codecDurationMs = 500L,
            audioProcessDurationMs = 5L
        )

        val chunk2 = TtsChunkTiming(
            chunkIndex = 1,
            totalChunks = 2,
            textSnippet = longText.take(40),
            charCount = longText.length,
            phonemeCount = 100,
            phonemizeDurationMs = 10L,
            inferenceDurationMs = 3000L,
            audioDurationMs = 2000L,
            rtf = 1.5f,
            starvationDelayMs = 0L,
            backboneDurationMs = 2400L,
            codecDurationMs = 600L,
            audioProcessDurationMs = 5L
        )

        val session = TtsSessionMetrics(
            storyId = "story_test_full",
            storyTitle = "Test Full Flags",
            timeToFirstAudioMs = 4500L, // > 3500ms (SLOW_TTFT)
            averageRtf = 1.375f, // > 1.0f (CPU_BOUND_RTF_HIGH)
            totalAudioDurationMs = 4000L,
            totalSynthesisDurationMs = 5500L,
            underrunCount = 1, // > 0 (BUFFER_UNDERRUN)
            chunks = listOf(chunk1, chunk2),
            deviceProfile = DeviceProfile(
                model = "Pixel 7",
                manufacturer = "Google",
                socModel = "Tensor G2",
                cpuCores = 8,
                totalRamMb = 8192L,
                availableRamMb = 3500L,
                engineThreads = 2,
                quantization = "FP32"
            ),
            startMemory = startMem,
            endMemory = endMem,
            startThermal = startThermal,
            endThermal = endThermal,
            totalChars = 160
        )

        val flags = TtsMetricsLogger.detectAiFlags(session)
        val flagTags = flags.map { it.tag }

        // Verify all 7 diagnostic flag rules are triggered
        assertEquals(7, flags.size)
        assertTrue(flagTags.contains("AI_FLAG: SLOW_TTFT"))
        assertTrue(flagTags.contains("AI_FLAG: BUFFER_UNDERRUN"))
        assertTrue(flagTags.contains("AI_FLAG: CPU_BOUND_RTF_HIGH"))
        assertTrue(flagTags.contains("AI_FLAG: THERMAL_THROTTLED"))
        assertTrue(flagTags.contains("AI_FLAG: MEMORY_LEAK_SUSPECTED"))
        assertTrue(flagTags.contains("AI_FLAG: CHUNK_TOO_LONG"))
        assertTrue(flagTags.contains("AI_FLAG: G2P_OVERHEAD"))

        // Verify recommendations generation
        val recommendations = TtsMetricsLogger.generateRecommendations(flags)
        val recText = recommendations.joinToString("\n")
        assertTrue(recText.contains("[OPTIMIZE_PREWARM]"))
        assertTrue(recText.contains("[OPTIMIZE_SEGMENTATION]"))
        assertTrue(recText.contains("[OPTIMIZE_PREBUFFER]"))
        assertTrue(recText.contains("[OPTIMIZE_THREADS_QUANTIZATION]"))
        assertTrue(recText.contains("[OPTIMIZE_COOLING]"))
        assertTrue(recText.contains("[OPTIMIZE_G2P_CACHE]"))

        // Verify full report format
        val report = TtsMetricsLogger.formatAiDiagnosticReport(session)
        assertTrue(report.contains("========================== 📊 AI PERFORMANCE DIAGNOSTIC =========================="))
        assertTrue(report.contains("[DEVICE PROFILE]"))
        assertTrue(report.contains("Google Pixel 7 | SoC: Tensor G2 (8 Cores)"))
        assertTrue(report.contains("[SESSION METRICS]"))
        assertTrue(report.contains("TTFT (Time to First Audio): 4500 ms"))
        assertTrue(report.contains("Buffer Starvations: 1 lần"))
        assertTrue(report.contains("Potential Leak"))
        assertTrue(report.contains("Thermal Profile: 36.0°C ➔ 43.5°C (+7.5°C) | Throttling: SEVERE"))
        assertTrue(report.contains("[LATENCY PROFILING PER STAGE]"))
        assertTrue(report.contains("[ACTIVE AI FLAGS & BOTTLENECK ANALYSIS]"))
        assertTrue(report.contains("[🎯 AI ACTIONABLE RECOMMENDATIONS]"))
    }

    @Test
    fun testCleanSessionReportsNoFlags() {
        val startMem = MemorySnapshot(nativeHeapAllocatedMb = 100f)
        val endMem = MemorySnapshot(nativeHeapAllocatedMb = 105f) // Delta +5MB (No leak)

        val startThermal = ThermalSnapshot(batteryTempCelsius = 34.0f, thermalThrottlingStatus = "NONE")
        val endThermal = ThermalSnapshot(batteryTempCelsius = 35.0f, thermalThrottlingStatus = "NONE")

        val chunk = TtsChunkTiming(
            chunkIndex = 0,
            totalChunks = 1,
            textSnippet = "Xin chào mọi người",
            charCount = 18,
            phonemeCount = 12,
            phonemizeDurationMs = 15L,
            inferenceDurationMs = 400L,
            audioDurationMs = 1500L,
            rtf = 0.27f,
            backboneDurationMs = 300L,
            codecDurationMs = 100L,
            audioProcessDurationMs = 2L
        )

        val cleanSession = TtsSessionMetrics(
            storyId = "clean_story",
            storyTitle = "Clean Run",
            timeToFirstAudioMs = 1200L,
            averageRtf = 0.27f,
            totalAudioDurationMs = 1500L,
            totalSynthesisDurationMs = 400L,
            underrunCount = 0,
            chunks = listOf(chunk),
            startMemory = startMem,
            endMemory = endMem,
            startThermal = startThermal,
            endThermal = endThermal,
            totalChars = 18
        )

        val flags = TtsMetricsLogger.detectAiFlags(cleanSession)
        assertTrue(flags.isEmpty())

        val recommendations = TtsMetricsLogger.generateRecommendations(flags)
        assertTrue(recommendations.isEmpty())

        val report = TtsMetricsLogger.formatAiDiagnosticReport(cleanSession)
        assertTrue(report.contains("Không phát hiện bottleneck hiệu năng"))
        assertTrue(report.contains("Cấu hình hiện tại đạt hiệu năng tốt"))
    }
}
