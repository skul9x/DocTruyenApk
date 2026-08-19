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
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
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

    private var sentences: List<String> = emptyList()
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

    private var isTitlePauseMode = false
    private var titleSentencesCount = 0
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
     * Start reading the provided text from the beginning.
     */
    fun startReading(
        text: String,
        onStateChange: ((PipelineState) -> Unit)? = null
    ) {
        startReading(text, onStateChange, null)
    }

    /**
     * Start reading the provided text with both state and progress callbacks.
     */
    fun startReading(
        text: String,
        onStateChange: ((PipelineState) -> Unit)?,
        onProgress: ((Int) -> Unit)?
    ) {
        stopInternal(resetIndex = true)
        stateCallback = onStateChange
        progressCallback = onProgress
        isTitlePauseMode = false
        titleSentencesCount = 0
        sentences = PcmUtils.splitIntoSentences(text)
        totalChunks = sentences.size
        currentChunkIndex = 0

        if (sentences.isEmpty()) {
            setState(PipelineState.Idle)
            return
        }

        requestAudioFocus()
        startPipeline(startIndex = 0)
    }

    /**
     * Speak title, pause 1 second, then speak content.
     */
    fun startReadingWithTitlePause(
        title: String,
        content: String,
        onStateChange: ((PipelineState) -> Unit)? = null
    ) {
        startReadingWithTitlePause(title, content, onStateChange, null)
    }

    /**
     * Speak title, pause 1 second, then speak content with progress callback.
     */
    fun startReadingWithTitlePause(
        title: String,
        content: String,
        onStateChange: ((PipelineState) -> Unit)?,
        onProgress: ((Int) -> Unit)?
    ) {
        stopInternal(resetIndex = true)
        stateCallback = onStateChange
        progressCallback = onProgress

        val titleList = PcmUtils.splitIntoSentences(title)
        val contentList = PcmUtils.splitIntoSentences(content)

        isTitlePauseMode = titleList.isNotEmpty()
        titleSentencesCount = titleList.size
        sentences = titleList + contentList
        totalChunks = sentences.size
        currentChunkIndex = 0

        if (sentences.isEmpty()) {
            setState(PipelineState.Idle)
            return
        }

        requestAudioFocus()
        startPipeline(startIndex = 0)
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
            sentences = emptyList()
            isTitlePauseMode = false
            titleSentencesCount = 0
        }
    }

    private fun startPipeline(startIndex: Int) {
        chunkQueue.clear()
        audioPlayer.init()
        audioPlayer.play()
        setState(PipelineState.Synthesizing)

        synthesisJob = scope.launch {
            try {
                for (i in startIndex until sentences.size) {
                    if (!isActive) break
                    val sentence = sentences[i]
                    val phonemes = SeaG2P.phonemize(sentence)
                    if (!isActive) break

                    val pcmFloat = withContext(NonCancellable) {
                        engine.infer(phonemes = phonemes, voiceName = voiceName)
                    }
                    if (!isActive) break

                    val pcm16 = PcmUtils.floatToShort(pcmFloat)
                    chunkQueue.add(Pair(i, pcm16))

                    // If in title pause mode and just finished the title part, queue 1 second silence
                    if (isTitlePauseMode && i == titleSentencesCount - 1 && i < sentences.size - 1) {
                        val silencePcm = ShortArray(VieNeuConfig.SAMPLE_RATE)
                        chunkQueue.add(Pair(i, silencePcm))
                    }
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
                while (isActive && (playIndex < totalChunks || !chunkQueue.isEmpty())) {
                    val nextChunk = chunkQueue.poll()
                    if (nextChunk != null) {
                        currentChunkIndex = nextChunk.first
                        setState(PipelineState.Playing)
                        if (totalChunks > 0) {
                            val pct = ((nextChunk.first.toFloat() / totalChunks) * 100).toInt().coerceIn(0, 100)
                            notifyProgress(pct)
                        }

                        audioPlayer.write(nextChunk.second)
                        currentChunkIndex = nextChunk.first + 1
                        playIndex = currentChunkIndex

                        if (totalChunks > 0) {
                            val pct = ((currentChunkIndex.toFloat() / totalChunks) * 100).toInt().coerceIn(0, 100)
                            notifyProgress(pct)
                        }
                    } else {
                        // Check if synthesis has finished or failed
                        val isSynthDone = synthesisJob?.isActive != true
                        if (isSynthDone && chunkQueue.isEmpty()) {
                            break
                        }
                        delay(30)
                    }
                }

                if (isActive && playIndex >= totalChunks) {
                    audioPlayer.stop()
                    abandonAudioFocus()
                    notifyProgress(100)
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
