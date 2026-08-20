package com.skul9x.doctruyen.service

import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.tts.VoiceInfo
import com.skul9x.doctruyen.tts.vieneu.PauseConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadingServicePrepareGuardTest {

    private class MockTTSManager : ITTSManager {
        override val engineType: ITTSManager.EngineType = ITTSManager.EngineType.VIENEU
        override var currentState: ITTSManager.TTSState = ITTSManager.TTSState.IDLE
        override val currentVoiceName: String = "Trúc Ly"

        val preparedCalls = mutableListOf<PrepareCall>()
        val spokenCalls = mutableListOf<SpokenCall>()
        var stopCallCount = 0
        var cancelPrewarmCallCount = 0

        data class PrepareCall(val storyId: Int, val title: String, val content: String, val voice: String?)
        data class SpokenCall(val title: String, val content: String, val storyId: Int)

        override fun speak(text: String, storyId: Int) {}
        override fun speakWithTitlePause(title: String, content: String, storyId: Int, pauseConfig: PauseConfig) {
            spokenCalls.add(SpokenCall(title, content, storyId))
            currentState = ITTSManager.TTSState.PLAYING
        }

        override fun pause() {
            currentState = ITTSManager.TTSState.PAUSED
        }

        override fun resume() {
            currentState = ITTSManager.TTSState.PLAYING
        }

        override fun stop() {
            stopCallCount++
            currentState = ITTSManager.TTSState.IDLE
        }

        override fun isPlaying(): Boolean = currentState == ITTSManager.TTSState.PLAYING
        override fun isPaused(): Boolean = currentState == ITTSManager.TTSState.PAUSED
        override fun setVoice(voiceName: String) {}
        override fun getAvailableVoices(): List<VoiceInfo> = emptyList()
        override fun setSpeed(speed: Float) {}
        override fun setPitch(pitch: Float) {}

        override fun prepareStory(storyId: Int, title: String, content: String, voice: String?, pauseConfig: PauseConfig) {
            preparedCalls.add(PrepareCall(storyId, title, content, voice))
            currentState = ITTSManager.TTSState.READY
        }

        override fun cancelPrewarm() {
            cancelPrewarmCallCount++
            currentState = ITTSManager.TTSState.IDLE
        }

        override fun shutdown() {
            currentState = ITTSManager.TTSState.IDLE
        }
    }

    private lateinit var service: ReadingService
    private lateinit var mockTTS: MockTTSManager
    private val preferencesMap = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        preferencesMap.clear()

        val editorProxy = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences.Editor::class.java.classLoader,
            arrayOf(android.content.SharedPreferences.Editor::class.java),
            java.lang.reflect.InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "putString" -> {
                        preferencesMap[args[0] as String] = args[1]
                        proxy
                    }
                    "apply", "commit" -> null
                    else -> null
                }
            }
        ) as android.content.SharedPreferences.Editor

        val prefsProxy = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java),
            java.lang.reflect.InvocationHandler { _, method, args ->
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
        ) as android.content.SharedPreferences

        com.skul9x.doctruyen.utils.UserConfig.customPrefsProvider = { prefsProxy }

        service = ReadingService()
        mockTTS = MockTTSManager()
        service.ttsManager = mockTTS
    }

    @org.junit.After
    fun tearDown() {
        com.skul9x.doctruyen.utils.UserConfig.customPrefsProvider = null
    }

    @Test
    fun testPrepareStoryGuard_comprehensiveLifecycle() {
        // 1. First call to prepareStory(storyId = 1) -> Forwarded to TTSManager
        service.prepareStory(storyId = 1, title = "Story 1", content = "Content 1")
        assertEquals(1, mockTTS.preparedCalls.size)
        assertEquals(1, mockTTS.preparedCalls[0].storyId)
        assertEquals(1, service.preparingStoryId)
        assertEquals(ITTSManager.TTSState.READY, mockTTS.currentState)

        // Duplicate prepareStory(storyId = 1) while in READY state -> Guard skips it
        service.prepareStory(storyId = 1, title = "Story 1", content = "Content 1")
        assertEquals("Duplicate prepareStory on same story in READY state must be skipped", 1, mockTTS.preparedCalls.size)

        // 2. Start reading story 1 -> state becomes PLAYING, preparingStoryId cleared, currentStoryId set
        service.startReading(storyId = 1, title = "Story 1", content = "Content 1")
        assertEquals(1, service.currentStoryId)
        assertEquals(0, service.preparingStoryId)
        assertEquals(ITTSManager.TTSState.PLAYING, mockTTS.currentState)

        // Second call to prepareStory(storyId = 1) while PLAYING -> Guard skips it
        service.prepareStory(storyId = 1, title = "Story 1", content = "Content 1")
        assertEquals("Duplicate prepareStory while PLAYING must be skipped", 1, mockTTS.preparedCalls.size)

        // Pause reading -> state becomes PAUSED
        service.pauseReading()
        assertEquals(ITTSManager.TTSState.PAUSED, mockTTS.currentState)

        // prepareStory(storyId = 1) while PAUSED -> Guard skips it
        service.prepareStory(storyId = 1, title = "Story 1", content = "Content 1")
        assertEquals("Duplicate prepareStory while PAUSED must be skipped", 1, mockTTS.preparedCalls.size)

        // 3. Call to prepareStory(storyId = 2) (different story) while active on story 1 -> ALLOWED
        service.prepareStory(storyId = 2, title = "Story 2", content = "Content 2")
        assertEquals("prepareStory for different storyId must be forwarded", 2, mockTTS.preparedCalls.size)
        assertEquals(2, mockTTS.preparedCalls[1].storyId)
        assertEquals(2, service.preparingStoryId)

        // 4. Call stopReading() -> state resets, currentStoryId and preparingStoryId reset to 0
        service.stopReading()
        assertEquals(0, service.currentStoryId)
        assertEquals(0, service.preparingStoryId)
        assertEquals(ITTSManager.TTSState.IDLE, mockTTS.currentState)

        // Call prepareStory(storyId = 1) after stopReading() -> ALLOWED
        service.prepareStory(storyId = 1, title = "Story 1", content = "Content 1")
        assertEquals("prepareStory after stopReading must be forwarded", 3, mockTTS.preparedCalls.size)
        assertEquals(1, mockTTS.preparedCalls[2].storyId)
        assertEquals(1, service.preparingStoryId)
    }
}
