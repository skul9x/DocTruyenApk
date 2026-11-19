package com.skul9x.doctruyen.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.skul9x.doctruyen.tts.vieneu.PcmUtils
import com.skul9x.doctruyen.tts.vieneu.VieNeuOnnxEngine
import com.skul9x.doctruyen.tts.vieneu.VieNeuTTSManager
import com.skul9x.doctruyen.tts.vieneu.VoicePresets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Observable states for audio preview playback.
 */
sealed class AudioPreviewState {
    object Idle : AudioPreviewState() {
        override fun toString(): String = "Idle"
    }
    data class Loading(val voiceId: String) : AudioPreviewState()
    data class Playing(val voiceId: String) : AudioPreviewState()
    data class Stopped(val voiceId: String? = null) : AudioPreviewState()
    data class Error(val voiceId: String, val message: String) : AudioPreviewState()
}

/**
 * Thread-safe, strictly bounded in-memory LRU cache for synthesized PCM audio buffers.
 * Enforces both maximum item count (default 32) and maximum byte capacity (default 3 MB)
 * to avoid memory pressure during prolonged voice auditioning.
 */
open class AudioPreviewCache(
    val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    val maxSizeBytes: Long = DEFAULT_MAX_SIZE_BYTES
) {
    companion object {
        const val DEFAULT_MAX_ENTRIES = 32
        const val DEFAULT_MAX_SIZE_BYTES = 3L * 1024L * 1024L // 3 MB
    }

    private val lock = Any()
    // LinkedHashMap with accessOrder = true ensures LRU ordering on read/write
    private val map = LinkedHashMap<String, ShortArray>(maxEntries, 0.75f, true)

    @Volatile
    private var currentSizeBytes: Long = 0L

    val entryCount: Int
        get() = synchronized(lock) { map.size }

    val totalBytes: Long
        get() = synchronized(lock) { currentSizeBytes }

    fun put(voiceId: String, pcm: ShortArray) {
        synchronized(lock) {
            val entryBytes = pcm.size.toLong() * 2L // 16-bit PCM = 2 bytes per sample
            val existing = map.remove(voiceId)
            if (existing != null) {
                currentSizeBytes -= (existing.size.toLong() * 2L)
            }

            // Evict oldest entries until within entry count and memory budget
            while (map.isNotEmpty() && (map.size >= maxEntries || (currentSizeBytes + entryBytes) > maxSizeBytes)) {
                val it = map.entries.iterator()
                if (it.hasNext()) {
                    val oldest = it.next()
                    currentSizeBytes -= (oldest.value.size.toLong() * 2L)
                    it.remove()
                } else {
                    break
                }
            }

            map[voiceId] = pcm
            currentSizeBytes += entryBytes
        }
    }

    fun get(voiceId: String): ShortArray? {
        synchronized(lock) {
            return map[voiceId]
        }
    }

    fun contains(voiceId: String): Boolean {
        synchronized(lock) {
            return map.containsKey(voiceId)
        }
    }

    fun remove(voiceId: String): ShortArray? {
        synchronized(lock) {
            val removed = map.remove(voiceId)
            if (removed != null) {
                currentSizeBytes -= (removed.size.toLong() * 2L)
            }
            return removed
        }
    }

    fun clear() {
        synchronized(lock) {
            map.clear()
            currentSizeBytes = 0L
        }
    }
}

/**
 * Abstraction for preview PCM playback to support both production AudioTrack
 * and unit test simulation.
 */
interface IAudioPreviewPlayer {
    val isPlaying: Boolean
    fun play(pcm16: ShortArray, onCompletion: () -> Unit)
    fun stop()
    fun release()
}

/**
 * Low-latency AudioTrack static-mode player for brief voice preview greetings (~2s).
 * Cleans up and releases hardware AudioTrack in finally blocks to guarantee zero resource leaks.
 */
