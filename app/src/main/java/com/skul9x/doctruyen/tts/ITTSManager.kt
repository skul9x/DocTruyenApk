package com.skul9x.doctruyen.tts

import com.skul9x.doctruyen.tts.vieneu.PauseConfig

/**
 * Unified interface for Text-to-Speech engines (VieNeu Neural and Google/Android TTS).
 */
interface ITTSManager {
    enum class EngineType {
        VIENEU,      // Offline Neural ONNX
        GOOGLE_TTS   // Android System / Google TTS
    }

    enum class TTSState {
        IDLE,
        INITIALIZING,
        READY,
        PLAYING,
        PAUSED,
        ERROR
    }

    val currentState: TTSState
    val engineType: EngineType
    val currentVoiceName: String

    fun speak(text: String, storyId: Int = 0)
    fun speakWithTitlePause(title: String, content: String, storyId: Int = 0, pauseConfig: PauseConfig = PauseConfig())
    fun pause()
    fun resume()
    fun stop()
    fun isPlaying(): Boolean
    fun isPaused(): Boolean
    fun setVoice(voiceName: String)
    fun getAvailableVoices(): List<VoiceInfo>
    fun setSpeed(speed: Float)
    fun setPitch(pitch: Float)
    fun prepareStory(storyId: Int, title: String, content: String, voice: String? = null, pauseConfig: PauseConfig = PauseConfig())
    fun cancelPrewarm()
    fun shutdown()
}

/**
 * Metadata representation for voices across different engines.
 */
data class VoiceInfo(
    val id: String,
    val displayName: String,
    val description: String = "",
    val gender: String = "",
    val region: String = "",
    val isDefault: Boolean = false
)
