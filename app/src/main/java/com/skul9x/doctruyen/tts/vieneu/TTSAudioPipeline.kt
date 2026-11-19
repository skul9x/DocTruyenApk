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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Orchestrates text-to-speech synthesis and streaming audio playback.
 * Synthesizes text in sentence chunks in the background while playing previously synthesized
 * chunks through AudioPlayer for low-latency, gapless story narration.
 */
class TTSAudioPipeline(
    private val engine: VieNeuOnnxEngine? = null,
    var voiceName: String = "Hải Đăng",
    private val context: Context? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    var minPreBufferChunks: Int = 1,
    var minPreBufferDurationMs: Long = 7500L,
    var quantizationType: OnnxSessionManager.QuantizationType = engine?.quantizationType ?: OnnxSessionManager.QuantizationType.INT8,
    var preBufferChunkCount: Int = 2,
    var temperature: Float = 0.45f,
    internal var audioPlayer: AudioPlayer = AudioPlayer()
) {
    var targetPreBufferChunks: Int
        get() = preBufferChunkCount
        set(value) {
            preBufferChunkCount = value
        }

    sealed class PipelineState {
        object Idle : PipelineState()
        data class Buffering(val bufferedChunks: Int, val targetChunks: Int) : PipelineState()
        object Synthesizing : PipelineState()
        object Playing : PipelineState()
        object Paused : PipelineState()
        data class Error(val message: String) : PipelineState()
    }

    private val audioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            try {
                // Boost Linux thread priority to urgent audio (-19) to prevent CPU starvation and glitches
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: Throwable) {
                // Ignore in JVM unit tests or environments where android.os.Process is a stub
            }
            runnable.run()
        }, "AudioTrackWorkerThread")
    }
    internal val audioDispatcher = audioExecutor.asCoroutineDispatcher()

    internal var chunkChannel: Channel<Pair<Int, ShortArray>> = Channel(capacity = 4)
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private var synthesisJob: Job? = null
    private var playbackJob: Job? = null

    private var chunks: List<TextChunk> = emptyList()
    private var currentStoryId: Int = 0
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
        if (currentState == state) return
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
        storyId: Int = 0,
        onStateChange: ((PipelineState) -> Unit)? = null,
        onProgress: ((Int) -> Unit)? = null
    ) {
        stopInternal(resetIndex = true)
        stateCallback = onStateChange
        progressCallback = onProgress
        chunks = chunkList
        totalChunks = chunks.size
        currentChunkIndex = 0
        currentStoryId = storyId

        if (chunks.isEmpty()) {
            setState(PipelineState.Idle)
            return
        }

        requestAudioFocus()
        val engineThreads = DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4)
        val ep = engine?.executionProvider ?: "CPU"
        val quant = when (quantizationType) {
            OnnxSessionManager.QuantizationType.INT8 -> "INT8"
            OnnxSessionManager.QuantizationType.FP16 -> if (ep.contains("NNAPI")) "FP16+NNAPI" else "FP16"
            OnnxSessionManager.QuantizationType.FP32 -> "FP32"
        }
        Log.i(TAG, "Starting TTS session: storyId='$storyId', quantization='$quant', EP='$ep'")
        TtsMetricsLogger.startSession(
            storyId = if (storyId != 0) storyId.toString() else "",
            storyTitle = chunks.firstOrNull()?.text?.take(30) ?: "",
            context = context,
            engineThreads = engineThreads,
            quantization = quant
        )
        startPipeline(startIndex = 0, storyId = storyId)
    }

    /**
     * Start reading the provided text from the beginning.
     */
    fun startReading(
        text: String,
        storyId: Int = 0,
        pauseConfig: PauseConfig = PauseConfig(),
        onStateChange: ((PipelineState) -> Unit)? = null
    ) {
        startReading(text, storyId = storyId, onStateChange = onStateChange, onProgress = null, pauseConfig = pauseConfig)
    }

    fun startReading(
        text: String,
        onStateChange: (PipelineState) -> Unit
    ) {
        startReading(text, storyId = 0, onStateChange = onStateChange, onProgress = null, pauseConfig = PauseConfig())
    }

    /**
     * Start reading the provided text with both state and progress callbacks.
     */
    fun startReading(
        text: String,
        storyId: Int = 0,
        onStateChange: ((PipelineState) -> Unit)?,
        onProgress: ((Int) -> Unit)?,
        pauseConfig: PauseConfig = PauseConfig()
    ) {
        val chunkList = SmartTextSegmenter.segment(text, pauseConfig = pauseConfig)
        startReadingChunks(chunkList, storyId = storyId, onStateChange = onStateChange, onProgress = onProgress)
    }

    /**
     * Speak title, pause 1 second, then speak content.
     */
    fun startReadingWithTitlePause(
        title: String,
        content: String,
        storyId: Int = 0,
        pauseConfig: PauseConfig = PauseConfig(),
        onStateChange: ((PipelineState) -> Unit)? = null
    ) {
        startReadingWithTitlePause(title, content, storyId = storyId, onStateChange = onStateChange, onProgress = null, pauseConfig = pauseConfig)
    }

    fun startReadingWithTitlePause(
        title: String,
        content: String,
        onStateChange: (PipelineState) -> Unit
    ) {
        startReadingWithTitlePause(title, content, storyId = 0, onStateChange = onStateChange, onProgress = null, pauseConfig = PauseConfig())
    }

    /**
     * Speak title, pause 1 second, then speak content with progress callback.
     */
    fun startReadingWithTitlePause(
        title: String,
        content: String,
        storyId: Int = 0,
        onStateChange: ((PipelineState) -> Unit)?,
        onProgress: ((Int) -> Unit)?,
        pauseConfig: PauseConfig = PauseConfig()
    ) {
        val chunkList = SmartTextSegmenter.segmentStory(title, content, pauseConfig = pauseConfig)
        startReadingChunks(chunkList, storyId = storyId, onStateChange = onStateChange, onProgress = onProgress)
    }

    /**
     * Pause reading. Preserves current reading progress.
     */
    fun pause() {
        synthesisJob?.cancel()
        playbackJob?.cancel()
        try {
            chunkChannel.cancel()
        } catch (e: Throwable) {}
        chunkChannel = Channel(capacity = 4)
        audioPlayer.pause()
        setState(PipelineState.Paused)
    }

    /**
     * Resume reading from current chunk index.
     */
    fun resume() {
        if (currentState is PipelineState.Paused && currentChunkIndex < totalChunks) {
            requestAudioFocus()
            startPipeline(startIndex = currentChunkIndex, storyId = currentStoryId)
        }
    }

    /**
     * Stop reading and reset position.
     */
    fun stop() {
        stopInternal(resetIndex = true)
        abandonAudioFocus()
        notifyProgress(0)
        TtsMetricsLogger.endSession(context)
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
            startPipeline(startIndex = target, storyId = currentStoryId)
        } else {
            setState(if (target >= totalChunks) PipelineState.Idle else PipelineState.Paused)
        }
    }

    /**
     * Compute adaptive target duration in milliseconds for the current pipeline configuration.
     */
    fun computeAdaptiveTargetDurationMs(
        quantizationType: OnnxSessionManager.QuantizationType = this.quantizationType,
        cpuCores: Int = DevicePerformanceHelper.getDeviceProfile(context).cpuCores,
        remainingChunks: Int = 2,
        startIndex: Int = 0,
        targetPreBufferChunks: Int = this.preBufferChunkCount
    ): Long {
        return Companion.computeAdaptiveTargetDurationMs(
            quantizationType = quantizationType,
            cpuCores = cpuCores,
            remainingChunks = remainingChunks,
            startIndex = startIndex,
            safeFallbackDurationMs = minPreBufferDurationMs,
            targetPreBufferChunks = targetPreBufferChunks
        )
    }

    /**
     * Release all pipeline resources, audio player, and coroutine scopes.
     */
    fun release() {
        stopInternal(resetIndex = true)
        abandonAudioFocus()
        audioPlayer.release()
        scope.cancel()
        try {
            chunkChannel.cancel()
        } catch (e: Throwable) {}
        audioDispatcher.close()
        audioExecutor.shutdown()
    }

    private fun stopInternal(resetIndex: Boolean) {
        synthesisJob?.cancel()
        playbackJob?.cancel()
        synthesisJob = null
        playbackJob = null
        try {
            chunkChannel.cancel()
        } catch (e: Throwable) {}
        chunkChannel = Channel(capacity = 4)
        audioPlayer.flush()
        audioPlayer.stop()
        if (resetIndex) {
            currentChunkIndex = 0
            totalChunks = 0
            chunks = emptyList()
        }
    }

    private fun startPipeline(startIndex: Int, storyId: Int = 0) {
        try {
            chunkChannel.cancel()
        } catch (e: Throwable) {}
        chunkChannel = Channel(capacity = 4)
        audioPlayer.init()
        audioPlayer.play()
        setState(PipelineState.Synthesizing)
        TtsMetricsLogger.recordPreBufferingStart()

        val cachedIndices = mutableSetOf<Int>()

        synthesisJob = scope.launch(dispatcher) {
            try {
                // Ingest any pre-warmed chunks into the channel
                if (startIndex == 0 && storyId != 0) {
                    val prewarmed = PrewarmedChunkCache.getChunksForStory(storyId, voiceName)
                    // Sort by chunk index to enqueue in proper playback order
                    prewarmed.toSortedMap().forEach { (chunkIdx, pcm) ->
                        if (chunkIdx in 0 until chunks.size) {
                            chunkChannel.send(Pair(chunkIdx, pcm))
                            cachedIndices.add(chunkIdx)
                            Log.d(TAG, "⚡ Ingested pre-warmed chunk $chunkIdx into chunkChannel from cache")
                        }
                    }
                }

                for (i in startIndex until chunks.size) {
                    if (!isActive) break

                    // Skip synthesis if chunk was already loaded from pre-warmed cache
                    if (cachedIndices.contains(i)) {
                        Log.d(TAG, "⚡ Skipping synthesis for pre-warmed chunk $i")
                        continue
                    }

                    val chunk = chunks[i]

                    // 1. Probe phonemization latency
                    val g2pStart = System.currentTimeMillis()
                    val phonemes = VieNeuTTSManager.phonemizeWithEmotionBypass(chunk.text)
                    val g2pDuration = System.currentTimeMillis() - g2pStart
                    val phonemeCount = phonemes.count { it == ' ' } + 1
                    if (!isActive) break

                    // 2. Probe ONNX neural inference time (backbone + codec)
                    val inferStart = System.currentTimeMillis()
                    val pcmFloat: FloatArray
                    val backboneDuration: Long
                    val codecDuration: Long

                    if (engine != null) {
                        val inferResult = withContext(NonCancellable) {
                            val effectiveTemp = if (voiceName == "Thiền Tâm Đức") 0.25f else temperature
                            engine.inferWithTiming(phonemes = phonemes, voiceName = voiceName, temperature = effectiveTemp)
                        }
                        pcmFloat = inferResult.pcm
                        backboneDuration = inferResult.timing.backboneDurationMs
                        codecDuration = inferResult.timing.codecDurationMs
                    } else {
                        pcmFloat = FloatArray(VieNeuConfig.SAMPLE_RATE)
                        backboneDuration = 0L
                        codecDuration = 0L
                    }
                    val inferDuration = System.currentTimeMillis() - inferStart
                    if (!isActive) break

                    // 3. Audio/PCM post-processing (floatToShort conversion, applyMicroFade, and generateSilence concatenation)
                    val audioProcessStart = System.currentTimeMillis()
                    val rawPcm16 = PcmUtils.floatToShort(pcmFloat)
                    val speechPcm = PcmUtils.applyMicroFade(rawPcm16)
                    val audioDurationMs = (speechPcm.size * 1000L) / VieNeuConfig.SAMPLE_RATE
                    val pauseMs = chunk.pauseDurationMs.toLong()

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
                    val audioProcessDuration = System.currentTimeMillis() - audioProcessStart

                    // Record chunk synthesis metrics with complete stage breakdown
                    TtsMetricsLogger.recordChunkSynthesis(
                        chunkIndex = i,
                        totalChunks = totalChunks,
                        text = chunk.text,
                        phonemeCount = phonemeCount,
                        phonemizeDurationMs = g2pDuration,
                        inferenceDurationMs = inferDuration,
                        audioDurationMs = audioDurationMs,
                        pauseDurationMs = pauseMs,
                        backboneDurationMs = backboneDuration,
                        codecDurationMs = codecDuration,
                        audioProcessDurationMs = audioProcessDuration
                    )

                    // Enqueue chunk buffer - suspends naturally if channel (capacity 4) is full (backpressure)
                    chunkChannel.send(Pair(i, combinedPcm))
                }

                // Close channel to signal all chunks have been synthesized
                chunkChannel.close()
            } catch (e: CancellationException) {
                // Expected when paused or stopped
            } catch (e: Throwable) {
                Log.e(TAG, "Synthesis failed", e)
                chunkChannel.close(e)
                setState(PipelineState.Error(e.message ?: "Synthesis failed"))
            }
        }

        playbackJob = scope.launch(audioDispatcher) {
            try {
                var playIndex = startIndex
                var starvationStartTime = 0L

                // Adaptive duration-based pre-buffering thresholds
                val remainingChunks = totalChunks - startIndex
                val cpuCores = DevicePerformanceHelper.getDeviceProfile(context).cpuCores
                val targetDurationMs = computeAdaptiveTargetDurationMs(
                    quantizationType = quantizationType,
                    cpuCores = cpuCores,
                    remainingChunks = remainingChunks,
                    startIndex = startIndex,
                    targetPreBufferChunks = preBufferChunkCount
                )

                val targetChunks = minOf(preBufferChunkCount, remainingChunks).coerceAtLeast(1)
                val prebufferList = mutableListOf<Pair<Int, ShortArray>>()
                var prebufferDurationMs = 0L

                // Pre-buffering phase: Suspend until target duration or chunk count is reached without delay() loops
                setState(PipelineState.Buffering(bufferedChunks = 0, targetChunks = targetChunks))

                while (isActive && prebufferList.size < targetChunks && prebufferDurationMs < targetDurationMs) {
                    val chunk = try {
                        chunkChannel.receive()
                    } catch (e: ClosedReceiveChannelException) {
                        break
                    }
                    prebufferList.add(chunk)
                    val chunkDuration = (chunk.second.size * 1000L) / VieNeuConfig.SAMPLE_RATE
                    prebufferDurationMs += chunkDuration
                    setState(PipelineState.Buffering(bufferedChunks = prebufferList.size, targetChunks = targetChunks))
                    if (prebufferList.size >= remainingChunks) {
                        break
                    }
                }

                if (prebufferList.isNotEmpty()) {
                    TtsMetricsLogger.recordPreBufferingComplete(prebufferList.size, prebufferDurationMs)
                }

                fun playChunk(nextChunk: Pair<Int, ShortArray>) {
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
                }

                // 1. Play all chunks that were collected during prebuffering
                for (chunk in prebufferList) {
                    if (!isActive) break
                    playChunk(chunk)
                }

                // 2. Stream and play remaining chunks directly from chunkChannel
                while (isActive) {
                    val nextChunk = try {
                        val tryResult = chunkChannel.tryReceive()
                        if (tryResult.isSuccess) {
                            tryResult.getOrThrow()
                        } else if (tryResult.isClosed) {
                            break
                        } else {
                            starvationStartTime = System.currentTimeMillis()
                            chunkChannel.receive()
                        }
                    } catch (e: ClosedReceiveChannelException) {
                        break
                    }

                    if (!isActive) break
                    playChunk(nextChunk)
                }

                if (isActive && playIndex >= totalChunks) {
                    audioPlayer.stop()
                    abandonAudioFocus()
                    notifyProgress(100)
                    TtsMetricsLogger.endSession(context)
                    setState(PipelineState.Idle)
                }
            } catch (e: CancellationException) {
                // Expected on pause or stop
            } catch (e: Throwable) {
                Log.e(TAG, "Playback failed", e)
                setState(PipelineState.Error(e.message ?: "Playback failed"))
            } finally {
                if (currentState !is PipelineState.Paused) {
                    audioPlayer.stop()
                }
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

        /**
         * Computes initial target pre-buffering duration in milliseconds adaptively based on
         * model quantization, available CPU cores, playback starting position, and user configured prebuffer chunk count.
         */
        fun computeAdaptiveTargetDurationMs(
            quantizationType: OnnxSessionManager.QuantizationType,
            cpuCores: Int = DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4),
            remainingChunks: Int = 2,
            startIndex: Int = 0,
            safeFallbackDurationMs: Long = 7500L,
            targetPreBufferChunks: Int = 2
        ): Long {
            if (startIndex > 0 || remainingChunks <= 1) {
                return 1500L
            }
            return when (targetPreBufferChunks) {
                1 -> {
                    if (cpuCores < 4 || quantizationType == OnnxSessionManager.QuantizationType.FP32) {
                        safeFallbackDurationMs
                    } else {
                        2000L
                    }
                }
                3 -> {
                    if (cpuCores < 4 || quantizationType == OnnxSessionManager.QuantizationType.FP32) {
                        maxOf(8000L, safeFallbackDurationMs)
                    } else {
                        8000L
                    }
                }
                else -> { // 2 Chunks (Default)
                    when {
                        cpuCores < 4 || quantizationType == OnnxSessionManager.QuantizationType.FP32 -> safeFallbackDurationMs
                        quantizationType == OnnxSessionManager.QuantizationType.INT8 && cpuCores >= 6 -> 2000L
                        quantizationType == OnnxSessionManager.QuantizationType.INT8 -> 2500L
                        quantizationType == OnnxSessionManager.QuantizationType.FP16 && cpuCores >= 6 -> 2500L
                        else -> 4500L
                    }
                }
            }
        }
    }
}
