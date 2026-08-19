package com.skul9x.doctruyen.tts.vieneu

import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Locale

/**
 * Data structure recording detailed timing metrics for an individual TTS chunk.
 */
data class TtsChunkTiming(
    val chunkIndex: Int,
    val totalChunks: Int,
    val textSnippet: String,
    val charCount: Int,
    val phonemeCount: Int,
    val phonemizeDurationMs: Long,
    val inferenceDurationMs: Long,
    val audioDurationMs: Long,
    val rtf: Float,
    val queueDwellTimeMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
    val pauseDurationMs: Long = 0L,
    val starvationDelayMs: Long = 0L
)

/**
 * Aggregated metrics for an entire TTS playback/reading session.
 */
data class TtsSessionMetrics(
    val storyId: String = "",
    val storyTitle: String = "",
    val timeToFirstAudioMs: Long = 0L,
    val averageRtf: Float = 0f,
    val totalAudioDurationMs: Long = 0L,
    val totalSynthesisDurationMs: Long = 0L,
    val underrunCount: Int = 0,
    val chunks: List<TtsChunkTiming> = emptyList()
)

/**
 * Core timing telemetry engine for VieNeu TTS.
 * Thread-safe and non-blocking.
 */
object TtsMetricsLogger {

    private const val TAG = "TtsMetrics"

    @Volatile
    var currentSession: TtsSessionMetrics = TtsSessionMetrics()
        private set

    private var sessionStartTimeMs: Long = 0L
    private var firstAudioOutputTimeMs: Long = 0L
    private val chunkTimings = CopyOnWriteArrayList<TtsChunkTiming>()
    private val chunkQueueTimeMap = ConcurrentHashMap<Int, Long>()
    private var underrunCount: Int = 0

    @Synchronized
    fun startSession(storyId: String = "", storyTitle: String = "") {
        sessionStartTimeMs = System.currentTimeMillis()
        firstAudioOutputTimeMs = 0L
        underrunCount = 0
        chunkTimings.clear()
        chunkQueueTimeMap.clear()
        currentSession = TtsSessionMetrics(
            storyId = storyId,
            storyTitle = storyTitle
        )
        val msg = "⏱️ [TTS Session Start] storyId='$storyId', title='$storyTitle'"
        DebugLogger.log(TAG, msg, LogType.TTS_TIMING)
    }

    fun recordChunkSynthesis(
        chunkIndex: Int,
        totalChunks: Int,
        text: String,
        phonemeCount: Int,
        phonemizeDurationMs: Long,
        inferenceDurationMs: Long,
        audioDurationMs: Long,
        pauseDurationMs: Long = 0L
    ): TtsChunkTiming {
        val snippet = if (text.length > 40) text.take(40) + "..." else text
        val rtf = if (audioDurationMs > 0) inferenceDurationMs.toFloat() / audioDurationMs else 0f
        
        // Mark enqueue time for dwell time calculation
        chunkQueueTimeMap[chunkIndex] = System.currentTimeMillis()

        val timing = TtsChunkTiming(
            chunkIndex = chunkIndex,
            totalChunks = totalChunks,
            textSnippet = snippet,
            charCount = text.length,
            phonemeCount = phonemeCount,
            phonemizeDurationMs = phonemizeDurationMs,
            inferenceDurationMs = inferenceDurationMs,
            audioDurationMs = audioDurationMs,
            rtf = rtf,
            pauseDurationMs = pauseDurationMs
        )
        chunkTimings.add(timing)
        updateSessionSnapshot()

        val logMsg = formatChunkLog(timing)
        DebugLogger.log(TAG, logMsg, LogType.TTS_TIMING)

        return timing
    }

