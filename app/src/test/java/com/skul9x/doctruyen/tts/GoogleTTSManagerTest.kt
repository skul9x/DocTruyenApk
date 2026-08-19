package com.skul9x.doctruyen.tts

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.skul9x.doctruyen.utils.UserConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.Locale

class GoogleTTSManagerTest {

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

    private fun createMockContext(): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context {
                return this
            }
        }
    }

    private class FakeTtsEngine(private val availableVoices: List<VoiceInfo> = emptyList()) : ITtsEngineWrapper {
        var selectedVoiceName: String? = null
        var speechRate: Float = 1.0f
        var pitch: Float = 1.0f
        var lastSpokenText: String? = null
        var isStopped: Boolean = false
        var isShutdown: Boolean = false
        var listener: UtteranceProgressListener? = null

        override fun setSpeechRate(rate: Float): Int {
            speechRate = rate
            return TextToSpeech.SUCCESS
        }

        override fun setPitch(pitch: Float): Int {
            this.pitch = pitch
            return TextToSpeech.SUCCESS
        }

        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

        override fun setVoice(voice: android.speech.tts.Voice): Int = TextToSpeech.SUCCESS

        override fun setVoiceByName(voiceName: String): Int {
            selectedVoiceName = voiceName
            return TextToSpeech.SUCCESS
        }

        override fun getVoices(): Set<android.speech.tts.Voice>? = null

        override fun getVoiceInfoList(): List<VoiceInfo>? = availableVoices

        override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int {
            lastSpokenText = text
            return TextToSpeech.SUCCESS
        }

        override fun speakLegacy(text: String, queueMode: Int, params: HashMap<String, String>): Int {
            lastSpokenText = text
            return TextToSpeech.SUCCESS
        }

        override fun playSilentUtterance(durationMs: Long, queueMode: Int, utteranceId: String): Int = TextToSpeech.SUCCESS

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
            this.listener = listener
        }

        override fun stop(): Int {
            isStopped = true
            return TextToSpeech.SUCCESS
        }

        override fun shutdown() {
            isShutdown = true
        }
    }

    @Test
    fun testGoogleTTSStateTransitionsAndConfig() {
        val states = mutableListOf<ITTSManager.TTSState>()
        val progressValues = mutableListOf<Int>()
        val mockContext = createMockContext()
        val fakeTts = FakeTtsEngine()

        val manager = GoogleTTSManager(
            context = mockContext,
            onStateChange = { states.add(it) },
            onProgressChange = { progressValues.add(it) },
            customEngineWrapper = fakeTts
        )

        assertEquals(ITTSManager.EngineType.GOOGLE_TTS, manager.engineType)
        assertTrue("Should transition through INITIALIZING and READY", states.contains(ITTSManager.TTSState.INITIALIZING))
        assertTrue("Should transition through READY", states.contains(ITTSManager.TTSState.READY))
        assertEquals(ITTSManager.TTSState.READY, manager.currentState)

        // Test speak
        manager.speak("Xin chào mọi người")
        assertEquals(ITTSManager.TTSState.PLAYING, manager.currentState)
        assertTrue(manager.isPlaying())
        assertFalse(manager.isPaused())
        assertEquals("Xin chào mọi người", fakeTts.lastSpokenText)

        // Test pause
        manager.pause()
        assertEquals(ITTSManager.TTSState.PAUSED, manager.currentState)
        assertTrue(manager.isPaused())
        assertFalse(manager.isPlaying())
        assertTrue(fakeTts.isStopped)

        // Test resume
        manager.resume()
        assertEquals(ITTSManager.TTSState.PLAYING, manager.currentState)
        assertTrue(manager.isPlaying())

        // Test stop
        manager.stop()
        assertEquals(ITTSManager.TTSState.READY, manager.currentState)
        assertFalse(manager.isPlaying())
        assertFalse(manager.isPaused())
    }

    @Test
    fun testVietnameseVoiceFiltering() {
        val mockContext = createMockContext()
        val voiceVi1 = VoiceInfo(
            id = "vi-vn-x-vic-network",
            displayName = "Google Nữ (Chất lượng cao (Online))",
            description = "Google TTS · Nữ · Chất lượng cao (Online)",
            gender = "Nữ",
            region = "Việt Nam",
            isDefault = true
        )
        val voiceVi2 = VoiceInfo(
            id = "vi-vn-x-vif-local",
            displayName = "Google Nam (Offline)",
            description = "Google TTS · Nam · Offline",
            gender = "Nam",
            region = "Việt Nam",
            isDefault = false
        )

        val fakeTts = FakeTtsEngine(listOf(voiceVi1, voiceVi2))

        val manager = GoogleTTSManager(
            context = mockContext,
            onStateChange = {},
            customEngineWrapper = fakeTts
        )

        val voices = manager.getAvailableVoices()
        assertEquals(2, voices.size)
        assertTrue(voices.any { it.id == "vi-vn-x-vic-network" && it.gender == "Nữ" })
        assertTrue(voices.any { it.id == "vi-vn-x-vif-local" && it.gender == "Nam" })

        manager.setVoice("vi-vn-x-vic-network")
        assertEquals("vi-vn-x-vic-network", manager.currentVoiceName)
        assertEquals("vi-vn-x-vic-network", fakeTts.selectedVoiceName)
    }

    @Test
    fun testSpeakWithTitlePause() {
        val states = mutableListOf<ITTSManager.TTSState>()
        val mockContext = createMockContext()
        val fakeTts = FakeTtsEngine()

        val manager = GoogleTTSManager(
            context = mockContext,
            onStateChange = { states.add(it) },
            customEngineWrapper = fakeTts
        )

        manager.speakWithTitlePause(
            title = "Tiêu đề truyện",
            content = "Nội dung truyện một câu dài."
        )

        assertEquals(ITTSManager.TTSState.PLAYING, manager.currentState)
        assertEquals("Tiêu đề truyện", fakeTts.lastSpokenText)

        manager.shutdown()
        assertEquals(ITTSManager.TTSState.IDLE, manager.currentState)
        assertTrue(fakeTts.isShutdown)
    }
}
