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
import java.util.concurrent.atomic.AtomicInteger

class GoogleTtsReentrancySafetyTest {

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

    private fun createDummyContext(): Context {
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
    }

    private class StubEngineWrapper(
        private val voiceList: List<VoiceInfo>? = null,
        private val throwOnGetVoices: Boolean = false
    ) : ITtsEngineWrapper {
        var isShutdown = false
        override fun setSpeechRate(rate: Float): Int = 0
        override fun setPitch(pitch: Float): Int = 0
        override fun setLanguage(locale: java.util.Locale): Int = 0
        override fun setVoice(voice: android.speech.tts.Voice): Int = 0
        override fun setVoiceByName(voiceName: String): Int = 0
        override fun getVoices(): Set<android.speech.tts.Voice>? {
            if (throwOnGetVoices) {
                throw IllegalStateException("getVoices called while unbound")
            }
            return null
        }
        override fun getVoiceInfoList(): List<VoiceInfo>? = voiceList
        override fun speak(text: String, queueMode: Int, params: android.os.Bundle?, utteranceId: String): Int = 0
        override fun speakLegacy(text: String, queueMode: Int, params: HashMap<String, String>): Int = 0
        override fun playSilentUtterance(durationMs: Long, queueMode: Int, utteranceId: String): Int = 0
        override fun setOnUtteranceProgressListener(listener: android.speech.tts.UtteranceProgressListener) {}
        override fun stop(): Int = 0
        override fun shutdown() {
            isShutdown = true
        }
    }

    @Test
    fun testImmediateSyncCallbackDuringConstructionDoesNotRecurseOrOverflow() {
        val mockContext = createDummyContext()
        val factoryCount = AtomicInteger(0)
        val refreshCount = AtomicInteger(0)

        var holderRef: SettingsTtsHolder? = null

        val holder = SettingsTtsHolder(
            contextProvider = { mockContext },
            onVoiceDisplayRefresh = {
                refreshCount.incrementAndGet()
                // Simulate recursive call from UI update
                holderRef?.getOrCreateGoogleTts()
            },
            googleTtsFactory = { ctx, callback ->
                factoryCount.incrementAndGet()
                // Simulate aggressive immediate synchronous callback firing during factory execution
                callback(ITTSManager.TTSState.INITIALIZING)
                callback(ITTSManager.TTSState.READY)
                GoogleTTSManager(ctx, onStateChange = callback, customEngineWrapper = StubEngineWrapper())
            }
        )
        holderRef = holder

        // Act: should complete synchronously without java.lang.StackOverflowError
        val tts = holder.getOrCreateGoogleTts()

        assertNotNull("TTS instance must be created", tts)
        assertEquals("Factory must be executed exactly once without recursive creation", 1, factoryCount.get())
        assertSame("holder.tempGoogleTts must reference created instance", tts, holder.tempGoogleTts)
    }

    @Test
    fun testInitializingStateDoesNotTriggerVoiceDisplayRefresh() {
        val mockContext = createDummyContext()
        var stateCallbackRef: ((ITTSManager.TTSState) -> Unit)? = null
        var refreshCount = 0

        val holder = SettingsTtsHolder(
            contextProvider = { mockContext },
            onVoiceDisplayRefresh = { refreshCount++ },
            googleTtsFactory = { ctx, callback ->
                stateCallbackRef = callback
                GoogleTTSManager(ctx, onStateChange = callback, customEngineWrapper = StubEngineWrapper())
            }
        )

        val tts = holder.getOrCreateGoogleTts()
        assertNotNull(tts)
        val initialRefreshCount = refreshCount

        // Fire INITIALIZING state
        stateCallbackRef?.invoke(ITTSManager.TTSState.INITIALIZING)
        assertEquals("INITIALIZING state must not trigger onVoiceDisplayRefresh", initialRefreshCount, refreshCount)

        // Fire READY state
        stateCallbackRef?.invoke(ITTSManager.TTSState.READY)
        assertEquals("READY state must trigger onVoiceDisplayRefresh", initialRefreshCount + 1, refreshCount)
    }

    @Test
    fun testGetAvailableVoicesSafeWhenInitializingOrUnbound() {
        val mockContext = createDummyContext()
        val engine = StubEngineWrapper(throwOnGetVoices = true)
        val ttsManager = GoogleTTSManager(mockContext, onStateChange = {}, customEngineWrapper = engine)

        // Query available voices: should return fallback default without throwing exception
        val voices = ttsManager.getAvailableVoices()
        assertNotNull(voices)
        assertTrue("Fallback default voice must be returned when unbound", voices.isNotEmpty())
        assertEquals("vi-vn-default", voices.first().id)
    }

    @Test
    fun testSettingsTtsHolderReentrancyAndLifecycleCleanliness() {
        val mockContext = createDummyContext()
        val stubEngine = StubEngineWrapper()
        var refreshCount = 0

        val holder = SettingsTtsHolder(
            contextProvider = { mockContext },
            onVoiceDisplayRefresh = {
                refreshCount++
            },
            googleTtsFactory = { ctx, callback ->
                GoogleTTSManager(ctx, onStateChange = callback, customEngineWrapper = stubEngine)
            }
        )

        assertNull("Initially uninstantiated", holder.tempGoogleTts)
        holder.onEngineChanged(ITTSManager.EngineType.GOOGLE_TTS)
        assertNotNull("Instantiated on engine change", holder.tempGoogleTts)

        // Repeated queries should return same instance
        val tts1 = holder.getOrCreateGoogleTts()
        val tts2 = holder.getOrCreateGoogleTts()
        assertSame("Must return same singleton", tts1, tts2)

        // Clean teardown
        holder.onDestroy()
        assertNull("Must clear instance on destroy", holder.tempGoogleTts)
        assertTrue("Engine must be shut down", stubEngine.isShutdown)
    }
}