open class AndroidAudioPreviewPlayer(
    val sampleRate: Int = 48000
) : IAudioPreviewPlayer {

    private val lock = Any()
    private var audioTrack: AudioTrack? = null
    private var workerThread: Thread? = null

    @Volatile
    private var isCancelled = false

    @Volatile
    private var _isPlaying: Boolean = false

    override val isPlaying: Boolean
        get() = _isPlaying

    override fun play(pcm16: ShortArray, onCompletion: () -> Unit) {
        stop()
        if (pcm16.isEmpty()) {
            onCompletion()
            return
        }

        synchronized(lock) {
            isCancelled = false
            _isPlaying = true

            workerThread = Thread({
                var track: AudioTrack? = null
                try {
                    val minBufferSize = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize, pcm16.size * 2) else pcm16.size * 2

                    val attrBuilder = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)

                    val formatBuilder = AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)

                    val trackBuilder = AudioTrack.Builder()
                        .setAudioAttributes(attrBuilder.build())
                        .setAudioFormat(formatBuilder.build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STATIC)

                    track = trackBuilder.build()
                    synchronized(lock) {
                        audioTrack = track
                    }

                    if (track.state == AudioTrack.STATE_INITIALIZED && !isCancelled) {
                        track.write(pcm16, 0, pcm16.size)
                        if (!isCancelled) {
                            track.play()
                            val durationMs = (pcm16.size * 1000L) / sampleRate
                            val startTime = System.currentTimeMillis()
                            while (!isCancelled && (System.currentTimeMillis() - startTime) < durationMs) {
                                Thread.sleep(20)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    // Ignored on interruption or test environment
                } finally {
                    synchronized(lock) {
                        try {
                            track?.let {
                                if (it.state == AudioTrack.STATE_INITIALIZED) {
                                    if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                        it.stop()
                                    }
                                    it.flush()
                                }
                                it.release()
                            }
                        } catch (e: Throwable) {}
                        if (audioTrack == track) {
                            audioTrack = null
                        }
                        val wasCancelled = isCancelled
                        _isPlaying = false
                        if (!wasCancelled) {
                            onCompletion()
                        }
                    }
                }
            }, "AudioPreviewPlaybackThread").apply { start() }
        }
    }

    override fun stop() {
        synchronized(lock) {
            isCancelled = true
            _isPlaying = false
            try {
                audioTrack?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            it.stop()
                        }
                        it.flush()
                    }
                }
            } catch (e: Throwable) {}
            workerThread?.interrupt()
            workerThread = null
        }
    }

    override fun release() {
        stop()
        synchronized(lock) {
            try {
                audioTrack?.release()
            } catch (e: Throwable) {}
            audioTrack = null
        }
    }
}

/**
 * Dedicated lifecycle-safe Audio Preview Engine.
 * Supports:
 * - Single-active playback semantics (triggering B immediately halts A without overlap).
 * - Sample greeting generation ("Xin chào, tôi là [Tên giọng], rất vui được đọc truyện cùng bạn.").
 * - In-memory bounded PCM audio caching (0 ms latency on repeated preview).
 * - Audio focus management with AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK to avoid interrupting ReadingService.
 * - Clean cooperative cancellation and resource release on dialog dismissal.
 */
