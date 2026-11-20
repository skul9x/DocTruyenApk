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
 * Cadence and sampling tuning profile for VieNeu preset voices.
 */
data class VoiceTuningProfile(
    val temperature: Float = DEFAULT_TEMPERATURE,
    val clausePauseMs: Int = DEFAULT_CLAUSE_PAUSE_MS,
    val sentencePauseMs: Int = DEFAULT_SENTENCE_PAUSE_MS
) {
    companion object {
        const val DEFAULT_TEMPERATURE = 0.45f
        const val DEFAULT_CLAUSE_PAUSE_MS = 180
        const val DEFAULT_SENTENCE_PAUSE_MS = 350

        val DEFAULT = VoiceTuningProfile()
        val THIEN_TAM_DUC = VoiceTuningProfile(
            temperature = 0.25f,
            clausePauseMs = 300,
            sentencePauseMs = 600
        )
    }
}

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

    var voiceName: String = VoicePresets.defaultVoiceName
        private set

    val tuningProfile: VoiceTuningProfile
        get() = getTuningProfile(voiceName)

    fun getTuningProfile(voice: String? = null): VoiceTuningProfile {
        return Companion.getTuningProfile(voice ?: voiceName)
    }

    fun resolveVoiceName(name: String?): String {
        return Companion.resolveVoiceName(name)
    }

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
        voiceName = if (!savedVoice.isNullOrEmpty()) {
            resolveVoiceName(savedVoice)
        } else {
            VoicePresets.defaultVoiceName
        }

        if (customEngine != null && customPipeline != null) {
            engine = customEngine
            pipeline = customPipeline
            customPipeline.voiceName = voiceName
            customPipeline.temperature = getTuningProfile(voiceName).temperature
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
                    val savedPrecision = UserConfig.getTtsPrecision(ctx)
                    SeaG2P.warmUp(ctx)
                    VieNeuOnnxEngine.create(ctx, precision = savedPrecision)
                }
                engine = loadedEngine

                // Ensure voice is valid in loaded presets
                voiceName = resolveVoiceName(voiceName)

                val prebufferChunks = appContext?.let { UserConfig.getPrebufferChunks(it) } ?: UserConfig.DEFAULT_PREBUFFER_CHUNKS
                val loadedPipeline = TTSAudioPipeline(
                    engine = loadedEngine,
                    voiceName = voiceName,
                    context = appContext,
                    dispatcher = Dispatchers.Default,
                    quantizationType = loadedEngine.quantizationType,
                    preBufferChunkCount = prebufferChunks,
                    temperature = getTuningProfile(voiceName).temperature
                )
                pipeline = loadedPipeline

                DebugLogger.log(TAG, "✅ VieNeuOnnxEngine & TTSAudioPipeline ready. Voice: $voiceName", LogType.INFO)
                if (pendingTitle != null || pendingContent != null) {
                    checkPendingSpeech()
                } else {
                    notifyStateChange(TTSState.READY)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                DebugLogger.log(TAG, "VieNeu initialization cancelled normally: ${e.message}", LogType.INFO)
                throw e
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

    fun getEffectivePauseConfig(targetVoice: String? = null): PauseConfig {
        return getLoadedPauseConfig(targetVoice)
    }

    private fun getLoadedPauseConfig(targetVoice: String? = null): PauseConfig {
        val target = resolveVoiceName(targetVoice ?: voiceName)
        val profile = getTuningProfile(target)
        val ctx = appContext
        val baseSentence = ctx?.let { UserConfig.getPauseSentence(it) } ?: profile.sentencePauseMs
        val baseParagraph = ctx?.let { UserConfig.getPauseParagraph(it) } ?: PauseType.PARAGRAPH_BREAK.defaultDurationMs
        val baseClause = ctx?.let { UserConfig.getPauseClause(it) } ?: profile.clausePauseMs

        return if (target == "Thiền Tâm Đức") {
            PauseConfig(
                sentencePauseMs = profile.sentencePauseMs,
                paragraphPauseMs = baseParagraph,
                clausePauseMs = profile.clausePauseMs
            )
        } else {
            PauseConfig(
                sentencePauseMs = baseSentence,
                paragraphPauseMs = baseParagraph,
                clausePauseMs = baseClause
            )
        }
    }

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

        val effectiveVoice = resolveVoiceName(voice ?: appContext?.let { UserConfig.getTtsVoice(it) } ?: voiceName)
        val profile = getTuningProfile(effectiveVoice)
        val effectivePauseConfig = if (effectiveVoice == "Thiền Tâm Đức") {
            pauseConfig.copy(
                clausePauseMs = profile.clausePauseMs,
                sentencePauseMs = profile.sentencePauseMs
            )
        } else {
            pauseConfig
        }

        // Cancel previous pre-warm job if running
        cancelPrewarm()

        // Clear cache for previous stories if different storyId
        PrewarmedChunkCache.clearPreviousStory(storyId)

        val eng = engine ?: return

        prewarmJob = scope.launch(Dispatchers.Default) {
            try {
                Log.d(TAG, "🔥 Starting pre-warming for story $storyId (voice: $effectiveVoice)")
                val chunks = SmartTextSegmenter.segmentStory(title, content, pauseConfig = effectivePauseConfig)
                val prewarmCount = minOf(2, chunks.size)

                for (i in 0 until prewarmCount) {
                    if (!isActive) break
                    if (PrewarmedChunkCache.contains(storyId, effectiveVoice, i)) continue

                    val chunk = chunks[i]
                    val g2pStart = System.currentTimeMillis()
                    val phonemes = phonemizeWithEmotionBypass(chunk.text)
                    val g2pDuration = System.currentTimeMillis() - g2pStart
                    if (!isActive) break

                    val inferStart = System.currentTimeMillis()
                    val pcmFloat = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        eng.infer(phonemes = phonemes, voiceName = effectiveVoice, temperature = profile.temperature)
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
        val targetVoice = resolveVoiceName(voiceName)
        val profile = getTuningProfile(targetVoice)
        pipe.voiceName = targetVoice
        pipe.temperature = profile.temperature
        pipe.preBufferChunkCount = appContext?.let { UserConfig.getPrebufferChunks(it) } ?: pipe.preBufferChunkCount
        val config = if (targetVoice == "Thiền Tâm Đức") {
            (if (pauseConfig.sentencePauseMs > 0) pauseConfig else getLoadedPauseConfig(targetVoice)).copy(
                clausePauseMs = profile.clausePauseMs,
                sentencePauseMs = profile.sentencePauseMs
            )
        } else {
            if (pauseConfig.sentencePauseMs > 0) pauseConfig else getLoadedPauseConfig(targetVoice)
        }
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
        val targetVoice = resolveVoiceName(voiceName)
        val profile = getTuningProfile(targetVoice)
        pipe.voiceName = targetVoice
        pipe.temperature = profile.temperature
        pipe.preBufferChunkCount = appContext?.let { UserConfig.getPrebufferChunks(it) } ?: pipe.preBufferChunkCount
        val config = if (targetVoice == "Thiền Tâm Đức") {
            (if (pauseConfig.sentencePauseMs > 0) pauseConfig else getLoadedPauseConfig(targetVoice)).copy(
                clausePauseMs = profile.clausePauseMs,
                sentencePauseMs = profile.sentencePauseMs
            )
        } else {
            if (pauseConfig.sentencePauseMs > 0) pauseConfig else getLoadedPauseConfig(targetVoice)
        }
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
        val resolved = resolveVoiceName(voiceName)
        val oldVoice = this.voiceName
        this.voiceName = resolved
        pipeline?.voiceName = resolved
        val profile = getTuningProfile(resolved)
        pipeline?.temperature = profile.temperature
        if (oldVoice != resolved) {
            PrewarmedChunkCache.clear()
        }
        appContext?.let { UserConfig.setTtsVoice(it, resolved) }
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
        val defaultList = listOf("Hải Đăng", "Trúc Ly", "Minh Đức", "Phạm Tuyên", "Thái Sơn", "Xuân Vĩnh", "Thanh Bình", "Ngọc Linh", "Đoan Trang")
        return defaultList.map { name ->
            VoiceInfo(
                id = name,
                displayName = name,
                description = "VieNeu Neural Voice",
                isDefault = name == VoicePresets.defaultVoiceName
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
                        val audioFlat = headsReader.getChannelFlatArrays("audio_emb")
                        VieNeuOnnxEngine(
                            sessionManager = sessionManager,
                            config = config,
                            tokenizer = tokenizer,
                            textEmbeddings = headsReader.get2D("text_emb")!!,
                            flatAudioEmbeddingsIn = audioFlat,
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
                    preBufferChunkCount = prebufferChunks,
                    temperature = getTuningProfile(voiceName).temperature
                )
                pipeline = newPipeline

                TtsMetricsLogger.logEngineReload(newEngine.quantizationType.name)
                DebugLogger.log(TAG, "✅ VieNeuOnnxEngine hot-reloaded successfully with precision: ${newEngine.quantizationType.name} [EP: ${newEngine.executionProvider}]", LogType.INFO)

                if (pendingTitle != null || pendingContent != null) {
                    checkPendingSpeech()
                } else {
                    notifyStateChange(TTSState.READY)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                DebugLogger.log(TAG, "VieNeu reload cancelled normally: ${e.message}", LogType.INFO)
                throw e
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
        const val DEFAULT_VOICE_NAME = "Hải Đăng"

        fun resolveVoiceName(voiceName: String?): String {
            return VoicePresets.resolveVoiceName(voiceName)
        }

        fun getTuningProfile(voiceName: String? = null): VoiceTuningProfile {
            val resolved = resolveVoiceName(voiceName)
            return when (resolved) {
                "Thiền Tâm Đức" -> VoiceTuningProfile.THIEN_TAM_DUC
                else -> VoiceTuningProfile.DEFAULT
            }
        }

        val EmotionTagParser = com.skul9x.doctruyen.tts.vieneu.EmotionTagParser

        fun phonemizeWithEmotionBypass(
            text: String,
            phonemizer: (String) -> String = { SeaG2P.phonemize(it) }
        ): String {
            return EmotionTagParser.phonemizeWithBypass(text, phonemizer)
        }

        fun phonemizeWithBypass(
            text: String,
            phonemizer: (String) -> String = { SeaG2P.phonemize(it) }
        ): String {
            return EmotionTagParser.phonemizeWithBypass(text, phonemizer)
        }
    }
}
