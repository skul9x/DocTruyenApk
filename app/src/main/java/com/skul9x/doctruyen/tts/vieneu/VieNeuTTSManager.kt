package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.util.Log
import com.skul9x.doctruyen.tts.AndroidTTSManager
import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType
import com.skul9x.doctruyen.utils.UserConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Text-to-Speech Manager using offline VieNeu ONNX engine and neural audio pipeline.
 * Drop-in replacement for the original Android TextToSpeech manager with identical state callbacks.
 */
class VieNeuTTSManager(
    context: Context? = null,
    private val onStateChange: (TTSState) -> Unit,
    private val onProgressChange: ((Int) -> Unit)? = null,
    private val customEngine: VieNeuOnnxEngine? = null,
    private val customPipeline: TTSAudioPipeline? = null
) {

    enum class TTSState {
        IDLE,
        INITIALIZING,
        READY,
        PLAYING,
        PAUSED,
        ERROR
    }

    private val appContext: Context? = try {
        context?.applicationContext ?: context
    } catch (e: Throwable) {
        null
    }
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var engine: VieNeuOnnxEngine? = customEngine
    private var pipeline: TTSAudioPipeline? = customPipeline
    private var fallbackManager: AndroidTTSManager? = null
    private var isUsingFallback = false

    var voiceName: String = "Trúc Ly"
        private set

    @Volatile
    var currentState: TTSState = TTSState.IDLE
        private set

    private var pendingTitle: String? = null
    private var pendingContent: String? = null
    private var pendingStoryId: Int = 0

    private var prewarmJob: kotlinx.coroutines.Job? = null

    init {
        initManager()
    }

    private fun notifyStateChange(state: TTSState) {
        currentState = state
        try {
            onStateChange(state)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in onStateChange callback", e)
        }
    }

    private fun initManager() {
        notifyStateChange(TTSState.INITIALIZING)

        // Load saved voice from user preferences
        val savedVoice = appContext?.let { UserConfig.getTtsVoice(it) }
        if (!savedVoice.isNullOrEmpty()) {
            voiceName = savedVoice
        }

        if (customEngine != null && customPipeline != null) {
            engine = customEngine
            pipeline = customPipeline
            notifyStateChange(TTSState.READY)
            checkPendingSpeech()
            return
        }

        val ctx = appContext
        if (ctx == null) {
            notifyStateChange(TTSState.ERROR)
            return
        }

        scope.launch {
            try {
                DebugLogger.log(TAG, "🚀 Initializing VieNeuOnnxEngine...", LogType.INFO)
                val loadedEngine = withContext(Dispatchers.IO) {
                    VieNeuOnnxEngine.create(ctx)
                }
                engine = loadedEngine

                // Ensure voice is valid in loaded presets
                val available = VoicePresets.availableVoices
                if (available.isNotEmpty() && !available.contains(voiceName)) {
                    voiceName = if (available.contains(VoicePresets.defaultVoiceName)) {
                        VoicePresets.defaultVoiceName
                    } else {
                        available.first()
                    }
                }

                val loadedPipeline = TTSAudioPipeline(
                    engine = loadedEngine,
                    voiceName = voiceName,
                    context = appContext,
                    dispatcher = Dispatchers.Default
                )
                pipeline = loadedPipeline

                DebugLogger.log(TAG, "✅ VieNeuOnnxEngine & TTSAudioPipeline ready. Voice: $voiceName", LogType.INFO)
                withContext(Dispatchers.Main) {
                    notifyStateChange(TTSState.READY)
                    checkPendingSpeech()
                }
            } catch (e: Throwable) {
                DebugLogger.logError(TAG, "❌ Failed to initialize VieNeu engine: ${e.message}. Attempting Android TTS fallback...", e)
                Log.e(TAG, "Failed to initialize VieNeuOnnxEngine", e)
                withContext(Dispatchers.Main) {
                    tryFallbackAndroidTTS()
                }
            }
        }
    }

    private fun tryFallbackAndroidTTS() {
        val ctx = appContext
        if (ctx == null) {
            notifyStateChange(TTSState.ERROR)
            return
        }
        try {
            isUsingFallback = true
            fallbackManager = AndroidTTSManager(
                context = ctx,
                onStateChange = { fallbackState ->
                    val mapped = when (fallbackState) {
                        AndroidTTSManager.TTSState.IDLE -> TTSState.IDLE
                        AndroidTTSManager.TTSState.INITIALIZING -> TTSState.INITIALIZING
                        AndroidTTSManager.TTSState.READY -> TTSState.READY
                        AndroidTTSManager.TTSState.PLAYING -> TTSState.PLAYING
                        AndroidTTSManager.TTSState.PAUSED -> TTSState.PAUSED
                        AndroidTTSManager.TTSState.ERROR -> TTSState.ERROR
                    }
                    notifyStateChange(mapped)
                },
                onProgressChange = onProgressChange
            )
            if (pendingTitle != null && pendingContent != null) {
                fallbackManager?.speakWithTitlePause(pendingTitle!!, pendingContent!!)
                pendingTitle = null
                pendingContent = null
            } else if (pendingContent != null) {
                fallbackManager?.speak(pendingContent!!)
                pendingContent = null
            }
        } catch (e: Throwable) {
            DebugLogger.logError(TAG, "❌ Android TTS fallback initialization also failed: ${e.message}")
            notifyStateChange(TTSState.ERROR)
        }
    }

    private fun checkPendingSpeech() {
        if (pendingTitle != null && pendingContent != null) {
            val title = pendingTitle!!
            val content = pendingContent!!
            val storyId = pendingStoryId
            pendingTitle = null
            pendingContent = null
            pendingStoryId = 0
            speakWithTitlePause(title, content, storyId = storyId)
        } else if (pendingContent != null) {
            val content = pendingContent!!
            val storyId = pendingStoryId
            pendingContent = null
            pendingStoryId = 0
            speakContent(content, storyId = storyId)
        }
    }

    private fun getLoadedPauseConfig(): PauseConfig {
        val ctx = appContext ?: return PauseConfig()
        return PauseConfig(
            sentencePauseMs = UserConfig.getPauseSentence(ctx),
            paragraphPauseMs = UserConfig.getPauseParagraph(ctx),
            clausePauseMs = UserConfig.getPauseClause(ctx)
        )
    }

    /**
     * Proactively pre-warm story synthesis in background.
     * Pre-synthesizes the first 2 chunks (Title + Opening sentence) into PrewarmedChunkCache
     * for instant playback with 0ms starvation latency when play is pressed.
     */
    fun prepareStory(
        storyId: Int,
        title: String,
        content: String,
        voice: String = voiceName,
        pauseConfig: PauseConfig = getLoadedPauseConfig()
    ) {
        if (isUsingFallback) return

        // Cancel previous pre-warm job if running
        cancelPrewarm()

        // Clear cache for previous stories if different storyId
        PrewarmedChunkCache.clearPreviousStory(storyId)

        val eng = engine ?: return

        prewarmJob = scope.launch(Dispatchers.Default) {
            try {
                Log.d(TAG, "🔥 Starting pre-warming for story $storyId (voice: $voice)")
                val chunks = SmartTextSegmenter.segmentStory(title, content, pauseConfig = pauseConfig)
                val prewarmCount = minOf(2, chunks.size)

                for (i in 0 until prewarmCount) {
                    if (!isActive) break
                    if (PrewarmedChunkCache.contains(storyId, voice, i)) continue

                    val chunk = chunks[i]
                    val g2pStart = System.currentTimeMillis()
                    val phonemes = SeaG2P.phonemize(chunk.text)
                    val g2pDuration = System.currentTimeMillis() - g2pStart
                    if (!isActive) break

                    val inferStart = System.currentTimeMillis()
                    val pcmFloat = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        eng.infer(phonemes = phonemes, voiceName = voice)
                    }
                    val inferDuration = System.currentTimeMillis() - inferStart
                    if (!isActive) break

                    val rawPcm16 = PcmUtils.floatToShort(pcmFloat)
                    val speechPcm = PcmUtils.applyMicroFade(rawPcm16)
                    val pauseMs = chunk.pauseDurationMs.toLong()

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

                    PrewarmedChunkCache.put(storyId, voice, i, combinedPcm)
                    Log.d(TAG, "⚡ Pre-warmed chunk $i/$prewarmCount for story $storyId (G2P: ${g2pDuration}ms, Infer: ${inferDuration}ms)")
                }
                Log.d(TAG, "✅ Finished pre-warming story $storyId")
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.d(TAG, "⏹️ Pre-warming cancelled for story $storyId")
            } catch (e: Throwable) {
                Log.w(TAG, "⚠️ Pre-warming failed for story $storyId: ${e.message}")
            }
        }
    }

    /**
     * Cancel any active background pre-warm job.
     */
    fun cancelPrewarm() {
        prewarmJob?.cancel()
        prewarmJob = null
    }

    /**
     * Start speaking text content.
     */
    fun speak(text: String, storyId: Int = 0) = speakContent(text, storyId = storyId)

    /**
     * Start speaking text content.
     */
    fun speakContent(content: String, storyId: Int = 0, pauseConfig: PauseConfig = getLoadedPauseConfig()) {
        cancelPrewarm()

        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.speak(content)
            return
        }

        if (currentState == TTSState.INITIALIZING) {
            pendingTitle = null
            pendingContent = content
            pendingStoryId = storyId
            return
        }

        val pipe = pipeline
        if (pipe == null) {
            notifyStateChange(TTSState.ERROR)
            return
        }

        TtsMetricsLogger.startSession(storyTitle = content.take(30))
        notifyStateChange(TTSState.PLAYING)
        pipe.voiceName = voiceName
        pipe.startReading(
            text = content,
            storyId = storyId,
            onStateChange = { pipeState ->
                handlePipelineState(pipeState)
            },
            onProgress = onProgressChange,
            pauseConfig = pauseConfig
        )
    }

    /**
     * Speak story title, pause 1 second, then speak content.
     */
    fun speakWithTitlePause(title: String, content: String, storyId: Int = 0, pauseConfig: PauseConfig = getLoadedPauseConfig()) {
        cancelPrewarm()

        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.speakWithTitlePause(title, content)
            return
        }

        if (currentState == TTSState.INITIALIZING) {
            pendingTitle = title
            pendingContent = content
            pendingStoryId = storyId
            return
        }

        val pipe = pipeline
        if (pipe == null) {
            notifyStateChange(TTSState.ERROR)
            return
        }

        TtsMetricsLogger.startSession(storyTitle = title)
        notifyStateChange(TTSState.PLAYING)
        pipe.voiceName = voiceName
        pipe.startReadingWithTitlePause(
            title = title,
            content = content,
            storyId = storyId,
            onStateChange = { pipeState ->
                handlePipelineState(pipeState)
            },
            onProgress = onProgressChange,
            pauseConfig = pauseConfig
        )
    }

    private fun handlePipelineState(pipeState: TTSAudioPipeline.PipelineState) {
        when (pipeState) {
            is TTSAudioPipeline.PipelineState.Idle -> {
                notifyStateChange(TTSState.READY)
            }
            is TTSAudioPipeline.PipelineState.Buffering -> {
                notifyStateChange(TTSState.PLAYING)
            }
            is TTSAudioPipeline.PipelineState.Synthesizing -> {
                notifyStateChange(TTSState.PLAYING)
            }
            is TTSAudioPipeline.PipelineState.Playing -> {
                notifyStateChange(TTSState.PLAYING)
            }
            is TTSAudioPipeline.PipelineState.Paused -> {
                notifyStateChange(TTSState.PAUSED)
            }
            is TTSAudioPipeline.PipelineState.Error -> {
                DebugLogger.logError(TAG, "Pipeline error: ${pipeState.message}")
                notifyStateChange(TTSState.ERROR)
            }
        }
    }

    /**
     * Pause reading. Preserves current chunk position.
     */
    fun pause() {
        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.pause()
            return
        }
        pipeline?.pause()
        notifyStateChange(TTSState.PAUSED)
    }

    /**
     * Resume reading from preserved chunk position.
     */
    fun resume() {
        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.resume()
            return
        }
        if (currentState == TTSState.PAUSED) {
            notifyStateChange(TTSState.PLAYING)
            pipeline?.resume()
        }
    }

    /**
     * Stop reading and reset position.
     */
    fun stop() {
        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.stop()
            return
        }
        pipeline?.stop()
        notifyStateChange(TTSState.READY)
        onProgressChange?.invoke(0)
    }

    /**
     * Check if speech is actively playing or synthesizing.
     */
    fun isPlaying(): Boolean {
        if (isUsingFallback && fallbackManager != null) {
            return fallbackManager?.isPlaying() == true
        }
        return currentState == TTSState.PLAYING
    }

    /**
     * Check if speech is currently paused.
     */
    fun isPaused(): Boolean {
        if (isUsingFallback && fallbackManager != null) {
            return fallbackManager?.isPaused() == true
        }
        return currentState == TTSState.PAUSED
    }

    /**
     * Set active VieNeu preset voice name.
     */
    fun setVoice(name: String) {
        voiceName = name
        pipeline?.voiceName = name
        appContext?.let { UserConfig.setTtsVoice(it, name) }
    }

    /**
     * Get list of all available preset voice names.
     */
    fun getAvailableVoices(): List<String> {
        val presets = VoicePresets.availableVoices
        if (presets.isNotEmpty()) return presets
        return listOf("Trúc Ly", "Minh Đức", "Phạm Tuyên", "Thái Sơn", "Xuân Vĩnh", "Thanh Bình", "Ngọc Linh", "Đoan Trang")
    }

    /**
     * Release all TTS resources and ONNX engine sessions.
     */
    fun shutdown() {
        cancelPrewarm()
        scope.cancel()
        pipeline?.release()
        pipeline = null
        engine?.close()
        engine = null
        fallbackManager?.shutdown()
        fallbackManager = null
        notifyStateChange(TTSState.IDLE)
    }

    companion object {
        private const val TAG = "VieNeuTTSManager"
    }
}