    fun recordPlaybackStart(chunkIndex: Int, starvationDelayMs: Long = 0L) {
        val now = System.currentTimeMillis()
        if (firstAudioOutputTimeMs == 0L) {
            firstAudioOutputTimeMs = now
            val ttft = if (sessionStartTimeMs > 0) now - sessionStartTimeMs else 0L
            updateSessionSnapshot()
            val ttftMsg = "⚡ [TTFT] Time to first audio: ${ttft}ms"
            DebugLogger.log(TAG, ttftMsg, LogType.TTS_TIMING)
        }

        if (starvationDelayMs > 0) {
            recordUnderrun(chunkIndex, starvationDelayMs)
        }

        val queueTime = chunkQueueTimeMap.remove(chunkIndex)
        val dwellTime = if (queueTime != null && queueTime > 0) now - queueTime else 0L

        // Update the timing item in list
        val index = chunkTimings.indexOfFirst { it.chunkIndex == chunkIndex }
        if (index >= 0) {
            val old = chunkTimings[index]
            val updated = old.copy(
                queueDwellTimeMs = dwellTime,
                starvationDelayMs = starvationDelayMs
            )
            chunkTimings[index] = updated
            updateSessionSnapshot()
        }
    }

    fun recordPlaybackComplete(chunkIndex: Int, actualPlaybackDurationMs: Long) {
        val index = chunkTimings.indexOfFirst { it.chunkIndex == chunkIndex }
        if (index >= 0) {
            val old = chunkTimings[index]
            val updated = old.copy(playbackDurationMs = actualPlaybackDurationMs)
            chunkTimings[index] = updated
            updateSessionSnapshot()
        }
    }

    @Synchronized
    fun recordUnderrun(chunkIndex: Int, delayMs: Long) {
        underrunCount++
        updateSessionSnapshot()
        val msg = "⚠️ [Buffer Starvation] Chunk #$chunkIndex delayed by ${delayMs}ms (Underrun count: $underrunCount)"
        DebugLogger.log(TAG, msg, LogType.TTS_TIMING)
    }

    @Synchronized
    fun endSession(): TtsSessionMetrics {
        val session = updateSessionSnapshot()
        val msg = formatSessionSummary(session)
        DebugLogger.log(TAG, msg, LogType.TTS_TIMING)
        return session
    }

    fun formatChunkLog(timing: TtsChunkTiming): String {
        return String.format(
            Locale.US,
            "⚡ [Chunk %d/%d] chars=%d | phonemes=%d | g2p=%dms | infer=%dms | audio=%dms | rtf=%.2fx | pause=%dms | text='%s'",
            timing.chunkIndex + 1,
            timing.totalChunks,
            timing.charCount,
            timing.phonemeCount,
            timing.phonemizeDurationMs,
            timing.inferenceDurationMs,
            timing.audioDurationMs,
            timing.rtf,
            timing.pauseDurationMs,
            timing.textSnippet
        )
    }

    fun formatSessionSummary(session: TtsSessionMetrics): String {
        return String.format(
            Locale.US,
            "📊 [TTS Session Summary]\n" +
            "• Story: %s (%s)\n" +
            "• TTFT (Time to First Audio): %d ms\n" +
            "• Chunks Synthesized: %d\n" +
            "• Avg RTF: %.2fx\n" +
            "• Total Inference Time: %d ms\n" +
            "• Total Audio Duration: %d ms (%.1fs)\n" +
            "• Buffer Underruns: %d",
            session.storyTitle.ifEmpty { "N/A" },
            session.storyId.ifEmpty { "N/A" },
            session.timeToFirstAudioMs,
            session.chunks.size,
            session.averageRtf,
            session.totalSynthesisDurationMs,
            session.totalAudioDurationMs,
            session.totalAudioDurationMs / 1000f,
            session.underrunCount
        )
    }

    private fun updateSessionSnapshot(): TtsSessionMetrics {
        val ttft = if (firstAudioOutputTimeMs > 0 && sessionStartTimeMs > 0) {
            firstAudioOutputTimeMs - sessionStartTimeMs
        } else {
            0L
        }

        val totalAudio = chunkTimings.sumOf { it.audioDurationMs }
        val totalInfer = chunkTimings.sumOf { it.inferenceDurationMs }
        val avgRtf = if (totalAudio > 0) totalInfer.toFloat() / totalAudio else 0f

        val snapshot = currentSession.copy(
            timeToFirstAudioMs = ttft,
            averageRtf = avgRtf,
            totalAudioDurationMs = totalAudio,
            totalSynthesisDurationMs = totalInfer,
            underrunCount = underrunCount,
            chunks = ArrayList(chunkTimings)
        )
        currentSession = snapshot
        return snapshot
    }

    fun getChunkTimings(): List<TtsChunkTiming> = ArrayList(chunkTimings)
}
