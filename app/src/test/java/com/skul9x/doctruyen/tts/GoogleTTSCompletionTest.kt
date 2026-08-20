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

class GoogleTTSCompletionTest {

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

    private class TestableTtsEngine : ITtsEngineWrapper {
        var listener: UtteranceProgressListener? = null
        val spokenQueue = mutableListOf<String>()
        val silenceQueue = mutableListOf<String>()
        var stopCount = 0

        override fun setSpeechRate(rate: Float): Int = TextToSpeech.SUCCESS
        override fun setPitch(pitch: Float): Int = TextToSpeech.SUCCESS
        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun setVoice(voice: android.speech.tts.Voice): Int = TextToSpeech.SUCCESS
        override fun setVoiceByName(voiceName: String): Int = TextToSpeech.SUCCESS
        override fun getVoices(): Set<android.speech.tts.Voice>? = null
        override fun getVoiceInfoList(): List<VoiceInfo>? = null

        override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int {
            spokenQueue.add(utteranceId)
            return TextToSpeech.SUCCESS
        }

        override fun speakLegacy(text: String, queueMode: Int, params: HashMap<String, String>): Int {
            params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID]?.let { spokenQueue.add(it) }
            return TextToSpeech.SUCCESS
        }

        override fun playSilentUtterance(durationMs: Long, queueMode: Int, utteranceId: String): Int {
            silenceQueue.add(utteranceId)
            return TextToSpeech.SUCCESS
        }

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
            this.listener = listener
        }

        override fun stop(): Int {
            stopCount++
            return TextToSpeech.SUCCESS
        }

        override fun shutdown() {}
    }

    @Test
    fun testCompletionDeduplicationAndSilenceHandling() {
        val stateChanges = mutableListOf<ITTSManager.TTSState>()
        val progressValues = mutableListOf<Int>()
        val mockContext = createMockContext()
        val fakeEngine = TestableTtsEngine()

        val manager = GoogleTTSManager(
            context = mockContext,
            onStateChange = { stateChanges.add(it) },
            onProgressChange = { progressValues.add(it) },
            customEngineWrapper = fakeEngine
        )

        assertNotNull("Listener should be registered", fakeEngine.listener)
        val listener = fakeEngine.listener!!

        // Clear initialization state recordings
        stateChanges.clear()
        progressValues.clear()

        // 1. Speak a story that results in multi-segment chunks
        manager.speakWithTitlePause(
            title = "Chương 1: Khởi đầu",
            content = "Đây là câu thứ nhất. Đây là câu thứ hai. Kết thúc câu chuyện."
        )

        assertEquals(ITTSManager.TTSState.PLAYING, manager.currentState)
        assertEquals(listOf(ITTSManager.TTSState.PLAYING), stateChanges)
        assertTrue(fakeEngine.spokenQueue.isNotEmpty())
        assertEquals("seg_0", fakeEngine.spokenQueue.first())

        // 2. Simulate start of segment 0
        listener.onStart("seg_0")
        // onStart("seg_0") should not emit duplicate PLAYING if already in PLAYING state
        assertEquals(1, stateChanges.count { it == ITTSManager.TTSState.PLAYING })

        // 3. Test progress event
        listener.onRangeStart("seg_0", 0, 5, 0)
        assertTrue("Progress should be emitted", progressValues.isNotEmpty())

        // 4. Stale utterance callbacks or silence done should be ignored
        val stateCountBeforeSilence = stateChanges.size
        listener.onDone("silence_0")
        assertEquals("Silence done callback should NOT advance segment or change state", stateCountBeforeSilence, stateChanges.size)

        listener.onDone("seg_999") // Non-matching segment
        assertEquals("Stale utterance ID should be ignored", stateCountBeforeSilence, stateChanges.size)

        // 5. Complete segment 0 -> moves to segment 1
        listener.onDone("seg_0")
        assertTrue("Next segment seg_1 should be queued", fakeEngine.spokenQueue.contains("seg_1"))

        // Complete remaining segments until completion
        var segIndex = 1
        while (manager.currentState == ITTSManager.TTSState.PLAYING && segIndex < 10) {
            listener.onStart("seg_$segIndex")
            listener.onDone("seg_$segIndex")
            segIndex++
        }

        // 6. Verify final completion state
        assertEquals(ITTSManager.TTSState.READY, manager.currentState)
        assertEquals("Should trigger READY exactly once on completion", 1, stateChanges.count { it == ITTSManager.TTSState.READY })
        assertEquals(100, progressValues.last())

        // 7. Trailing out-of-order callbacks after completion must be ignored
        val readyCount = stateChanges.count { it == ITTSManager.TTSState.READY }
        listener.onDone("seg_${segIndex - 1}") // duplicate completion callback
        listener.onDone("silence_${segIndex - 1}")
        listener.onStart("seg_${segIndex - 1}")

        assertEquals("No duplicate READY state changes should occur", readyCount, stateChanges.count { it == ITTSManager.TTSState.READY })
        assertEquals(ITTSManager.TTSState.READY, manager.currentState)

        // 8. Verify stop ignores trailing callbacks
        manager.speak("Một đoạn văn khác")
        assertEquals(ITTSManager.TTSState.PLAYING, manager.currentState)
        manager.stop()
        assertEquals(ITTSManager.TTSState.READY, manager.currentState)

        // Trailing onDone from stopped speech
        listener.onDone("seg_0")
        assertEquals(ITTSManager.TTSState.READY, manager.currentState)
    }
}
