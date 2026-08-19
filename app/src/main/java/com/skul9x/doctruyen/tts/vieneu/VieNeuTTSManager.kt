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
            pendingTitle = null
            pendingContent = null
            speakWithTitlePause(title, content)
        } else if (pendingContent != null) {
            val content = pendingContent!!
            pendingContent = null
            speakContent(content)
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
     * Start speaking text content.
     */
    fun speak(text: String) = speakContent(text)

    /**
     * Start speaking text content.
     */
    fun speakContent(content: String, pauseConfig: PauseConfig = getLoadedPauseConfig()) {
        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.speak(content)
            return
        }

        if (currentState == TTSState.INITIALIZING) {
            pendingTitle = null
            pendingContent = content
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
    fun speakWithTitlePause(title: String, content: String, pauseConfig: PauseConfig = getLoadedPauseConfig()) {
        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.speakWithTitlePause(title, content)
            return
        }

        if (currentState == TTSState.INITIALIZING) {
            pendingTitle = title
            pendingContent = content
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
