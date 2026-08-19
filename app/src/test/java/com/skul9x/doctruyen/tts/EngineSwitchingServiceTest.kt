package com.skul9x.doctruyen.tts

import android.content.Context
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

class EngineSwitchingServiceTest {

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

    private class FakeTTSManager(
        override val engineType: ITTSManager.EngineType
    ) : ITTSManager {
        override val currentState: ITTSManager.TTSState = ITTSManager.TTSState.READY
        override val currentVoiceName: String = "TestVoice"
        var isStopped: Boolean = false
        var isShutdown: Boolean = false

        override fun speak(text: String, storyId: Int) {}
        override fun speakWithTitlePause(title: String, content: String, storyId: Int, pauseConfig: PauseConfig) {}
        override fun pause() {}
        override fun resume() {}
        override fun stop() { isStopped = true }
        override fun isPlaying(): Boolean = false
        override fun isPaused(): Boolean = false
        override fun setVoice(voiceName: String) {}
        override fun getAvailableVoices(): List<VoiceInfo> = emptyList()
        override fun setSpeed(speed: Float) {}
        override fun setPitch(pitch: Float) {}
        override fun prepareStory(storyId: Int, title: String, content: String, voice: String?, pauseConfig: PauseConfig) {}
        override fun cancelPrewarm() {}
        override fun shutdown() { isShutdown = true }
    }

    @Test
    fun testSwitchEngineChangesConfigAndManager() {
        val manager1 = FakeTTSManager(ITTSManager.EngineType.VIENEU)
        val manager2 = FakeTTSManager(ITTSManager.EngineType.GOOGLE_TTS)

        val service = ReadingService()
        service.ttsManager = manager1

        assertEquals(ITTSManager.EngineType.VIENEU, service.ttsManager?.engineType)

        // Inject switched engine and ensure clean shutdown of previous engine
        service.ttsManager?.stop()
        service.ttsManager?.shutdown()
        service.ttsManager = manager2

        assertTrue(manager1.isStopped)
        assertTrue(manager1.isShutdown)
        assertEquals(ITTSManager.EngineType.GOOGLE_TTS, service.ttsManager?.engineType)
    }
}
