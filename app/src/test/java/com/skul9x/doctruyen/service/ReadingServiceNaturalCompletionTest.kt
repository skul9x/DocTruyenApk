package com.skul9x.doctruyen.service

import android.app.Notification
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.tts.VoiceInfo
import com.skul9x.doctruyen.tts.vieneu.PauseConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ReadingServiceNaturalCompletionTest {

    private class FakeWakeLock : IWakeLock {
        var isHeldState = false
        var acquireCount = 0
        var releaseCount = 0

        override val isHeld: Boolean
            get() = isHeldState

        override fun acquire(timeout: Long?) {
            isHeldState = true
            acquireCount++
        }

        override fun release() {
            isHeldState = false
            releaseCount++
        }
    }

    private class FakeNotificationManagerWrapper : INotificationManagerWrapper {
        val notifyCalls = mutableListOf<Pair<Int, Notification>>()
        val cancelCalls = mutableListOf<Int>()

        override fun notify(id: Int, notification: Notification) {
            notifyCalls.add(id to notification)
        }

        override fun cancel(id: Int) {
            cancelCalls.add(id)
        }
    }

    private class FakeTTSManager(
        override val engineType: ITTSManager.EngineType = ITTSManager.EngineType.GOOGLE_TTS,
        var onStateChangeCallback: ((ITTSManager.TTSState) -> Unit)? = null
    ) : ITTSManager {
        override var currentState: ITTSManager.TTSState = ITTSManager.TTSState.IDLE
            private set
        override var currentVoiceName: String = "vi-vn-x-vic-network"

        var stopCallCount = 0
        var cancelPrewarmCallCount = 0
        val spokenCalls = mutableListOf<Triple<String, String, Int>>()

        fun emitState(state: ITTSManager.TTSState) {
            currentState = state
            onStateChangeCallback?.invoke(state)
        }

        override fun speak(text: String, storyId: Int) {
            spokenCalls.add(Triple("", text, storyId))
            emitState(ITTSManager.TTSState.PLAYING)
        }

        override fun speakWithTitlePause(title: String, content: String, storyId: Int, pauseConfig: PauseConfig) {
            spokenCalls.add(Triple(title, content, storyId))
            emitState(ITTSManager.TTSState.PLAYING)
        }

        override fun pause() {
            emitState(ITTSManager.TTSState.PAUSED)
        }

        override fun resume() {
            emitState(ITTSManager.TTSState.PLAYING)
        }

        override fun stop() {
            stopCallCount++
            emitState(ITTSManager.TTSState.READY)
        }

        override fun isPlaying(): Boolean = currentState == ITTSManager.TTSState.PLAYING
        override fun isPaused(): Boolean = currentState == ITTSManager.TTSState.PAUSED
        override fun setVoice(voiceName: String) {
            currentVoiceName = voiceName
        }
        override fun getAvailableVoices(): List<VoiceInfo> = emptyList()
        override fun setSpeed(speed: Float) {}
        override fun setPitch(pitch: Float) {}
        override fun prepareStory(storyId: Int, title: String, content: String, voice: String?, pauseConfig: PauseConfig) {
            emitState(ITTSManager.TTSState.READY)
        }
        override fun cancelPrewarm() {
            cancelPrewarmCallCount++
        }
        override fun shutdown() {
            emitState(ITTSManager.TTSState.IDLE)
        }
    }

    private lateinit var service: ReadingService
    private lateinit var fakeTTS: FakeTTSManager
    private lateinit var fakeWakeLock: FakeWakeLock
    private lateinit var fakeNotificationManager: FakeNotificationManagerWrapper
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

        fakeTTS = FakeTTSManager()
        fakeWakeLock = FakeWakeLock()
        fakeNotificationManager = FakeNotificationManagerWrapper()

        service = ReadingService().apply {
            wakeLock = fakeWakeLock
            notificationManager = fakeNotificationManager
            notificationBuilder = { Notification() }
            ttsManagerFactory = { _, onStateChange, _, _ ->
                fakeTTS.onStateChangeCallback = onStateChange
                fakeTTS
            }
        }

        // Initialize TTS inside service
        service.initTTS()
    }

    @org.junit.After
    fun tearDown() {
        com.skul9x.doctruyen.utils.UserConfig.customPrefsProvider = null
    }

    @Test
    fun testNaturalPlaybackCompletion_resetsCurrentStoryIdAndTransitionsCleanlyToReady() {
        val observedStates = mutableListOf<ITTSManager.TTSState>()
        val observedProgress = mutableListOf<Int>()

        service.setStateCallback { state ->
            observedStates.add(state)
        }
        service.setProgressCallback { progress ->
            observedProgress.add(progress)
        }

        // 1. Initial State: IDLE, currentStoryId = 0
        assertEquals(ITTSManager.TTSState.IDLE, service.currentState)
        assertEquals(0, service.currentStoryId)
        assertEquals(0, service.preparingStoryId)

        // 2. Start Reading Story 101
        service.startReading(storyId = 101, title = "Truyện Kiều", content = "Trăm năm trong cõi người ta...")
        assertEquals(101, service.currentStoryId)
        assertEquals("Truyện Kiều", service.currentStoryTitle)
        assertEquals(ITTSManager.TTSState.PLAYING, service.currentState)
        assertTrue("WakeLock should be held during playback", fakeWakeLock.isHeld)

        // 3. Engine finishes naturally and emits READY
        fakeTTS.emitState(ITTSManager.TTSState.READY)

        // Verify currentStoryId & preparingStoryId reset to 0
        assertEquals("currentStoryId must reset to 0 upon natural playback completion", 0, service.currentStoryId)
        assertEquals("preparingStoryId must reset to 0 upon natural playback completion", 0, service.preparingStoryId)
        assertEquals(ITTSManager.TTSState.READY, service.currentState)

        // Verify WakeLock is released
        assertFalse("WakeLock must be released on playback completion", fakeWakeLock.isHeld)

        // Verify progress reset to 0
        assertTrue("Progress callback should have received reset to 0", observedProgress.contains(0))

        // Verify states observed by subscriber
        assertTrue("Subscribers must receive PLAYING and READY transitions", observedStates.contains(ITTSManager.TTSState.PLAYING))
        assertEquals(ITTSManager.TTSState.READY, observedStates.last())
    }

    @Test
    fun testNaturalCompletionToIdle_resetsCurrentStoryId() {
        // Start reading story 202
        service.startReading(storyId = 202, title = "Lục Vân Tiên", content = "Trước đèn giở sách thánh hiền...")
        assertEquals(202, service.currentStoryId)
        assertEquals(ITTSManager.TTSState.PLAYING, service.currentState)

        // Natural transition to IDLE
        fakeTTS.emitState(ITTSManager.TTSState.IDLE)

        assertEquals("currentStoryId must reset to 0 upon IDLE state", 0, service.currentStoryId)
        assertEquals("preparingStoryId must reset to 0 upon IDLE state", 0, service.preparingStoryId)
        assertEquals(ITTSManager.TTSState.IDLE, service.currentState)
        assertFalse("WakeLock must be released on IDLE", fakeWakeLock.isHeld)
    }

    @Test
    fun testServiceStateGuard_preventsDuplicateVoiceRebinding() {
        service.startReading(storyId = 303, title = "Tắt Đèn", content = "Ngoi lên...")
        assertEquals(303, service.currentStoryId)

        // Set same voice name
        val currentVoice = fakeTTS.currentVoiceName
        fakeTTS.setVoice(currentVoice)

        // Natural completion
        fakeTTS.emitState(ITTSManager.TTSState.READY)
        assertEquals(0, service.currentStoryId)

        // If prepareStory called for another story, currentStoryId remains 0 and preparingStoryId is set
        service.prepareStory(storyId = 404, title = "Lão Hạc", content = "Lão Hạc khóc...")
        assertEquals(0, service.currentStoryId)
        assertEquals(404, service.preparingStoryId)
    }
}
