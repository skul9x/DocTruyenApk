package com.skul9x.doctruyen

import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.tts.GoogleTTSManager
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.tts.ITtsEngineWrapper
import com.skul9x.doctruyen.tts.VoiceInfo
import com.skul9x.doctruyen.utils.UserConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

class SettingsLazyGoogleTtsTest {

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

    private class FakeTtsEngineWrapper : ITtsEngineWrapper {
        var isShutdown = false
        override fun setSpeechRate(rate: Float): Int = 0
        override fun setPitch(pitch: Float): Int = 0
        override fun setLanguage(locale: java.util.Locale): Int = 0
        override fun setVoice(voice: android.speech.tts.Voice): Int = 0
        override fun setVoiceByName(voiceName: String): Int = 0
        override fun getVoices(): Set<android.speech.tts.Voice>? = null
        override fun getVoiceInfoList(): List<VoiceInfo> = listOf(
            VoiceInfo("vi-vn-x-vic-network", "Google Nữ (Online)", "Google TTS", "Nữ", "Việt Nam", true)
        )
        override fun speak(text: String, queueMode: Int, params: android.os.Bundle?, utteranceId: String): Int = 0
        override fun speakLegacy(text: String, queueMode: Int, params: HashMap<String, String>): Int = 0
        override fun playSilentUtterance(durationMs: Long, queueMode: Int, utteranceId: String): Int = 0
        override fun setOnUtteranceProgressListener(listener: android.speech.tts.UtteranceProgressListener) {}
        override fun stop(): Int = 0
        override fun shutdown() {
            isShutdown = true
        }
    }

    private fun createDummyContext(): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
    }

    @Test
    fun testVieNeuEngineKeepsGoogleTtsNullAndUninitialized() {
        val mockContext = createDummyContext()
        UserConfig.setTtsEngine(mockContext, ITTSManager.EngineType.VIENEU)

        var factoryCallCount = 0
        var voiceDisplayRefreshed = false
        val fakeEngine = FakeTtsEngineWrapper()

        val holder = SettingsTtsHolder(
            contextProvider = { mockContext },
            onVoiceDisplayRefresh = { voiceDisplayRefreshed = true },
            googleTtsFactory = { ctx, callback ->
                factoryCallCount++
                GoogleTTSManager(ctx, onStateChange = callback, customEngineWrapper = fakeEngine)
            }
        )

        // Verify initial state: no instance created
        assertNull("tempGoogleTts must be null on creation", holder.tempGoogleTts)
        assertEquals("Factory must not be called initially", 0, factoryCallCount)
        assertFalse("Voice display refresh should not be triggered", voiceDisplayRefreshed)

        // When engine remains VIENEU, onEngineChanged should not instantiate Google TTS
        holder.onEngineChanged(ITTSManager.EngineType.VIENEU)
        assertNull("tempGoogleTts must remain null for VIENEU engine", holder.tempGoogleTts)
        assertEquals("Factory must still not be called", 0, factoryCallCount)
    }

    @Test
    fun testGoogleTtsEngineLazilyInstantiatesOnDemand() {
        val mockContext = createDummyContext()
        UserConfig.setTtsEngine(mockContext, ITTSManager.EngineType.GOOGLE_TTS)

        var factoryCallCount = 0
        var voiceDisplayRefreshed = false
        val fakeEngine = FakeTtsEngineWrapper()

        val holder = SettingsTtsHolder(
            contextProvider = { mockContext },
            onVoiceDisplayRefresh = { voiceDisplayRefreshed = true },
            googleTtsFactory = { ctx, callback ->
                factoryCallCount++
                GoogleTTSManager(ctx, onStateChange = callback, customEngineWrapper = fakeEngine)
            }
        )

        // Initially null before query
        assertNull(holder.tempGoogleTts)
        assertEquals(0, factoryCallCount)

        // Explicit getOrCreateGoogleTts invocation (such as dialog opening or engine active voice query)
        val ttsInstance1 = holder.getOrCreateGoogleTts()
        assertNotNull(ttsInstance1)
        assertEquals(1, factoryCallCount)
        assertSame("Subsequent reads of tempGoogleTts must return cached instance", ttsInstance1, holder.tempGoogleTts)

        // Calling again must reuse cached singleton instance
        val ttsInstance2 = holder.getOrCreateGoogleTts()
        assertSame("Must return same singleton instance without re-instantiation", ttsInstance1, ttsInstance2)
        assertEquals("Factory should only be invoked once", 1, factoryCallCount)

        // Verify state change callback triggered voice display refresh
        assertTrue("Voice display refresh callback should be notified on TTS init/state change", voiceDisplayRefreshed)
    }

    @Test
    fun testEngineSwitchFromVieNeuToGoogleTriggersLazyInit() {
        val mockContext = createDummyContext()
        UserConfig.setTtsEngine(mockContext, ITTSManager.EngineType.VIENEU)

        var factoryCallCount = 0
        val fakeEngine = FakeTtsEngineWrapper()

        val holder = SettingsTtsHolder(
            contextProvider = { mockContext },
            onVoiceDisplayRefresh = {},
            googleTtsFactory = { ctx, callback ->
                factoryCallCount++
                GoogleTTSManager(ctx, onStateChange = callback, customEngineWrapper = fakeEngine)
            }
        )

        assertNull(holder.tempGoogleTts)
        assertEquals(0, factoryCallCount)

        // User switches engine toggle to GOOGLE_TTS
        holder.onEngineChanged(ITTSManager.EngineType.GOOGLE_TTS)

        assertNotNull("Switching to GOOGLE_TTS must lazily initialize GoogleTTSManager", holder.tempGoogleTts)
        assertEquals(1, factoryCallCount)
    }

    @Test
    fun testShutdownCleansUpIfInitialized() {
        val mockContext = createDummyContext()
        val fakeEngine = FakeTtsEngineWrapper()

        val holder = SettingsTtsHolder(
            contextProvider = { mockContext },
            onVoiceDisplayRefresh = {},
            googleTtsFactory = { ctx, callback ->
                GoogleTTSManager(ctx, onStateChange = callback, customEngineWrapper = fakeEngine)
            }
        )

        // If never initialized, onDestroy should safely do nothing
        holder.onDestroy()
        assertNull(holder.tempGoogleTts)
        assertFalse(fakeEngine.isShutdown)

        // Initialize instance
        val tts = holder.getOrCreateGoogleTts()
        assertNotNull(tts)
        assertFalse(fakeEngine.isShutdown)

        // Calling onDestroy cleans up and shuts down engine wrapper
        holder.onDestroy()
        assertNull("tempGoogleTts must be set to null after onDestroy", holder.tempGoogleTts)
        assertTrue("Engine wrapper shutdown must be called", fakeEngine.isShutdown)
    }
}
