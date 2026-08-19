package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Orchestrates text-to-speech synthesis and streaming audio playback.
 * Synthesizes text in sentence chunks in the background while playing previously synthesized
 * chunks through AudioPlayer for low-latency, gapless story narration.
 */
class TTSAudioPipeline(
    private val engine: VieNeuOnnxEngine,
    var voiceName: String = "Trúc Ly",
    private val context: Context? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    var minPreBufferChunks: Int = 1,
    var minPreBufferDurationMs: Long = 1500L
) {

    sealed class PipelineState {
        object Idle : PipelineState()
        object Synthesizing : PipelineState()
        object Playing : PipelineState()
        object Paused : PipelineState()
        data class Error(val message: String) : PipelineState()
    }

    private val audioPlayer = AudioPlayer()
    private val chunkQueue = ConcurrentLinkedQueue<Pair<Int, ShortArray>>()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private var synthesisJob: Job? = null
    private var playbackJob: Job? = null

    private var chunks: List<TextChunk> = emptyList()
    private var stateCallback: ((PipelineState) -> Unit)? = null

    private val audioManager = try {
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (e: Throwable) {
        null
    }
    private var focusRequest: AudioFocusRequestCompat? = null
    private var wasPausedByTransientLoss = false

    @Volatile
    var currentChunkIndex: Int = 0

    @Volatile
    var totalChunks: Int = 0

    val progress: Float
        get() = if (totalChunks > 0) currentChunkIndex.toFloat() / totalChunks else 0f

    @Volatile
    var currentState: PipelineState = PipelineState.Idle
        private set

    private var progressCallback: ((Int) -> Unit)? = null

    private fun setState(state: PipelineState) {
        currentState = state
        try {
            stateCallback?.invoke(state)
        } catch (e: Exception) {
            Log.e(TAG, "Error in stateCallback", e)
        }
    }

    private fun notifyProgress(percent: Int) {
        try {
            progressCallback?.invoke(percent)
        } catch (e: Exception) {
            Log.e(TAG, "Error in progressCallback", e)
        }
    }

    /**
     * Start reading with structured text chunks.
     */
    fun startReadingChunks(
        chunkList: List<TextChunk>,
        onStateChange: ((PipelineState) -> Unit)? = null,
        onProgress: ((Int) -> Unit)? = null
    ) {
        stopInternal(resetIndex = true)
        stateCallback = onStateChange
        progressCallback = onProgress
        chunks = chunkList
        totalChunks = chunks.size
        currentChunkIndex = 0

        if (chunks.isEmpty()) {
            setState(PipelineState.Idle)
            return
        }

        requestAudioFocus()
        startPipeline(startIndex = 0)
    }

    /**
     * Start reading the provided text from the beginning.
     */
    fun startReading(
        text: String,
        pauseConfig: PauseConfig = PauseConfig(),
        onStateChange: ((PipelineState) -> Unit)? = null
    ) {
        startReading(text, onStateChange, null, pauseConfig)
    }

    fun startReading(
        text: String,
        onStateChange: (PipelineState) -> Unit
    ) {
        startReading(text, onStateChange = onStateChange, onProgress = null, pauseConfig = PauseConfig())
    }

    /**
     * Start reading the provided text with both state and progress callbacks.
     */
    fun startReading(
        text: String,
        onStateChange: ((PipelineState) -> Unit)?,
        onProgress: ((Int) -> Unit)?,
        pauseConfig: PauseConfig = PauseConfig()
    ) {
        val chunkList = SmartTextSegmenter.segment(text, pauseConfig = pauseConfig)
        startReadingChunks(chunkList, onStateChange, onProgress)
    }

    /**
     * Speak title, pause 1 second, then speak content.
     */
    fun startReadingWithTitlePause(
        title: String,
        content: String,
        pauseConfig: PauseConfig = PauseConfig(),
        onStateChange: ((PipelineState) -> Unit)? = null
    ) {
        startReadingWithTitlePause(title, content, onStateChange, null, pauseConfig)
    }

    fun startReadingWithTitlePause(
        title: String,
        content: String,
        onStateChange: (PipelineState) -> Unit
    ) {
        startReadingWithTitlePause(title, content, onStateChange = onStateChange, onProgress = null, pauseConfig = PauseConfig())
    }

    /**
     * Speak title, pause 1 second, then speak content with progress callback.
     */
    fun startReadingWithTitlePause(
        title: String,
        content: String,
        onStateChange: ((PipelineState) -> Unit)?,
        onProgress: ((Int) -> Unit)?,
        pauseConfig: PauseConfig = PauseConfig()
    ) {
        val chunkList = SmartTextSegmenter.segmentStory(title, content, pauseConfig = pauseConfig)
        startReadingChunks(chunkList, onStateChange, onProgress)
    }

    /**
     * Pause reading. Preserves current reading progress.
     */
    fun pause() {
        synthesisJob?.cancel()
        playbackJob?.cancel()
        audioPlayer.pause()
        setState(PipelineState.Paused)
    }

    /**
     * Resume reading from current chunk index.
     */
    fun resume() {
        if (currentState is PipelineState.Paused && currentChunkIndex < totalChunks) {
            requestAudioFocus()
            startPipeline(startIndex = currentChunkIndex)
        }
    }

    /**
     * Stop reading and reset position.
     */
    fun stop() {
        stopInternal(resetIndex = true)
        abandonAudioFocus()
        notifyProgress(0)
        setState(PipelineState.Idle)
    }

    /**
     * Seek to a specific chunk index and continue reading if active.
     */
    fun seekToChunk(index: Int) {
        val target = index.coerceIn(0, totalChunks)
        val wasActive = currentState is PipelineState.Playing || currentState is PipelineState.Synthesizing
        stopInternal(resetIndex = false)
        currentChunkIndex = target

        if (wasActive && target < totalChunks) {
            requestAudioFocus()
            startPipeline(startIndex = target)
        } else {
            setState(if (target >= totalChunks) PipelineState.Idle else PipelineState.Paused)
        }
    }

    /**
     * Release all pipeline resources, audio player, and coroutine scopes.
     */
    fun release() {
        stopInternal(resetIndex = true)
        abandonAudioFocus()
        audioPlayer.release()
        scope.cancel()
    }

    private fun stopInternal(resetIndex: Boolean) {
        synthesisJob?.cancel()
        playbackJob?.cancel()
        synthesisJob = null
        playbackJob = null
        chunkQueue.clear()
        audioPlayer.stop()
        if (resetIndex) {
            currentChunkIndex = 0
            totalChunks = 0
            chunks = emptyList()
        }
    }

    private fun startPipeline(startIndex: Int) {
        chunkQueue.clear()
        audioPlayer.init()
        audioPlayer.play()
        setState(PipelineState.Synthesizing)

        synthesisJob = scope.launch {
            try {
                for (i in startIndex until chunks.size) {
                    if (!isActive) break
                    val chunk = chunks[i]

                    // 1. Probe phonemization latency
                    val g2pStart = System.currentTimeMillis()
                    val phonemes = SeaG2P.phonemize(chunk.text)
                    val g2pDuration = System.currentTimeMillis() - g2pStart
                    val phonemeCount = phonemes.count { it == ' ' } + 1
                    if (!isActive) break

                    // 2. Probe ONNX neural inference time
                    val inferStart = System.currentTimeMillis()
                    val pcmFloat = withContext(NonCancellable) {
                        engine.infer(phonemes = phonemes, voiceName = voiceName)
                    }
                    val inferDuration = System.currentTimeMillis() - inferStart
                    if (!isActive) break

                    // Convert to 16-bit PCM and apply micro-fade to eliminate pop/click
                    val rawPcm16 = PcmUtils.floatToShort(pcmFloat)
                    val speechPcm = PcmUtils.applyMicroFade(rawPcm16)
                    val audioDurationMs = (speechPcm.size * 1000L) / VieNeuConfig.SAMPLE_RATE
                    val pauseMs = chunk.pauseDurationMs.toLong()

                    // Record chunk synthesis metrics
                    TtsMetricsLogger.recordChunkSynthesis(
                        chunkIndex = i,
                        totalChunks = totalChunks,
                        text = chunk.text,
                        phonemeCount = phonemeCount,
                        phonemizeDurationMs = g2pDuration,
                        inferenceDurationMs = inferDuration,
                        audioDurationMs = audioDurationMs,
                        pauseDurationMs = pauseMs
                    )

                    // Append silence if pause is specified into one unified audio buffer
                    val combinedPcm = if (pauseMs > 0L) {
                        val silencePcm = PcmUtils.generateSilence(chunk.pauseDurationMs)
                        if (silencePcm.isNotEmpty()) {
                            val combined = ShortArray(speechPcm.size + silencePcm.size)
                            System.arraycopy(speechPcm, 0, combined, 0, speechPcm.size)
                            System.arraycopy(silencePcm, 0, combined, speechPcm.size, silencePcm.size)
                            combined
                        } else {
                            speechPcm
                        }
                    } else {
                        speechPcm
                    }

                    // Enqueue unified chunk buffer
                    chunkQueue.add(Pair(i, combinedPcm))
                }
            } catch (e: CancellationException) {
                // Expected when paused or stopped
            } catch (e: Throwable) {
                Log.e(TAG, "Synthesis failed", e)
                setState(PipelineState.Error(e.message ?: "Synthesis failed"))
            }
        }

        playbackJob = scope.launch {
            try {
                var playIndex = startIndex
                var starvationStartTime = 0L
                var isPreBuffering = true

                while (isActive && (playIndex < totalChunks || !chunkQueue.isEmpty())) {
                    // Anti-starvation pre-buffering check
                    if (isPreBuffering) {
                        val currentQueueSize = chunkQueue.size
                        val totalBufferedSamples = chunkQueue.sumOf { it.second.size.toLong() }
                        val bufferedDurationMs = (totalBufferedSamples * 1000L) / VieNeuConfig.SAMPLE_RATE
                        val isSynthDone = synthesisJob?.isActive != true

                        val hasEnoughChunks = currentQueueSize >= minPreBufferChunks
                        val hasEnoughDuration = bufferedDurationMs >= minPreBufferDurationMs

                        if (isSynthDone || hasEnoughDuration || (hasEnoughChunks && totalChunks <= 2) || (currentQueueSize >= 2) || playIndex > 0) {
                            isPreBuffering = false
                        } else {
                            delay(20)
                            continue
                        }
                    }

                    val nextChunk = chunkQueue.poll()
                    if (nextChunk != null) {
                        val starvationDelay = if (starvationStartTime > 0L) {
                            val elapsed = System.currentTimeMillis() - starvationStartTime
                            if (elapsed > 150L) elapsed else 0L
                        } else {
                            0L
                        }
                        starvationStartTime = 0L

                        currentChunkIndex = nextChunk.first
                        setState(PipelineState.Playing)
                        if (totalChunks > 0) {
                            val pct = ((nextChunk.first.toFloat() / totalChunks) * 100).toInt().coerceIn(0, 100)
                            notifyProgress(pct)
                        }

                        TtsMetricsLogger.recordPlaybackStart(nextChunk.first, starvationDelay)

                        val playStart = System.currentTimeMillis()
                        audioPlayer.write(nextChunk.second)
                        val playDuration = System.currentTimeMillis() - playStart
                        TtsMetricsLogger.recordPlaybackComplete(nextChunk.first, playDuration)

                        currentChunkIndex = nextChunk.first + 1
                        playIndex = currentChunkIndex

                        if (totalChunks > 0) {
                            val pct = ((currentChunkIndex.toFloat() / totalChunks) * 100).toInt().coerceIn(0, 100)
                            notifyProgress(pct)
                        }
                    } else {
                        // Buffer is empty - track starvation if synthesis is still in progress
                        if (starvationStartTime == 0L && playIndex < totalChunks) {
                            starvationStartTime = System.currentTimeMillis()
                        }

                        // Check if synthesis has finished or failed
                        val isSynthDone = synthesisJob?.isActive != true
                        if (isSynthDone && chunkQueue.isEmpty()) {
                            break
                        }
                        delay(20)
                    }
                }

                if (isActive && playIndex >= totalChunks) {
                    audioPlayer.stop()
                    abandonAudioFocus()
                    notifyProgress(100)
                    TtsMetricsLogger.endSession()
                    setState(PipelineState.Idle)
                }
            } catch (e: CancellationException) {
                // Expected on pause or stop
            } catch (e: Throwable) {
                Log.e(TAG, "Playback failed", e)
                setState(PipelineState.Error(e.message ?: "Playback failed"))
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        try {
            val attrBuilder = AudioAttributesCompat.Builder()
            attrBuilder.setUsage(AudioAttributesCompat.USAGE_MEDIA)
            attrBuilder.setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)
            val audioAttributes = attrBuilder.build()

            val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        audioPlayer.setVolume(1.0f)
                        if (currentState is PipelineState.Paused && wasPausedByTransientLoss) {
                            wasPausedByTransientLoss = false
                            resume()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        audioPlayer.setVolume(0.2f)
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        if (currentState is PipelineState.Playing || currentState is PipelineState.Synthesizing) {
                            wasPausedByTransientLoss = true
                            pause()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        wasPausedByTransientLoss = false
                        stop()
                    }
                }
            }

            val reqBuilder = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN)
            if (audioAttributes != null) reqBuilder.setAudioAttributes(audioAttributes)
            reqBuilder.setOnAudioFocusChangeListener(listener)
            val request = reqBuilder.build()
            focusRequest = request

            if (request != null) {
                val result = AudioManagerCompat.requestAudioFocus(am, request)
                return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
            return true
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to request audio focus: ${e.message}")
            return false
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        val req = focusRequest ?: return
        try {
            AudioManagerCompat.abandonAudioFocusRequest(am, req)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to abandon audio focus: ${e.message}")
        } finally {
            focusRequest = null
        }
    }

    companion object {
        private const val TAG = "TTSAudioPipeline"
    }
}
