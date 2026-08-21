package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.util.Log
import com.skul9x.doctruyen.tts.AndroidTTSManager
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.tts.ITTSManager.TTSState
import com.skul9x.doctruyen.tts.VoiceInfo
import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType
import com.skul9x.doctruyen.utils.UserConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

typealias TTSState = ITTSManager.TTSState

/**
 * Text-to-Speech Manager using offline VieNeu ONNX engine and neural audio pipeline.
 * Drop-in replacement for the original Android TextToSpeech manager with identical state callbacks.
 */
class VieNeuTTSManager(
    context: Context? = null,
    private val onStateChange: (ITTSManager.TTSState) -> Unit,
    private val onProgressChange: ((Int) -> Unit)? = null,
    private val customEngine: VieNeuOnnxEngine? = null,
    private val customPipeline: TTSAudioPipeline? = null
) : ITTSManager {

    override val engineType: ITTSManager.EngineType
        get() = ITTSManager.EngineType.VIENEU

    override val currentVoiceName: String
        get() = voiceName

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
    override var currentState: ITTSManager.TTSState = ITTSManager.TTSState.IDLE
        private set

    private var pendingTitle: String? = null
    private var pendingContent: String? = null
    private var pendingStoryId: Int = 0

    private var prewarmJob: kotlinx.coroutines.Job? = null

    init {
        initManager()
    }

    private fun notifyStateChange(state: ITTSManager.TTSState) {
        if (currentState == state) {
            return
        }
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
            if (pendingTitle != null || pendingContent != null) {
                checkPendingSpeech()
            } else {
                notifyStateChange(TTSState.READY)
            }
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
                    SeaG2P.warmUp(ctx)
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

                val prebufferChunks = appContext?.let { UserConfig.getPrebufferChunks(it) } ?: UserConfig.DEFAULT_PREBUFFER_CHUNKS
                val loadedPipeline = TTSAudioPipeline(
                    engine = loadedEngine,
                    voiceName = voiceName,
                    context = appContext,
                    dispatcher = Dispatchers.Default,
                    quantizationType = loadedEngine.quantizationType,
                    preBufferChunkCount = prebufferChunks
                )
                pipeline = loadedPipeline

                DebugLogger.log(TAG, "✅ VieNeuOnnxEngine & TTSAudioPipeline ready. Voice: $voiceName", LogType.INFO)
                if (pendingTitle != null || pendingContent != null) {
                    checkPendingSpeech()
                } else {
                    notifyStateChange(TTSState.READY)
                }
            } catch (e: Throwable) {
                DebugLogger.logError(TAG, "❌ Failed to initialize VieNeu engine: ${e.message}. Attempting Android TTS fallback...", e)
                Log.e(TAG, "Failed to initialize VieNeuOnnxEngine", e)
                tryFallbackAndroidTTS()
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
                pendingStoryId = 0
            } else if (pendingContent != null) {
                fallbackManager?.speak(pendingContent!!)
                pendingContent = null
                pendingStoryId = 0
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
    /**
     * Proactively pre-warm story synthesis in background.
     * Pre-synthesizes the first 2 chunks (Title + Opening sentence) into PrewarmedChunkCache
     * for instant playback with 0ms starvation latency when play is pressed.
     */
    private fun prepareStoryInternal(
        storyId: Int,
        title: String,
        content: String,
        voice: String? = null,
        pauseConfig: PauseConfig = getLoadedPauseConfig()
    ) {
        if (isUsingFallback) return

        val effectiveVoice = voice ?: appContext?.let { UserConfig.getTtsVoice(it) } ?: voiceName

        // Cancel previous pre-warm job if running
        cancelPrewarm()

        // Clear cache for previous stories if different storyId
        PrewarmedChunkCache.clearPreviousStory(storyId)

        val eng = engine ?: return

        prewarmJob = scope.launch(Dispatchers.Default) {
            try {
                Log.d(TAG, "🔥 Starting pre-warming for story $storyId (voice: $effectiveVoice)")
                val chunks = SmartTextSegmenter.segmentStory(title, content, pauseConfig = pauseConfig)
                val prewarmCount = minOf(2, chunks.size)

                for (i in 0 until prewarmCount) {
                    if (!isActive) break
                    if (PrewarmedChunkCache.contains(storyId, effectiveVoice, i)) continue

                    val chunk = chunks[i]
                    val g2pStart = System.currentTimeMillis()
                    val phonemes = SeaG2P.phonemize(chunk.text)
                    val g2pDuration = System.currentTimeMillis() - g2pStart
                    if (!isActive) break

                    val inferStart = System.currentTimeMillis()
                    val pcmFloat = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        eng.infer(phonemes = phonemes, voiceName = effectiveVoice)
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

                    PrewarmedChunkCache.put(storyId, effectiveVoice, i, combinedPcm)
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
     * Start speaking text content.
     */
    fun speakContent(content: String, storyId: Int = 0, pauseConfig: PauseConfig = getLoadedPauseConfig()) {
        cancelPrewarm()

        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.speak(content)
            return
        }

        if (currentState == TTSState.INITIALIZING && (pipeline == null || engine == null)) {
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

        notifyStateChange(TTSState.PLAYING)
        pipe.voiceName = voiceName
        pipe.preBufferChunkCount = appContext?.let { UserConfig.getPrebufferChunks(it) } ?: pipe.preBufferChunkCount
        val config = if (pauseConfig.sentencePauseMs > 0) pauseConfig else getLoadedPauseConfig()
        pipe.startReading(
            text = content,
            storyId = storyId,
            onStateChange = { pipeState ->
                handlePipelineState(pipeState)
            },
            onProgress = onProgressChange,
            pauseConfig = config
        )
    }

    /**
     * Speak story title, pause 1 second, then speak content.
     */
    private fun speakWithTitlePauseInternal(title: String, content: String, storyId: Int = 0, pauseConfig: PauseConfig = getLoadedPauseConfig()) {
        cancelPrewarm()

        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.speakWithTitlePause(title, content)
            return
        }

        if (currentState == TTSState.INITIALIZING && (pipeline == null || engine == null)) {
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

        notifyStateChange(TTSState.PLAYING)
        pipe.voiceName = voiceName
        pipe.preBufferChunkCount = appContext?.let { UserConfig.getPrebufferChunks(it) } ?: pipe.preBufferChunkCount
        val config = if (pauseConfig.sentencePauseMs > 0) pauseConfig else getLoadedPauseConfig()
        pipe.startReadingWithTitlePause(
            title = title,
            content = content,
            storyId = storyId,
            onStateChange = { pipeState ->
                handlePipelineState(pipeState)
            },
            onProgress = onProgressChange,
            pauseConfig = config
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
     * Check if speech is actively playing or synthesizing.
     */
    override fun isPlaying(): Boolean {
        if (isUsingFallback && fallbackManager != null) {
            return fallbackManager?.isPlaying() == true
        }
        return currentState == TTSState.PLAYING
    }

    /**
     * Check if speech is currently paused.
     */
    override fun isPaused(): Boolean {
        if (isUsingFallback && fallbackManager != null) {
            return fallbackManager?.isPaused() == true
        }
        return currentState == TTSState.PAUSED
    }

    /**
     * Set active VieNeu preset voice name.
     */
    override fun setVoice(voiceName: String) {
        val oldVoice = this.voiceName
        this.voiceName = voiceName
        pipeline?.voiceName = voiceName
        if (oldVoice != voiceName) {
            PrewarmedChunkCache.clear()
        }
        appContext?.let { UserConfig.setTtsVoice(it, voiceName) }
    }

    /**
     * Get list of all available preset voice infos.
     */
    override fun getAvailableVoices(): List<VoiceInfo> {
        val presets = VoicePresets.getVoicePresets()
        if (presets.isNotEmpty()) {
            return presets.map { preset ->
                VoiceInfo(
                    id = preset.name,
                    displayName = preset.getFormattedDisplayName(),
                    description = preset.getFormattedDescription(),
                    gender = preset.genderDisplay,
                    region = preset.regionDisplay,
                    isDefault = preset.name == VoicePresets.defaultVoiceName
                )
            }
        }
        val defaultList = listOf("Trúc Ly", "Minh Đức", "Phạm Tuyên", "Thái Sơn", "Xuân Vĩnh", "Thanh Bình", "Ngọc Linh", "Đoan Trang")
        return defaultList.map { name ->
            VoiceInfo(
                id = name,
                displayName = name,
                description = "VieNeu Neural Voice",
                isDefault = name == "Trúc Ly"
            )
        }
    }

    override fun setSpeed(speed: Float) {
        appContext?.let { UserConfig.setTtsSpeed(it, speed) }
    }

    override fun setPitch(pitch: Float) {
        appContext?.let { UserConfig.setTtsPitch(it, pitch) }
    }

    override fun speak(text: String, storyId: Int) {
        speakContent(text, storyId = storyId)
    }

    override fun speakWithTitlePause(title: String, content: String, storyId: Int, pauseConfig: PauseConfig) {
        speakWithTitlePauseInternal(title, content, storyId, pauseConfig)
    }

    override fun pause() {
        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.pause()
            return
        }
        pipeline?.pause()
        notifyStateChange(TTSState.PAUSED)
    }

    override fun resume() {
        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.resume()
            return
        }
        if (currentState == TTSState.PAUSED) {
            notifyStateChange(TTSState.PLAYING)
            pipeline?.resume()
        }
    }

    override fun stop() {
        if (isUsingFallback && fallbackManager != null) {
            fallbackManager?.stop()
            return
        }
        pipeline?.stop()
        notifyStateChange(TTSState.READY)
        onProgressChange?.invoke(0)
    }

    override fun prepareStory(
        storyId: Int,
        title: String,
        content: String,
        voice: String?,
        pauseConfig: PauseConfig
    ) {
        prepareStoryInternal(storyId, title, content, voice, pauseConfig)
    }

    override fun cancelPrewarm() {
        prewarmJob?.cancel()
        prewarmJob = null
    }

    fun setPrebufferChunks(chunks: Int) {
        val clamped = chunks.coerceIn(UserConfig.MIN_PREBUFFER_CHUNKS, UserConfig.MAX_PREBUFFER_CHUNKS)
        pipeline?.preBufferChunkCount = clamped
        appContext?.let { UserConfig.setPrebufferChunks(it, clamped) }
    }

    fun getPrebufferChunks(): Int {
        return pipeline?.preBufferChunkCount ?: appContext?.let { UserConfig.getPrebufferChunks(it) } ?: UserConfig.DEFAULT_PREBUFFER_CHUNKS
    }

    /**
     * Dynamic precision switching and hot-reloading at runtime.
     * Atomically stops active synthesis/playback, closes existing engine,
     * invalidates PrewarmedChunkCache, creates new VieNeuOnnxEngine with target precision,
     * reconnects TTSAudioPipeline, and notifies READY state.
     */
    fun setPrecision(precision: String) {
        val ctx = appContext
        if (ctx != null) {
            UserConfig.setTtsPrecision(ctx, precision)
        }
        reloadEngine(precision)
    }

    @Synchronized
    fun reloadEngine(precision: String = appContext?.let { UserConfig.getTtsPrecision(it) } ?: "INT8") {
        val normPrecision = when (precision.trim().uppercase()) {
            "INT8" -> "INT8"
            "FP32" -> "FP32"
            else -> "FP32"
        }

        DebugLogger.log(TAG, "🔄 Dynamic engine reload requested [Target Precision: $normPrecision]", LogType.INFO)

        // 1. Cancel background prewarm job
        cancelPrewarm()

        // 2. Atomically stop active synthesis and playback
        pipeline?.stop()
        notifyStateChange(TTSState.INITIALIZING)

        // 3. Clear prewarmed cache across model architectures
        PrewarmedChunkCache.clear()

        // 4. Release old pipeline and ONNX sessions cleanly
        pipeline?.release()
        pipeline = null
        engine?.close()
        engine = null
        System.gc()

        val ctx = appContext
        scope.launch {
            try {
                val newEngine = withContext(Dispatchers.IO) {
                    if (ctx != null) {
                        VieNeuOnnxEngine.create(ctx, precision = normPrecision)
                    } else {
                        val baseAssetsDir = if (File("app/src/main/assets/vieneu").exists()) {
                            File("app/src/main/assets/vieneu")
                        } else {
                            File("src/main/assets/vieneu")
                        }
                        val backboneDir = OnnxSessionManager.getBackboneDir(baseAssetsDir, normPrecision)
                        val codecDir = File(baseAssetsDir, "codec")
                        val sessionManager = OnnxSessionManager.createFromFiles(
                            backboneDir = backboneDir,
                            codecDir = codecDir,
                            threads = 2,
                            precision = normPrecision
                        )
                        val configFile = File(backboneDir, "config.json")
                        val config = VieNeuConfig.fromJson(configFile.readText(Charsets.UTF_8))
                        val tokenizerFile = File(backboneDir, "tokenizer.json")
                        val tokenizer = VieNeuTokenizer.fromFile(tokenizerFile)
                        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
                        val headsReader = NpzReader.read(headsFile)
                        VieNeuOnnxEngine(
                            sessionManager = sessionManager,
                            config = config,
                            tokenizer = tokenizer,
                            textEmbeddings = headsReader.get2D("text_emb")!!,
                            audioEmbeddings = headsReader.get3D("audio_emb")!!,
                            xvecW = headsReader.get2D("xvec_w"),
                            xvecB = headsReader.getFloatArray("xvec_b"),
                            xvecLnW = headsReader.getFloatArray("xvec_ln_w"),
                            xvecLnB = headsReader.getFloatArray("xvec_ln_b"),
                            xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f
                        )
                    }
                }
                engine = newEngine

                val prebufferChunks = ctx?.let { UserConfig.getPrebufferChunks(it) } ?: UserConfig.DEFAULT_PREBUFFER_CHUNKS
                val newPipeline = TTSAudioPipeline(
                    engine = newEngine,
                    voiceName = voiceName,
                    context = appContext,
                    dispatcher = Dispatchers.Default,
                    quantizationType = newEngine.quantizationType,
                    preBufferChunkCount = prebufferChunks
                )
                pipeline = newPipeline

                TtsMetricsLogger.logEngineReload(normPrecision)
                DebugLogger.log(TAG, "✅ VieNeuOnnxEngine hot-reloaded successfully with precision: $normPrecision [EP: ${newEngine.executionProvider}]", LogType.INFO)

                if (pendingTitle != null || pendingContent != null) {
                    checkPendingSpeech()
                } else {
                    notifyStateChange(TTSState.READY)
                }
            } catch (e: Throwable) {
                DebugLogger.logError(TAG, "❌ Failed to reload VieNeu engine with precision $normPrecision: ${e.message}", e)
                tryFallbackAndroidTTS()
            }
        }
    }

    /**
     * Release all TTS resources and ONNX engine sessions.
     */
    override fun shutdown() {
        cancelPrewarm()
        scope.cancel()
        pipeline?.stop()
        pipeline?.release()
        pipeline = null
        engine?.close()
        engine = null
        PrewarmedChunkCache.clear()
        fallbackManager?.shutdown()
        fallbackManager = null
        System.gc()
        notifyStateChange(TTSState.IDLE)
    }

    internal fun getEngineForTesting(): VieNeuOnnxEngine? = engine

    companion object {
        private const val TAG = "VieNeuTTSManager"
    }
}