class AudioPreviewEngine(
    private val context: Context? = null,
    val cache: AudioPreviewCache = AudioPreviewCache(),
    internal val audioPlayer: IAudioPreviewPlayer = AndroidAudioPreviewPlayer(),
    private val vieneuEngineProvider: (() -> VieNeuOnnxEngine?)? = null,
    private val googleTtsWrapper: ITtsEngineWrapper? = null,
    private val googleTtsWrapperProvider: (() -> ITtsEngineWrapper?)? = null,
    internal var customSynthesizer: (suspend (VoiceInfo, String) -> ShortArray)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    var onStateChange: (AudioPreviewState) -> Unit = {}
) {
    companion object {
        private const val TAG = "AudioPreviewEngine"

        fun generateGreetingText(voice: VoiceInfo): String {
            val name = voice.displayName.ifBlank { voice.id }
            return "Xin chào, tôi là $name, rất vui được đọc truyện cùng bạn."
        }

        fun generateGreetingText(voiceName: String): String {
            return "Xin chào, tôi là $voiceName, rất vui được đọc truyện cùng bạn."
        }
    }

    private val scope = CoroutineScope(dispatcher + SupervisorJob())
    private val stateLock = Any()

    private var currentJob: Job? = null
    private var currentActiveVoiceId: String? = null

    val activeGoogleTtsWrapper: ITtsEngineWrapper?
        get() = googleTtsWrapper ?: googleTtsWrapperProvider?.invoke()

    @Volatile
    private var isGoogleTtsActive = false

    private val audioManager = try {
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    } catch (e: Throwable) {
        null
    }
    private var focusRequest: AudioFocusRequestCompat? = null

    @Volatile
    var currentState: AudioPreviewState = AudioPreviewState.Idle
        private set

    val isPlaying: Boolean
        get() = currentState is AudioPreviewState.Playing

    val isLoading: Boolean
        get() = currentState is AudioPreviewState.Loading

    /**
     * Start voice audition for the provided [voiceInfo].
     * Immediately cancels and cleans up any currently active preview playback.
     */
    fun play(voiceInfo: VoiceInfo, engineType: ITTSManager.EngineType = ITTSManager.EngineType.VIENEU) {
        synchronized(stateLock) {
            stopInternal(silent = true)

            val voiceId = voiceInfo.id
            currentActiveVoiceId = voiceId
            requestAudioFocus()

            val activeWrapper = activeGoogleTtsWrapper
            if (engineType == ITTSManager.EngineType.GOOGLE_TTS && activeWrapper != null) {
                playGoogleTts(voiceInfo, activeWrapper)
                return
            }

            // VieNeu / PCM synthesis path
            val cachedPcm = cache.get(voiceId)
            if (cachedPcm != null) {
                setState(AudioPreviewState.Playing(voiceId))
                audioPlayer.play(cachedPcm) {
                    onPlaybackComplete(voiceId)
                }
                return
            }

            setState(AudioPreviewState.Loading(voiceId))
            currentJob = scope.launch(dispatcher) {
                try {
                    val greeting = generateGreetingText(voiceInfo)
                    val pcm: ShortArray = if (customSynthesizer != null) {
                        customSynthesizer!!.invoke(voiceInfo, greeting)
                    } else {
                        synthesizeVieNeu(voiceInfo, greeting)
                    }

                    if (!isActive) return@launch

                    cache.put(voiceId, pcm)

                    synchronized(stateLock) {
                        if (!isActive || currentActiveVoiceId != voiceId) return@synchronized
                        setState(AudioPreviewState.Playing(voiceId))
                        audioPlayer.play(pcm) {
                            onPlaybackComplete(voiceId)
                        }
                    }
                } catch (e: CancellationException) {
                    // Cooperative coroutine cancellation on stop or new voice selection
                } catch (e: Throwable) {
                    Log.e(TAG, "Audio preview synthesis failed for $voiceId", e)
                    synchronized(stateLock) {
                        if (currentActiveVoiceId == voiceId) {
                            abandonAudioFocus()
                            setState(AudioPreviewState.Error(voiceId, e.message ?: "Synthesis failed"))
                        }
                    }
                }
            }
        }
    }

    fun play(voiceName: String, engineType: ITTSManager.EngineType = ITTSManager.EngineType.VIENEU) {
        val voice = VoicePresets.getVoice(voiceName)?.toVoiceInfo()
            ?: VoiceInfo(id = voiceName, displayName = voiceName)
        play(voice, engineType)
    }

    /**
     * Toggles preview for the given [voiceInfo].
     * If already loading or playing this voice, it stops playback.
     * Otherwise, it starts previewing it.
     */
    fun toggle(voiceInfo: VoiceInfo, engineType: ITTSManager.EngineType = ITTSManager.EngineType.VIENEU) {
        synchronized(stateLock) {
            val activeVoiceId = when (val s = currentState) {
                is AudioPreviewState.Loading -> s.voiceId
                is AudioPreviewState.Playing -> s.voiceId
                else -> null
            }

            if (activeVoiceId == voiceInfo.id) {
                stop()
            } else {
                play(voiceInfo, engineType)
            }
        }
    }

    fun toggle(voiceName: String, engineType: ITTSManager.EngineType = ITTSManager.EngineType.VIENEU) {
        val voice = VoicePresets.getVoice(voiceName)?.toVoiceInfo()
            ?: VoiceInfo(id = voiceName, displayName = voiceName)
        toggle(voice, engineType)
    }

    /**
     * Stops active preview synthesis or playback immediately and transitions state to Stopped.
     */
    fun stop() {
        synchronized(stateLock) {
            stopInternal(silent = false)
        }
    }

    /**
     * Releases all preview resources, cancelling coroutines, stopping audio tracks,
     * abandoning audio focus, and setting state to Idle.
     */
    fun release() {
        synchronized(stateLock) {
            stopInternal(silent = true)
            audioPlayer.release()
            scope.cancel()
            setState(AudioPreviewState.Idle)
        }
    }

    fun clearCache() {
        cache.clear()
    }

    private fun stopInternal(silent: Boolean) {
        currentJob?.cancel()
        currentJob = null

        if (audioPlayer.isPlaying || !silent) {
            audioPlayer.stop()
        }
        if (isGoogleTtsActive) {
            isGoogleTtsActive = false
            try {
                activeGoogleTtsWrapper?.stop()
            } catch (e: Throwable) {}
        }

        abandonAudioFocus()

        val lastVoiceId = currentActiveVoiceId
        currentActiveVoiceId = null

        if (!silent) {
            setState(AudioPreviewState.Stopped(lastVoiceId))
        }
    }

    private fun playGoogleTts(voiceInfo: VoiceInfo, activeWrapper: ITtsEngineWrapper? = activeGoogleTtsWrapper) {
        val wrapper = activeWrapper ?: run {
            setState(AudioPreviewState.Error(voiceInfo.id, "Google TTS engine not available"))
            return
        }

        val voiceId = voiceInfo.id
        setState(AudioPreviewState.Loading(voiceId))
        isGoogleTtsActive = true

        wrapper.setVoiceByName(voiceId)
        val greetingText = generateGreetingText(voiceInfo)
        val utteranceId = "preview_$voiceId"

        wrapper.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                if (id == utteranceId) {
                    synchronized(stateLock) {
                        if (currentActiveVoiceId == voiceId) {
                            setState(AudioPreviewState.Playing(voiceId))
                        }
                    }
                }
            }

            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    isGoogleTtsActive = false
                    onPlaybackComplete(voiceId)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    isGoogleTtsActive = false
                    synchronized(stateLock) {
                        if (currentActiveVoiceId == voiceId) {
                            abandonAudioFocus()
                            setState(AudioPreviewState.Error(voiceId, "Google TTS utterance error"))
                        }
                    }
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    isGoogleTtsActive = false
                    synchronized(stateLock) {
                        if (currentActiveVoiceId == voiceId) {
                            abandonAudioFocus()
                            setState(AudioPreviewState.Error(voiceId, "Google TTS error code $errorCode"))
                        }
                    }
                }
            }
        })

        val result = wrapper.speak(greetingText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            abandonAudioFocus()
            setState(AudioPreviewState.Error(voiceId, "Failed to speak Google TTS utterance"))
        }
    }

    private suspend fun synthesizeVieNeu(voiceInfo: VoiceInfo, text: String): ShortArray {
        val engine = vieneuEngineProvider?.invoke()
            ?: throw IllegalStateException("VieNeu engine is not initialized")

        val phonemes = VieNeuTTSManager.phonemizeWithEmotionBypass(text)
        val profile = VieNeuTTSManager.getTuningProfile(voiceInfo.id)
        val inferResult = engine.inferWithTiming(
            phonemes = phonemes,
            voiceName = voiceInfo.id,
            temperature = profile.temperature
        )
        val rawPcm16 = PcmUtils.floatToShort(inferResult.pcm)
        return PcmUtils.applyMicroFade(rawPcm16)
    }

    private fun onPlaybackComplete(voiceId: String) {
        synchronized(stateLock) {
            if (currentActiveVoiceId == voiceId) {
                abandonAudioFocus()
                currentActiveVoiceId = null
                setState(AudioPreviewState.Stopped(voiceId))
            }
        }
    }

    private fun setState(newState: AudioPreviewState) {
        currentState = newState
        try {
            onStateChange(newState)
        } catch (e: Throwable) {
            Log.w(TAG, "Error in onStateChange callback: ${e.message}")
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        try {
            val attrBuilder = AudioAttributesCompat.Builder()
                .setUsage(AudioAttributesCompat.USAGE_MEDIA)
                .setContentType(AudioAttributesCompat.CONTENT_TYPE_SPEECH)

            val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        stop()
                    }
                    else -> {}
                }
            }

            val reqBuilder = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attrBuilder.build())
                .setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(false)

            val request = reqBuilder.build()
            focusRequest = request
            return AudioManagerCompat.requestAudioFocus(am, request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Throwable) {
            return false
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        val req = focusRequest ?: return
        try {
            AudioManagerCompat.abandonAudioFocusRequest(am, req)
        } catch (e: Throwable) {
        } finally {
            focusRequest = null
        }
    }
}
