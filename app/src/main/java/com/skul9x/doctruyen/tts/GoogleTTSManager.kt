package com.skul9x.doctruyen.tts

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.skul9x.doctruyen.tts.vieneu.PauseConfig
import com.skul9x.doctruyen.tts.vieneu.SmartTextSegmenter
import com.skul9x.doctruyen.tts.vieneu.TextChunk
import com.skul9x.doctruyen.tts.vieneu.TtsMetricsLogger
import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType
import com.skul9x.doctruyen.utils.UserConfig
import java.util.Locale

/**
 * Text-to-Speech Engine contract wrapper for testing and concrete Android TextToSpeech.
 */
interface ITtsEngineWrapper {
    fun setSpeechRate(rate: Float): Int
    fun setPitch(pitch: Float): Int
    fun setLanguage(locale: Locale): Int
    fun setVoice(voice: Voice): Int
    fun setVoiceByName(voiceName: String): Int
    fun getVoices(): Set<Voice>?
    fun getVoiceInfoList(): List<VoiceInfo>?
    fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int
    fun speakLegacy(text: String, queueMode: Int, params: HashMap<String, String>): Int
    fun playSilentUtterance(durationMs: Long, queueMode: Int, utteranceId: String): Int
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener)
    fun stop(): Int
    fun shutdown()
}

/**
 * Default wrapper around Android's TextToSpeech
 */
class AndroidTtsEngineWrapper(private val tts: TextToSpeech) : ITtsEngineWrapper {
    override fun setSpeechRate(rate: Float): Int = tts.setSpeechRate(rate)
    override fun setPitch(pitch: Float): Int = tts.setPitch(pitch)
    override fun setLanguage(locale: Locale): Int = tts.setLanguage(locale)
    override fun setVoice(voice: Voice): Int = tts.setVoice(voice)
    override fun setVoiceByName(voiceName: String): Int {
        val matchedVoice = tts.voices?.find { it.name == voiceName }
        return if (matchedVoice != null) tts.setVoice(matchedVoice) else TextToSpeech.ERROR
    }
    override fun getVoices(): Set<Voice>? = tts.voices
    override fun getVoiceInfoList(): List<VoiceInfo>? = null
    override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int =
        tts.speak(text, queueMode, params, utteranceId)
    @Suppress("DEPRECATION")
    override fun speakLegacy(text: String, queueMode: Int, params: HashMap<String, String>): Int =
        tts.speak(text, queueMode, params)
    override fun playSilentUtterance(durationMs: Long, queueMode: Int, utteranceId: String): Int =
        tts.playSilentUtterance(durationMs, queueMode, utteranceId)
    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
        tts.setOnUtteranceProgressListener(listener)
    }
    override fun stop(): Int = tts.stop()
    override fun shutdown() = tts.shutdown()
}

/**
 * Text-to-Speech Manager using Google TTS / Android System TextToSpeech engine.
 * Target package: com.google.android.tts with graceful fallback to system default.
 */
