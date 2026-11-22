package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Orchestrates text-to-speech synthesis and streaming audio playback.
 * Synthesizes text in sentence chunks with bounded backpressure via Coroutine Channel,
 * preventing memory ballooning and isolating blocking AudioTrack operations onto dedicated audio dispatchers.
 */
class TTSAudioPipeline(
    private val engine: VieNeuOnnxEngine,
    var voiceName: String = "Hải Đăng",
    private val context: Context? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    var minPreBufferChunks: Int = 1,
    var minPreBufferDurationMs: Long = 7500L,
    var quantizationType: OnnxSessionManager.QuantizationType = engine.quantizationType,
    var preBufferChunkCount: Int = 2,
    var temperature: Float = 0.45f,
    val maxQueueCapacity: Int = DEFAULT_MAX_QUEUE_CAPACITY,
    val audioDispatcher: CoroutineDispatcher = AudioDispatchers.AudioTrackDispatcher,
    customAudioPlayer: AudioPlayer? = null,
    private val customSynthesizer: (suspend (chunk: TextChunk, voiceName: String) -> ShortArray)? = null
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

    val audioPlayer: AudioPlayer = customAudioPlayer ?: AudioPlayer(audioDispatcher = audioDispatcher)

    private val bufferedChunks = AtomicInteger(0)
    private val bufferedSamples = AtomicLong(0L)

    @Volatile
    private var audioChannel: Channel<Pair<Int, ShortArray>>? = null

    @Volatile
    private var prebufferSignal: Channel<Unit>? = null

    val queuedChunkCount: Int
        get() = bufferedChunks.get()

    val queuedMemoryBytes: Long
        get() = bufferedSamples.get() * 2L

    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private class PauseGate {
        private val lock = Any()
        private var gateJob: CompletableJob = Job().apply { complete() }

        @Volatile
        var isPaused: Boolean = false
            private set

        fun pause() {
            synchronized(lock) {
                if (!isPaused) {
                    isPaused = true
                    gateJob = Job()
                }
            }
        }

        fun resume() {
            synchronized(lock) {
                if (isPaused) {
                    isPaused = false
                    gateJob.complete()
                }
            }
        }

        suspend fun awaitActive() {
            val jobToJoin = synchronized(lock) {
                if (isPaused) gateJob else null
            }
            jobToJoin?.join()
        }
    }

    private val pauseGate = PauseGate()

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

        if (storyId != 0 && PrewarmedChunkCache.activeStoryId != null && PrewarmedChunkCache.activeStoryId != storyId) {
            PrewarmedChunkCache.clearPreviousStory(storyId)
        }

        if (chunks.isEmpty()) {
            setState(PipelineState.Idle)
            return
        }

        requestAudioFocus()
        val engineThreads = DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4)
        val quant = when (quantizationType) {
            OnnxSessionManager.QuantizationType.INT8 -> "INT8"
            OnnxSessionManager.QuantizationType.FP16 -> if (engine.executionProvider.contains("NNAPI")) "FP16+NNAPI" else "FP16"
            OnnxSessionManager.QuantizationType.FP32 -> "FP32"
        }
        Log.i(TAG, "Starting TTS session: storyId='$storyId', quantization='$quant', EP='${engine.executionProvider}'")
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
     * Pause reading cooperatively.
     * Preserves current reading progress, coroutine jobs, and queued chunks in audioChannel.
     */
    fun pause() {
        if (currentState is PipelineState.Paused || currentState is PipelineState.Idle) {
            return
        }
        pauseGate.pause()
        audioPlayer.pause()
        setState(PipelineState.Paused)
    }

    /**
     * Resume reading from current chunk index cooperatively.
     */
    fun resume() {
        if (currentState is PipelineState.Paused && currentChunkIndex < totalChunks) {
            requestAudioFocus()
            if (synthesisJob?.isActive == true || playbackJob?.isActive == true) {
                audioPlayer.play()
                pauseGate.resume()
                setState(PipelineState.Playing)
            } else {
                startPipeline(startIndex = currentChunkIndex, storyId = currentStoryId)
            }
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
        val wasActive = currentState is PipelineState.Playing ||
                currentState is PipelineState.Synthesizing ||
                currentState is PipelineState.Buffering
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
    }

    private fun drainChannel() {
        audioChannel?.let { ch ->
            while (ch.tryReceive().isSuccess) {
                // Drain residual elements
            }
        }
        bufferedChunks.set(0)
        bufferedSamples.set(0L)
    }

    private fun stopInternal(resetIndex: Boolean) {
        pauseGate.resume()
        synthesisJob?.cancel()
        playbackJob?.cancel()
        synthesisJob = null
        playbackJob = null
        audioChannel?.cancel()
        drainChannel()
        audioChannel = null
        prebufferSignal?.cancel()
        prebufferSignal = null
        audioPlayer.flush()
        audioPlayer.stop()
        if (resetIndex) {
            currentChunkIndex = 0
            totalChunks = 0
            chunks = emptyList()
        }
    }

    private fun startPipeline(startIndex: Int, storyId: Int = 0) {
        audioChannel?.cancel()
        drainChannel()
        val channel = Channel<Pair<Int, ShortArray>>(capacity = maxQueueCapacity)
        audioChannel = channel
        prebufferSignal = Channel(Channel.CONFLATED)
        bufferedChunks.set(0)
        bufferedSamples.set(0L)

        audioPlayer.init()
        audioPlayer.play()
        setState(PipelineState.Synthesizing)
        TtsMetricsLogger.recordPreBufferingStart()

        // Ingest any pre-warmed chunks into the channel
        val cachedIndices = mutableSetOf<Int>()
        if (startIndex == 0 && storyId != 0) {
            val prewarmed = PrewarmedChunkCache.getChunksForStory(storyId, voiceName)
            prewarmed.toSortedMap().forEach { (chunkIdx, pcm) ->
                if (chunkIdx in 0 until chunks.size) {
                    val sent = channel.trySend(Pair(chunkIdx, pcm))
                    if (sent.isSuccess) {
                        bufferedChunks.incrementAndGet()
                        bufferedSamples.addAndGet(pcm.size.toLong())
                        cachedIndices.add(chunkIdx)
                        Log.d(TAG, "⚡ Ingested pre-warmed chunk $chunkIdx into channel from cache")
                    }
                }
            }
            if (cachedIndices.isNotEmpty()) {
                prebufferSignal?.trySend(Unit)
            }
        }

        synthesisJob = scope.launch(dispatcher) {
            try {
                for (i in startIndex until chunks.size) {
                    if (!isActive) break

                    pauseGate.awaitActive()
                    if (!isActive) break

                    // Skip synthesis if chunk was already loaded from pre-warmed cache
                    if (cachedIndices.contains(i)) {
                        Log.d(TAG, "⚡ Skipping synthesis for pre-warmed chunk $i")
                        continue
                    }

                    // Check if chunk is available in PrewarmedChunkCache or is actively in-flight from prewarm
                    if (storyId != 0) {
                        var prewarmedPcm = PrewarmedChunkCache.get(storyId, voiceName, i)
                        if (prewarmedPcm == null && PrewarmedChunkCache.isInFlight(storyId, voiceName, i)) {
                            Log.d(TAG, "⏳ Awaiting in-flight prewarm for chunk $i (story $storyId)...")
                            prewarmedPcm = PrewarmedChunkCache.awaitInFlightChunk(storyId, voiceName, i, timeoutMs = 8000L)
                        }

                        if (prewarmedPcm != null) {
                            Log.d(TAG, "⚡ Retrieved pre-warmed chunk $i from cache/in-flight without re-synthesizing")
                            cachedIndices.add(i)
                            channel.send(Pair(i, prewarmedPcm))
                            bufferedChunks.incrementAndGet()
                            bufferedSamples.addAndGet(prewarmedPcm.size.toLong())
                            prebufferSignal?.trySend(Unit)
                            continue
                        }
                    }

                    val chunk = chunks[i]

                    val combinedPcm = if (customSynthesizer != null) {
                        val pcm = customSynthesizer.invoke(chunk, voiceName)
                        val audioDurationMs = (pcm.size * 1000L) / VieNeuConfig.SAMPLE_RATE
                        TtsMetricsLogger.recordChunkSynthesis(
                            chunkIndex = i,
                            totalChunks = totalChunks,
                            text = chunk.text,
                            phonemeCount = chunk.text.split(" ").size,
                            phonemizeDurationMs = 1L,
                            inferenceDurationMs = 1L,
                            audioDurationMs = audioDurationMs,
                            pauseDurationMs = chunk.pauseDurationMs.toLong(),
                            backboneDurationMs = 1L,
                            codecDurationMs = 1L,
                            audioProcessDurationMs = 1L
                        )
                        pcm
                    } else {
                        // 1. Probe phonemization latency
                        val g2pStart = System.currentTimeMillis()
                        val phonemes = VieNeuTTSManager.phonemizeWithEmotionBypass(chunk.text)
                        val g2pDuration = System.currentTimeMillis() - g2pStart
                        val phonemeCount = phonemes.count { it == ' ' } + 1
                        if (!isActive) break

                        // 2. Probe ONNX neural inference time (backbone + codec)
                        val inferStart = System.currentTimeMillis()
                        val inferResult = withContext(NonCancellable) {
                            val effectiveTemp = if (voiceName == "Thiền Tâm Đức") 0.25f else temperature
                            engine.inferWithTiming(phonemes = phonemes, voiceName = voiceName, temperature = effectiveTemp)
                        }
                        val inferDuration = System.currentTimeMillis() - inferStart
                        val pcmFloat = inferResult.pcm
                        val backboneDuration = inferResult.timing.backboneDurationMs
                        val codecDuration = inferResult.timing.codecDurationMs
                        if (!isActive) break

                        // 3. Audio/PCM post-processing (floatToShort conversion, applyMicroFade, and generateSilence concatenation)
                        val audioProcessStart = System.currentTimeMillis()
                        val rawPcm16 = PcmUtils.floatToShort(pcmFloat)
                        val speechPcm = PcmUtils.applyMicroFade(rawPcm16)
                        val audioDurationMs = (speechPcm.size * 1000L) / VieNeuConfig.SAMPLE_RATE
                        val pauseMs = chunk.pauseDurationMs.toLong()

                        // Append silence if pause is specified into one unified audio buffer
                        val combined = if (pauseMs > 0L) {
                            val silencePcm = PcmUtils.generateSilence(chunk.pauseDurationMs)
                            if (silencePcm.isNotEmpty()) {
                                val arr = ShortArray(speechPcm.size + silencePcm.size)
                                System.arraycopy(speechPcm, 0, arr, 0, speechPcm.size)
                                System.arraycopy(silencePcm, 0, arr, speechPcm.size, silencePcm.size)
                                arr
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
                        combined
                    }

                    if (!isActive) break

                    // Enqueue unified chunk buffer into bounded channel.
                    // Suspends asynchronously when buffer capacity is reached, establishing natural backpressure.
                    channel.send(Pair(i, combinedPcm))
                    bufferedChunks.incrementAndGet()
                    bufferedSamples.addAndGet(combinedPcm.size.toLong())
                    prebufferSignal?.trySend(Unit)
                }

                channel.close()
                prebufferSignal?.trySend(Unit)
            } catch (e: CancellationException) {
                // Expected when paused or stopped
            } catch (e: InterruptedException) {
                // Expected when engine or pipeline is cancelled/stopped
                Log.d(TAG, "Synthesis interrupted: ${e.message}")
            } catch (e: Throwable) {
                Log.e(TAG, "Synthesis failed", e)
                setState(PipelineState.Error(e.message ?: "Synthesis failed"))
            }
        }

        playbackJob = scope.launch(dispatcher) {
            try {
                var playIndex = startIndex
                var starvationStartTime = 0L
                var isPreBuffering = true

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

                // 1. Reactive Pre-buffering phase (zero busy-wait polling)
                while (isActive && isPreBuffering) {
                    pauseGate.awaitActive()
                    if (!isActive) break

                    val currentQueueSize = bufferedChunks.get()
                    val totalBufferedSamples = bufferedSamples.get()
                    val bufferedDurationMs = (totalBufferedSamples * 1000L) / VieNeuConfig.SAMPLE_RATE
                    val isSynthDone = synthesisJob?.isActive != true
                    val allChunksBuffered = currentQueueSize >= remainingChunks

                    val minDurationMs = when (preBufferChunkCount) {
                        1 -> 1500L
                        3 -> 7000L
                        else -> if (quantizationType == OnnxSessionManager.QuantizationType.INT8) 2000L else 3500L // 2 chunks
                    }

                    val isFastMode = (quantizationType == OnnxSessionManager.QuantizationType.INT8 && cpuCores >= 4) ||
                            (cpuCores >= 6 && quantizationType != OnnxSessionManager.QuantizationType.FP32)
                    val hasQualifiedChunks = currentQueueSize >= preBufferChunkCount &&
                            (bufferedDurationMs >= minDurationMs || (preBufferChunkCount == 1 && isFastMode && bufferedDurationMs >= 1500L))
                    val hasEnoughDuration = bufferedDurationMs >= targetDurationMs || hasQualifiedChunks
                    val isQueueFull = currentQueueSize >= maxQueueCapacity
                    val hasPrewarmedPlayback = cachedIndices.contains(startIndex) && currentQueueSize > 0
                    val canStartPlayback = isSynthDone || allChunksBuffered || hasEnoughDuration || isQueueFull || hasPrewarmedPlayback ||
                            (customSynthesizer != null && currentQueueSize >= minOf(preBufferChunkCount, remainingChunks))

                    if (canStartPlayback && currentQueueSize > 0) {
                        isPreBuffering = false
                        TtsMetricsLogger.recordPreBufferingComplete(currentQueueSize, bufferedDurationMs)
                        break
                    } else if (isSynthDone && currentQueueSize == 0) {
                        break
                    } else {
                        setState(PipelineState.Buffering(bufferedChunks = currentQueueSize, targetChunks = minOf(preBufferChunkCount, remainingChunks)))
                        try {
                            withTimeoutOrNull(200L) {
                                prebufferSignal?.receive()
                            }
                        } catch (e: Throwable) {
                            // Ignored: timeout or cancellation
                        }
                    }
                }

                // 2. Continuous playback loop via suspending channel reception (0ms jitter)
                while (isActive && playIndex < totalChunks) {
                    pauseGate.awaitActive()
                    if (!isActive) break

                    if (bufferedChunks.get() == 0 && starvationStartTime == 0L && synthesisJob?.isActive == true) {
                        starvationStartTime = System.currentTimeMillis()
                    }

                    val nextChunk = try {
                        channel.receiveCatching().getOrNull()
                    } catch (e: Throwable) {
                        null
                    }

                    if (nextChunk == null) {
                        // Channel closed and empty or cancelled
                        break
                    }

                    bufferedChunks.decrementAndGet()
                    bufferedSamples.addAndGet(-nextChunk.second.size.toLong())

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
                    // Blocking hardware write isolated on dedicated audioDispatcher
                    audioPlayer.writeSuspended(nextChunk.second)
                    val playDuration = System.currentTimeMillis() - playStart
                    TtsMetricsLogger.recordPlaybackComplete(nextChunk.first, playDuration)

                    currentChunkIndex = nextChunk.first + 1
                    playIndex = currentChunkIndex

                    if (totalChunks > 0) {
                        val pct = ((currentChunkIndex.toFloat() / totalChunks) * 100).toInt().coerceIn(0, 100)
                        notifyProgress(pct)
                    }
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
        const val DEFAULT_MAX_QUEUE_CAPACITY = 4

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
                    if (quantizationType == OnnxSessionManager.QuantizationType.FP32) {
                        safeFallbackDurationMs
                    } else if (cpuCores < 4) {
                        2500L
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
