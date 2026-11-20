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
    fun speakWithTitlePause(title: String, content: String, storyId: Int = 0, pauseConfig: PauseConfig = PauseConfig.UNSPECIFIED)
    fun pause()
    fun resume()
    fun stop()
    fun isPlaying(): Boolean
    fun isPaused(): Boolean
    fun setVoice(voiceName: String)
    fun getAvailableVoices(): List<VoiceInfo>
    fun setSpeed(speed: Float)
    fun setPitch(pitch: Float)
    fun prepareStory(storyId: Int, title: String, content: String, voice: String? = null, pauseConfig: PauseConfig = PauseConfig.UNSPECIFIED)
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
    val isDefault: Boolean = false,
    val style: String = ""
) {
    val canonicalGender: VoiceGender
        get() {
            val g = VoiceGender.from(gender)
            if (g != VoiceGender.ALL) return g
            return VoiceGender.from(description)
        }

    val canonicalRegion: VoiceRegion
        get() {
            val r = VoiceRegion.from(region)
            if (r != VoiceRegion.ALL) return r
            return VoiceRegion.from(description)
        }

    val canonicalStyle: VoiceStyle
        get() {
            val s = VoiceStyle.from(style)
            if (s != VoiceStyle.ALL) return s
            return VoiceStyle.from(description)
        }

    fun getBadges(bracketed: Boolean = true): List<String> {
        val list = mutableListOf<String>()
        if (canonicalGender != VoiceGender.ALL) {
            list.add(if (bracketed) "[${canonicalGender.displayName}]" else canonicalGender.displayName)
        }
        if (canonicalRegion != VoiceRegion.ALL) {
            list.add(if (bracketed) "[${canonicalRegion.displayName}]" else canonicalRegion.displayName)
        }
        if (canonicalStyle != VoiceStyle.ALL) {
            list.add(if (bracketed) "[${canonicalStyle.displayName}]" else canonicalStyle.displayName)
        }
        return list
    }

    fun getFormattedBadges(bracketed: Boolean = true): String =
        getBadges(bracketed).joinToString(" ")

    fun matches(criteria: VoiceFilterCriteria): Boolean {
        if (criteria.gender != VoiceGender.ALL && canonicalGender != criteria.gender) return false
        if (criteria.region != VoiceRegion.ALL && canonicalRegion != criteria.region) return false
        if (criteria.style != VoiceStyle.ALL && canonicalStyle != criteria.style) return false
        if (criteria.searchQuery.isNotBlank()) {
            val q = criteria.searchQuery.trim().lowercase().removeVietnameseDiacritics()
            val nameNorm = displayName.lowercase().removeVietnameseDiacritics()
            val idNorm = id.lowercase().removeVietnameseDiacritics()
            val descNorm = description.lowercase().removeVietnameseDiacritics()
            val gNorm = canonicalGender.displayName.lowercase().removeVietnameseDiacritics()
            val rNorm = canonicalRegion.displayName.lowercase().removeVietnameseDiacritics()
            val sNorm = canonicalStyle.displayName.lowercase().removeVietnameseDiacritics()
            val matchesQuery = nameNorm.contains(q) || idNorm.contains(q) || descNorm.contains(q) ||
                    gNorm.contains(q) || rNorm.contains(q) || sNorm.contains(q)
            if (!matchesQuery) return false
        }
        return true
    }
}
