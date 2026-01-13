package com.skul9x.doctruyen.utils

import android.content.Context
import android.content.SharedPreferences

object UserConfig {
    private const val PREF_NAME = "doc_truyen_config"
    private const val KEY_BASE_URL = "base_url"
    
    // Default URL if not set
    private const val DEFAULT_URL = "https://skul9x.free.nf/truyen/"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
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
    private const val KEY_COOKIE_TIMESTAMP = "cookie_timestamp"
    private const val COOKIE_MAX_AGE_MS = 6 * 60 * 60 * 1000L // 6 hours
    private const val COOKIE_SAFE_MARGIN_MS = 30 * 60 * 1000L // 30 min buffer before expiry
    
    fun getCookie(context: Context): String {
        return getPrefs(context).getString(KEY_COOKIE, "") ?: ""
    }
    
    fun setCookie(context: Context, cookie: String) {
        getPrefs(context).edit()
            .putString(KEY_COOKIE, cookie)
            .apply() // No timestamp needed anymore
    }
    
    // Logic check expired removed - using Reactive check from API response instead
    
    fun clearCookie(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_COOKIE)
            .apply()
    }
    
    // Voice preference
    private const val KEY_TTS_VOICE = "tts_voice"
    
    fun getTtsVoice(context: Context): String? {
        return getPrefs(context).getString(KEY_TTS_VOICE, null)
    }
    
    fun setTtsVoice(context: Context, voiceName: String) {
        getPrefs(context).edit().putString(KEY_TTS_VOICE, voiceName).apply()
    }
}