class GoogleTTSManager(
    context: Context,
    private val onStateChange: (ITTSManager.TTSState) -> Unit,
    private val onProgressChange: ((Int) -> Unit)? = null,
    customEngineWrapper: ITtsEngineWrapper? = null
) : ITTSManager {

    companion object {
        private const val TAG = "GoogleTTSManager"
        const val GOOGLE_TTS_PACKAGE = "com.google.android.tts"
    }

    private val appContext: Context? = try {
        context.applicationContext ?: context
    } catch (e: Throwable) {
        context
    }

    override val engineType: ITTSManager.EngineType
        get() = ITTSManager.EngineType.GOOGLE_TTS

    private var engineWrapper: ITtsEngineWrapper? = customEngineWrapper

    @Volatile
    override var currentState: ITTSManager.TTSState = ITTSManager.TTSState.IDLE
        private set

    override var currentVoiceName: String = ""
        private set

    private var pendingText: String? = null
    private var pendingTitle: String? = null
    private var pendingStoryId: Int = 0

    // Full text storage for progress tracking and resume
    private var fullStoryContent: String = ""
    private var fullStoryTitle: String = ""
    private var segments: List<TextChunk> = emptyList()
    private var currentSegmentIndex: Int = 0
    private var isTitleSegment: Boolean = false

    private var isPausedByUser: Boolean = false

    private val audioManager = try {
        appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (e: Throwable) {
        null
    }
    private var focusRequest: AudioFocusRequestCompat? = null
    private var wasPausedByTransientLoss = false

    init {
        initTTS()
    }

    private fun notifyStateChange(state: ITTSManager.TTSState) {
        currentState = state
        try {
            onStateChange(state)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in onStateChange callback", e)
        }
    }

    private fun initTTS() {
        notifyStateChange(ITTSManager.TTSState.INITIALIZING)

        if (engineWrapper != null) {
            configureEngine()
            return
        }

        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureEngine()
            } else {
                DebugLogger.logError(TAG, "❌ Failed to initialize Google TTS engine, status=$status")
                notifyStateChange(ITTSManager.TTSState.ERROR)
            }
        }

        try {
            DebugLogger.log(TAG, "🚀 Binding targeted Google TTS Engine ($GOOGLE_TTS_PACKAGE)...", LogType.INFO)
            val rawTts = TextToSpeech(appContext, listener, GOOGLE_TTS_PACKAGE)
            engineWrapper = AndroidTtsEngineWrapper(rawTts)
        } catch (e: Throwable) {
            DebugLogger.log(TAG, "⚠️ Failed to bind $GOOGLE_TTS_PACKAGE, falling back to default TTS: ${e.message}", LogType.INFO)
            try {
                val rawTts = TextToSpeech(appContext, listener)
                engineWrapper = AndroidTtsEngineWrapper(rawTts)
            } catch (ex: Throwable) {
                DebugLogger.logError(TAG, "❌ Failed to instantiate default TextToSpeech", ex)
                notifyStateChange(ITTSManager.TTSState.ERROR)
            }
        }
    }

    private fun configureEngine() {
        val eng = engineWrapper ?: run {
            notifyStateChange(ITTSManager.TTSState.ERROR)
            return
        }

        // Set Vietnamese language
        val viLocale = Locale("vi", "VN")
        val langResult = eng.setLanguage(viLocale)
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            DebugLogger.log(TAG, "⚠️ Vietnamese locale not fully supported by TTS engine, attempting Locale(\"vi\")", LogType.INFO)
            eng.setLanguage(Locale("vi"))
        }

        // Apply speed & pitch
        val speed = appContext?.let { UserConfig.getTtsSpeed(it) } ?: 1.0f
        val pitch = appContext?.let { UserConfig.getTtsPitch(it) } ?: 1.0f
        eng.setSpeechRate(speed)
        eng.setPitch(pitch)

        // Select voice
        val savedVoiceId = appContext?.let { UserConfig.getGoogleTtsVoice(it) }
        val available = getAvailableVoices()
        if (!savedVoiceId.isNullOrEmpty()) {
            val matching = available.find { it.id == savedVoiceId }
            if (matching != null) {
                setVoice(matching.id)
            } else if (available.isNotEmpty()) {
                setVoice(available.first().id)
            }
        } else if (available.isNotEmpty()) {
            setVoice(available.first().id)
        }

        setupUtteranceListener()

        DebugLogger.log(TAG, "✅ Google TTS initialized successfully. Voice: $currentVoiceName", LogType.INFO)
        notifyStateChange(ITTSManager.TTSState.READY)
        checkPendingSpeech()
    }

    private fun setupUtteranceListener() {
        engineWrapper?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                notifyStateChange(ITTSManager.TTSState.PLAYING)
            }

            override fun onDone(utteranceId: String?) {
                if (isPausedByUser) {
                    return
                }

                if (utteranceId != null && utteranceId.startsWith("seg_")) {
                    val nextIndex = currentSegmentIndex + 1
                    if (nextIndex < segments.size) {
                        currentSegmentIndex = nextIndex
                        playSegment(currentSegmentIndex)
                    } else {
                        // All chunks done
                        resetReadingState()
                        notifyStateChange(ITTSManager.TTSState.READY)
                        onProgressChange?.invoke(100)
                    }
                }
            }

            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                super.onRangeStart(utteranceId, start, end, frame)
                if (utteranceId != null && utteranceId.startsWith("seg_")) {
                    calculateAndReportProgress(currentSegmentIndex, start)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                DebugLogger.logError(TAG, "TTS utterance error: $utteranceId")
                notifyStateChange(ITTSManager.TTSState.ERROR)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                DebugLogger.logError(TAG, "TTS utterance error $utteranceId with code $errorCode")
                notifyStateChange(ITTSManager.TTSState.ERROR)
            }
        })
    }

    private fun calculateAndReportProgress(segmentIdx: Int, charOffsetInSegment: Int) {
        val totalLength = fullStoryContent.length
        if (totalLength <= 0) return

        var charsPlayedBefore = 0
        for (i in 0 until segmentIdx.coerceAtMost(segments.size)) {
            val s = segments[i]
            charsPlayedBefore += s.text.length
        }
        val currentTotalChars = (charsPlayedBefore + charOffsetInSegment).coerceIn(0, totalLength)
        val progress = ((currentTotalChars.toFloat() / totalLength) * 100).toInt().coerceIn(0, 100)
        onProgressChange?.invoke(progress)
    }

    private fun checkPendingSpeech() {
        if (pendingTitle != null && pendingText != null) {
            val title = pendingTitle!!
            val text = pendingText!!
            val sId = pendingStoryId
            pendingTitle = null
            pendingText = null
            pendingStoryId = 0
            speakWithTitlePause(title, text, sId)
        } else if (pendingText != null) {
            val text = pendingText!!
            val sId = pendingStoryId
            pendingText = null
            pendingStoryId = 0
            speak(text, sId)
        }
    }

    private fun getLoadedPauseConfig(): PauseConfig {
        return PauseConfig(
            sentencePauseMs = appContext?.let { UserConfig.getPauseSentence(it) } ?: 500,
            paragraphPauseMs = appContext?.let { UserConfig.getPauseParagraph(it) } ?: 1000,
            clausePauseMs = appContext?.let { UserConfig.getPauseClause(it) } ?: 250
        )
    }

    override fun speak(text: String, storyId: Int) {
        if (currentState == ITTSManager.TTSState.INITIALIZING) {
            pendingTitle = null
            pendingText = text
            pendingStoryId = storyId
            return
        }

        requestAudioFocus()
        fullStoryTitle = ""
        fullStoryContent = text
        isTitleSegment = false
        isPausedByUser = false

        segments = SmartTextSegmenter.segmentStory("", text, pauseConfig = getLoadedPauseConfig())
        currentSegmentIndex = 0

        TtsMetricsLogger.startSession(storyTitle = text.take(30))
        notifyStateChange(ITTSManager.TTSState.PLAYING)

        if (segments.isNotEmpty()) {
            playSegment(0)
        } else {
            notifyStateChange(ITTSManager.TTSState.READY)
        }
    }

    override fun speakWithTitlePause(
        title: String,
        content: String,
        storyId: Int,
        pauseConfig: PauseConfig
    ) {
        if (currentState == ITTSManager.TTSState.INITIALIZING) {
            pendingTitle = title
            pendingText = content
            pendingStoryId = storyId
            return
        }

        requestAudioFocus()
        fullStoryTitle = title
        fullStoryContent = content
        isPausedByUser = false

        val config = if (pauseConfig.sentencePauseMs > 0) pauseConfig else getLoadedPauseConfig()
        segments = SmartTextSegmenter.segmentStory(title, content, pauseConfig = config)
        currentSegmentIndex = 0

        TtsMetricsLogger.startSession(storyTitle = title)
        notifyStateChange(ITTSManager.TTSState.PLAYING)

        if (segments.isNotEmpty()) {
            playSegment(0)
        } else {
            notifyStateChange(ITTSManager.TTSState.READY)
        }
    }

    private fun playSegment(index: Int) {
        if (index >= segments.size) return
        val segment = segments[index]
        val utteranceId = "seg_$index"

        val eng = engineWrapper ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val params = Bundle()
            eng.speak(segment.text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (segment.pauseDurationMs > 0) {
                eng.playSilentUtterance(segment.pauseDurationMs.toLong(), TextToSpeech.QUEUE_ADD, "silence_$index")
            }
        } else {
            @Suppress("DEPRECATION")
            val params = HashMap<String, String>()
            params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId
            @Suppress("DEPRECATION")
            eng.speakLegacy(segment.text, TextToSpeech.QUEUE_FLUSH, params)
        }
    }

    override fun pause() {
        if (currentState == ITTSManager.TTSState.PLAYING) {
            isPausedByUser = true
            engineWrapper?.stop()
            notifyStateChange(ITTSManager.TTSState.PAUSED)
        }
    }

    override fun resume() {
        if (currentState == ITTSManager.TTSState.PAUSED && segments.isNotEmpty()) {
            requestAudioFocus()
            isPausedByUser = false
            notifyStateChange(ITTSManager.TTSState.PLAYING)
            playSegment(currentSegmentIndex)
        }
    }

    override fun stop() {
        isPausedByUser = false
        abandonAudioFocus()
        engineWrapper?.stop()
        resetReadingState()
        notifyStateChange(ITTSManager.TTSState.READY)
        onProgressChange?.invoke(0)
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
                        if (currentState == ITTSManager.TTSState.PAUSED && wasPausedByTransientLoss) {
                            wasPausedByTransientLoss = false
                            resume()
                        }
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        // For speech playback, ducking is handled or we can pause transiently
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        if (currentState == ITTSManager.TTSState.PLAYING) {
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

    private fun resetReadingState() {
        currentSegmentIndex = 0
        segments = emptyList()
        fullStoryContent = ""
        fullStoryTitle = ""
    }

    override fun isPlaying(): Boolean = currentState == ITTSManager.TTSState.PLAYING

    override fun isPaused(): Boolean = currentState == ITTSManager.TTSState.PAUSED

    override fun setVoice(voiceName: String) {
        currentVoiceName = voiceName
        val wrapper = engineWrapper ?: return
        val res = wrapper.setVoiceByName(voiceName)
        if (res == TextToSpeech.SUCCESS) {
            appContext?.let { UserConfig.setGoogleTtsVoice(it, voiceName) }
        } else {
            val allVoices = wrapper.getVoices()
            val matchedVoice = allVoices?.find { it.name == voiceName }
            if (matchedVoice != null) {
                wrapper.setVoice(matchedVoice)
                appContext?.let { UserConfig.setGoogleTtsVoice(it, voiceName) }
            }
        }
    }

    override fun getAvailableVoices(): List<VoiceInfo> {
        val eng = engineWrapper ?: return emptyList()
        val customInfoList = eng.getVoiceInfoList()
        if (customInfoList != null) {
            return customInfoList
        }

        val allVoices: Set<Voice>? = try {
            eng.getVoices()
        } catch (e: Throwable) {
            null
        }

        if (allVoices.isNullOrEmpty()) {
            return listOf(
                VoiceInfo(
                    id = "vi-vn-default",
                    displayName = "Google Tiếng Việt (Mặc định)",
                    description = "Giọng đọc hệ thống Tiếng Việt",
                    gender = "Tiêu chuẩn",
                    region = "Việt Nam",
                    isDefault = true
                )
            )
        }

        val viVoices = allVoices.filter { voice ->
            val lang = voice.locale?.language ?: ""
            val country = voice.locale?.country ?: ""
            val name = voice.name ?: ""
            lang.equals("vi", ignoreCase = true) || 
            (lang.startsWith("vi", ignoreCase = true) && country.equals("VN", ignoreCase = true)) ||
            name.contains("vi-vn", ignoreCase = true)
        }

        val targetList = if (viVoices.isNotEmpty()) viVoices else emptyList()

        return targetList.map { voice ->
            val id = voice.name ?: "vi-vn-default"
            val isDef = id.contains("vi-vn-x-vic-network", ignoreCase = true) ||
                    id.contains("vi-vn-x-vic-local", ignoreCase = true) ||
                    id.contains("vi-vn", ignoreCase = true)

            val gender = when {
                id.contains("-vic-", ignoreCase = true) -> "Nữ"
                id.contains("-vif-", ignoreCase = true) -> "Nam"
                id.contains("-vib-", ignoreCase = true) -> "Nữ"
                id.contains("-vid-", ignoreCase = true) -> "Nam"
                id.contains("-vie-", ignoreCase = true) -> "Nữ"
                id.contains("female", ignoreCase = true) -> "Nữ"
                id.contains("male", ignoreCase = true) -> "Nam"
                else -> "Tiêu chuẩn"
            }

            val quality = when {
                id.contains("network", ignoreCase = true) -> "Chất lượng cao (Online)"
                id.contains("local", ignoreCase = true) -> "Offline"
                else -> "Mặc định"
            }

            val friendlyName = formatVoiceDisplayName(id, gender, quality)

            VoiceInfo(
                id = id,
                displayName = friendlyName,
                description = "Google TTS · $gender · $quality",
                gender = gender,
                region = "Việt Nam",
                isDefault = isDef
            )
        }
    }

    private fun formatVoiceDisplayName(voiceName: String, gender: String, quality: String): String {
        // e.g. vi-vn-x-vic-network -> "Google Nữ (Online #1)"
        val suffix = voiceName.substringAfterLast("-", "")
        return if (suffix.isNotEmpty()) {
            "Google $gender ($quality)"
        } else {
            "Google $voiceName"
        }
    }

    override fun setSpeed(speed: Float) {
        engineWrapper?.setSpeechRate(speed)
        appContext?.let { UserConfig.setTtsSpeed(it, speed) }
    }

    override fun setPitch(pitch: Float) {
        engineWrapper?.setPitch(pitch)
        appContext?.let { UserConfig.setTtsPitch(it, pitch) }
    }

    override fun prepareStory(
        storyId: Int,
        title: String,
        content: String,
        voice: String?,
        pauseConfig: PauseConfig
    ) {
        // Google TTS handles direct playback without explicit pre-warming chunk caching
        if (voice != null) {
            setVoice(voice)
        }
    }

    override fun cancelPrewarm() {
        // No-op for Google TTS
    }

    override fun shutdown() {
        abandonAudioFocus()
        engineWrapper?.stop()
        engineWrapper?.shutdown()
        engineWrapper = null
        notifyStateChange(ITTSManager.TTSState.IDLE)
    }
}
