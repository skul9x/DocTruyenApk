package com.skul9x.doctruyen.utils

import android.content.Context
import android.content.SharedPreferences

object UserConfig {
    private const val PREF_NAME = "doc_truyen_config"
    private const val KEY_BASE_URL = "base_url"
    
    // Default URL if not set
    private const val DEFAULT_URL = "https://skul9x.free.nf/truyen/"

    internal var customPrefsProvider: ((Context) -> SharedPreferences)? = null

    private fun getPrefs(context: Context): SharedPreferences {
        return customPrefsProvider?.invoke(context) ?: context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private const val KEY_SORT_ORDER = "sort_order"
    private const val KEY_TTS_PITCH = "tts_pitch"
    private const val KEY_TTS_SPEED = "tts_speed"

    fun getBaseUrl(context: Context): String {
        return getPrefs(context).getString(KEY_BASE_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    fun setBaseUrl(context: Context, url: String) {
        var cleanUrl = url.trim()
        if (!cleanUrl.endsWith("/")) {
            cleanUrl += "/"
        }
        if (!cleanUrl.startsWith("http")) {
            cleanUrl = "https://$cleanUrl"
        }
        
        getPrefs(context).edit().putString(KEY_BASE_URL, cleanUrl).apply()
    }
    
    fun getSortOrder(context: Context): String {
        return getPrefs(context).getString(KEY_SORT_ORDER, "newest") ?: "newest"
    }

    fun setSortOrder(context: Context, sort: String) {
        getPrefs(context).edit().putString(KEY_SORT_ORDER, sort).apply()
    }
    
    fun getTtsPitch(context: Context): Float {
        return getPrefs(context).getFloat(KEY_TTS_PITCH, 1.0f)
    }
    
    fun setTtsPitch(context: Context, pitch: Float) {
        getPrefs(context).edit().putFloat(KEY_TTS_PITCH, pitch).apply()
    }
    
    fun getTtsSpeed(context: Context): Float {
        return getPrefs(context).getFloat(KEY_TTS_SPEED, 1.0f)
    }
    
    fun setTtsSpeed(context: Context, speed: Float) {
        getPrefs(context).edit().putFloat(KEY_TTS_SPEED, speed).apply()
    }
    
    // Cookie persistence
    private const val KEY_COOKIE = "auth_cookie"
    
    fun getCookie(context: Context): String {
        return getPrefs(context).getString(KEY_COOKIE, "") ?: ""
    }
    
    fun setCookie(context: Context, cookie: String) {
        getPrefs(context).edit().putString(KEY_COOKIE, cookie).apply()
    }
    
    // TTS Engine preference
    const val KEY_TTS_ENGINE = "tts_engine"
    const val KEY_GOOGLE_TTS_VOICE = "google_tts_voice"
    const val KEY_TTS_PRECISION = "tts_precision"
    const val DEFAULT_TTS_PRECISION = "INT8"

    fun getTtsPrecision(context: Context): String {
        val raw = getPrefs(context).getString(KEY_TTS_PRECISION, DEFAULT_TTS_PRECISION)
        return when (raw?.trim()?.uppercase()) {
            "INT8" -> "INT8"
            "FP32" -> "FP32"
            else -> "FP32"
        }
    }

    fun setTtsPrecision(context: Context, precision: String) {
        val normalized = when (precision.trim().uppercase()) {
            "INT8" -> "INT8"
            "FP32" -> "FP32"
            else -> "FP32"
        }
        getPrefs(context).edit().putString(KEY_TTS_PRECISION, normalized).apply()
    }

    fun getTtsEngine(context: Context): com.skul9x.doctruyen.tts.ITTSManager.EngineType {
        val str = getPrefs(context).getString(KEY_TTS_ENGINE, com.skul9x.doctruyen.tts.ITTSManager.EngineType.VIENEU.name)
        return try {
            com.skul9x.doctruyen.tts.ITTSManager.EngineType.valueOf(str ?: com.skul9x.doctruyen.tts.ITTSManager.EngineType.VIENEU.name)
        } catch (e: Throwable) {
            com.skul9x.doctruyen.tts.ITTSManager.EngineType.VIENEU
        }
    }

    fun setTtsEngine(context: Context, engineType: com.skul9x.doctruyen.tts.ITTSManager.EngineType) {
        getPrefs(context).edit().putString(KEY_TTS_ENGINE, engineType.name).apply()
    }

    fun getGoogleTtsVoice(context: Context): String? {
        return getPrefs(context).getString(KEY_GOOGLE_TTS_VOICE, null)
    }

    fun setGoogleTtsVoice(context: Context, voiceId: String) {
        getPrefs(context).edit().putString(KEY_GOOGLE_TTS_VOICE, voiceId).apply()
    }

    // Voice preference
    private const val KEY_TTS_VOICE = "tts_voice"
    
    fun getTtsVoice(context: Context): String? {
        return getPrefs(context).getString(KEY_TTS_VOICE, null)
    }
    
    fun setTtsVoice(context: Context, voiceName: String) {
        getPrefs(context).edit().putString(KEY_TTS_VOICE, voiceName).apply()
    }

    // Pre-buffering chunk configuration
    const val KEY_TTS_PREBUFFER_CHUNKS = "tts_prebuffer_chunks"
    const val DEFAULT_PREBUFFER_CHUNKS = 2
    const val MIN_PREBUFFER_CHUNKS = 1
    const val MAX_PREBUFFER_CHUNKS = 3

    fun getPrebufferChunks(context: Context): Int {
        val value = getPrefs(context).getInt(KEY_TTS_PREBUFFER_CHUNKS, DEFAULT_PREBUFFER_CHUNKS)
        return value.coerceIn(MIN_PREBUFFER_CHUNKS, MAX_PREBUFFER_CHUNKS)
    }

    fun setPrebufferChunks(context: Context, chunks: Int) {
        val clamped = chunks.coerceIn(MIN_PREBUFFER_CHUNKS, MAX_PREBUFFER_CHUNKS)
        getPrefs(context).edit().putInt(KEY_TTS_PREBUFFER_CHUNKS, clamped).apply()
    }

    // Pause configuration
    const val KEY_TTS_PAUSE_SENTENCE = "tts_pause_sentence"
    const val KEY_TTS_PAUSE_PARAGRAPH = "tts_pause_paragraph"
    const val KEY_TTS_PAUSE_CLAUSE = "tts_pause_clause"
    const val KEY_TTS_DEBUG_LOGGING = "tts_debug_logging"

    const val DEFAULT_PAUSE_SENTENCE = 400
    const val MIN_PAUSE_SENTENCE = 0
    const val MAX_PAUSE_SENTENCE = 1000

    const val DEFAULT_PAUSE_PARAGRAPH = 650
    const val MIN_PAUSE_PARAGRAPH = 0
    const val MAX_PAUSE_PARAGRAPH = 1500

    const val DEFAULT_PAUSE_CLAUSE = 180
    const val MIN_PAUSE_CLAUSE = 0
    const val MAX_PAUSE_CLAUSE = 500

    const val DEFAULT_DEBUG_LOGGING = true

    fun getPauseSentence(context: Context): Int {
        val value = getPrefs(context).getInt(KEY_TTS_PAUSE_SENTENCE, DEFAULT_PAUSE_SENTENCE)
        return value.coerceIn(MIN_PAUSE_SENTENCE, MAX_PAUSE_SENTENCE)
    }

    fun setPauseSentence(context: Context, pauseMs: Int) {
        val clamped = pauseMs.coerceIn(MIN_PAUSE_SENTENCE, MAX_PAUSE_SENTENCE)
        getPrefs(context).edit().putInt(KEY_TTS_PAUSE_SENTENCE, clamped).apply()
    }

    fun getPauseParagraph(context: Context): Int {
        val value = getPrefs(context).getInt(KEY_TTS_PAUSE_PARAGRAPH, DEFAULT_PAUSE_PARAGRAPH)
        return value.coerceIn(MIN_PAUSE_PARAGRAPH, MAX_PAUSE_PARAGRAPH)
    }

    fun setPauseParagraph(context: Context, pauseMs: Int) {
        val clamped = pauseMs.coerceIn(MIN_PAUSE_PARAGRAPH, MAX_PAUSE_PARAGRAPH)
        getPrefs(context).edit().putInt(KEY_TTS_PAUSE_PARAGRAPH, clamped).apply()
    }

    fun getPauseClause(context: Context): Int {
        val value = getPrefs(context).getInt(KEY_TTS_PAUSE_CLAUSE, DEFAULT_PAUSE_CLAUSE)
        return value.coerceIn(MIN_PAUSE_CLAUSE, MAX_PAUSE_CLAUSE)
    }

    fun setPauseClause(context: Context, pauseMs: Int) {
        val clamped = pauseMs.coerceIn(MIN_PAUSE_CLAUSE, MAX_PAUSE_CLAUSE)
        getPrefs(context).edit().putInt(KEY_TTS_PAUSE_CLAUSE, clamped).apply()
    }

    fun isDebugLoggingEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_TTS_DEBUG_LOGGING, DEFAULT_DEBUG_LOGGING)
    }

    fun setDebugLoggingEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_TTS_DEBUG_LOGGING, enabled).apply()
    }

    /**
     * Preset configurations for pause cadence.
     */
    fun applyPausePreset(context: Context, presetName: String) {
        when (presetName) {
            "FAST" -> { // Đọc nhanh / Ngắt ngắn
                setPauseSentence(context, 250)
                setPauseParagraph(context, 400)
                setPauseClause(context, 100)
            }
            "EMOTIONAL" -> { // Truyện cảm xúc / Ngắt dài
                setPauseSentence(context, 600)
                setPauseParagraph(context, 900)
                setPauseClause(context, 250)
            }
            "SEAMLESS", "ZERO_GAP" -> { // Liền mạch / Zero-Gap
                setPauseSentence(context, 0)
                setPauseParagraph(context, 0)
                setPauseClause(context, 0)
            }
            else -> { // STANDARD / Tự nhiên / Chuẩn
                setPauseSentence(context, DEFAULT_PAUSE_SENTENCE)
                setPauseParagraph(context, DEFAULT_PAUSE_PARAGRAPH)
                setPauseClause(context, DEFAULT_PAUSE_CLAUSE)
            }
        }
    }
}
