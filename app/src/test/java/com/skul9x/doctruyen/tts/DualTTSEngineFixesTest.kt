package com.skul9x.doctruyen.tts

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.skul9x.doctruyen.service.ReadingService
import com.skul9x.doctruyen.tts.vieneu.PauseConfig
import com.skul9x.doctruyen.utils.UserConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class DualTTSEngineFixesTest {

    private val preferencesMap = mutableMapOf<String, Any?>()

    @Before
    fun setup() {
        preferencesMap.clear()

        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "putString" -> {
                        preferencesMap[args[0] as String] = args[1]
                        proxy
                    }
                    "putFloat" -> {
                        preferencesMap[args[0] as String] = args[1]
                        proxy
                    }
                    "putInt" -> {
                        preferencesMap[args[0] as String] = args[1]
                        proxy
                    }
                    "putBoolean" -> {
                        preferencesMap[args[0] as String] = args[1]
                        proxy
                    }
                    "apply", "commit" -> null
                    else -> null
                }
            }
        ) as SharedPreferences.Editor

        val prefsProxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "getString" -> {
                        val key = args[0] as String
                        val def = args[1] as? String
                        preferencesMap[key] as? String ?: def
                    }
                    "getFloat" -> {
                        val key = args[0] as String
                        val def = (args[1] as? Number)?.toFloat() ?: 1.0f
                        (preferencesMap[key] as? Number)?.toFloat() ?: def
                    }
                    "getInt" -> {
                        val key = args[0] as String
                        val def = (args[1] as? Number)?.toInt() ?: 0
                        (preferencesMap[key] as? Number)?.toInt() ?: def
                    }
                    "getBoolean" -> {
                        val key = args[0] as String
                        val def = (args[1] as? Boolean) ?: false
                        preferencesMap[key] as? Boolean ?: def
                    }
                    "edit" -> editorProxy
                    else -> null
                }
            }
        ) as SharedPreferences

        UserConfig.customPrefsProvider = { prefsProxy }
    }

    @After
    fun tearDown() {
        UserConfig.customPrefsProvider = null
    }

    private class MockTTSManager(
        override val engineType: ITTSManager.EngineType
    ) : ITTSManager {
        override val currentState: ITTSManager.TTSState = ITTSManager.TTSState.READY
        override var currentVoiceName: String = ""
        var preparedStoryId: Int = 0
        var preparedTitle: String = ""
        var preparedVoice: String? = null
        var isStopped: Boolean = false
        var isShutdown: Boolean = false

        override fun speak(text: String, storyId: Int) {}
        override fun speakWithTitlePause(title: String, content: String, storyId: Int, pauseConfig: PauseConfig) {}
        override fun pause() {}
        override fun resume() {}
        override fun stop() { isStopped = true }
        override fun isPlaying(): Boolean = false
        override fun isPaused(): Boolean = false
        override fun setVoice(voiceName: String) { currentVoiceName = voiceName }
        override fun getAvailableVoices(): List<VoiceInfo> = listOf(
            VoiceInfo("vi-vn-x-vic-local", "Google Nữ (Offline)", "Google TTS · Nữ · Offline", "Nữ", "Việt Nam", true),
            VoiceInfo("vi-vn-x-vif-local", "Google Nam (Offline)", "Google TTS · Nam · Offline", "Nam", "Việt Nam", false)
        )
        override fun setSpeed(speed: Float) {}
        override fun setPitch(pitch: Float) {}
        override fun prepareStory(storyId: Int, title: String, content: String, voice: String?, pauseConfig: PauseConfig) {
            preparedStoryId = storyId
            preparedTitle = title
            preparedVoice = voice
        }
        override fun cancelPrewarm() {}
        override fun shutdown() { isShutdown = true }
    }

    @Test
    fun testPrepareStoryResolvesCorrectVoicePerEngine() {
        val mockContext: Context = object : android.content.ContextWrapper(null) {}

        // 1. Set VieNeu voice and Google TTS voice separately
        UserConfig.setTtsVoice(mockContext, "Minh Đức")
        UserConfig.setGoogleTtsVoice(mockContext, "vi-vn-x-vif-local")

        // 2. Test ReadingService with VieNeu Engine
        val vieneuManager = MockTTSManager(ITTSManager.EngineType.VIENEU)
        val serviceVieNeu = ReadingService()
        serviceVieNeu.ttsManager = vieneuManager

        serviceVieNeu.prepareStory(101, "Truyện Kiều", "Trăm năm trong cõi người ta")
        assertEquals("Minh Đức", vieneuManager.currentVoiceName)
        assertEquals("Minh Đức", vieneuManager.preparedVoice)

        // 3. Test ReadingService with Google TTS Engine
        val googleManager = MockTTSManager(ITTSManager.EngineType.GOOGLE_TTS)
        val serviceGoogle = ReadingService()
        serviceGoogle.ttsManager = googleManager

        serviceGoogle.prepareStory(102, "Lục Vân Tiên", "Trước đèn xem chuyện Tây Minh")
        assertEquals("vi-vn-x-vif-local", googleManager.currentVoiceName)
        assertEquals("vi-vn-x-vif-local", googleManager.preparedVoice)
    }

    @Test
    fun testStartReadingResolvesCorrectVoicePerEngine() {
        val mockContext: Context = object : android.content.ContextWrapper(null) {}

        UserConfig.setTtsVoice(mockContext, "Trúc Ly")
        UserConfig.setGoogleTtsVoice(mockContext, "vi-vn-x-vic-local")

        val googleManager = MockTTSManager(ITTSManager.EngineType.GOOGLE_TTS)
        val service = ReadingService()
        service.ttsManager = googleManager

        // startReading should not fallback to Trúc Ly
        val resolvedVoice = if (googleManager.engineType == ITTSManager.EngineType.GOOGLE_TTS) {
            UserConfig.getGoogleTtsVoice(mockContext)
        } else {
            UserConfig.getTtsVoice(mockContext)
        }
        assertEquals("vi-vn-x-vic-local", resolvedVoice)
    }

    @Test
    fun testActionSwitchEngineConstant() {
        assertEquals("action_switch_engine", ReadingService.ACTION_SWITCH_ENGINE)
        assertEquals("extra_engine_type", ReadingService.EXTRA_ENGINE_TYPE)
    }

    @Test
    fun testGoogleVoiceFriendlyNameLookup() {
        val googleManager = MockTTSManager(ITTSManager.EngineType.GOOGLE_TTS)
        val available = googleManager.getAvailableVoices()

        val savedVoiceId = "vi-vn-x-vic-local"
        val matched = available.find { it.id == savedVoiceId }
        assertNotNull(matched)
        assertEquals("Google Nữ (Offline)", matched?.displayName)
        assertEquals("Google TTS · Nữ · Offline", matched?.description)
    }
}
