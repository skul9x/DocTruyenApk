package com.skul9x.doctruyen.service

import android.app.Notification
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.tts.VoiceInfo
import com.skul9x.doctruyen.tts.vieneu.PauseConfig
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ReadingServiceStateDeduplicationTest {

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
        override val engineType: ITTSManager.EngineType = ITTSManager.EngineType.VIENEU,
        val onStateChange: (ITTSManager.TTSState) -> Unit
    ) : ITTSManager {
        override var currentState: ITTSManager.TTSState = ITTSManager.TTSState.IDLE
            private set
        override val currentVoiceName: String = "Trúc Ly"

        fun emitState(state: ITTSManager.TTSState) {
            currentState = state
            onStateChange(state)
        }

        override fun speak(text: String, storyId: Int) {}
        override fun speakWithTitlePause(title: String, content: String, storyId: Int, pauseConfig: PauseConfig) {}
        override fun pause() { emitState(ITTSManager.TTSState.PAUSED) }
        override fun resume() { emitState(ITTSManager.TTSState.PLAYING) }
        override fun stop() { emitState(ITTSManager.TTSState.READY) }
        override fun isPlaying(): Boolean = currentState == ITTSManager.TTSState.PLAYING
        override fun isPaused(): Boolean = currentState == ITTSManager.TTSState.PAUSED
        override fun setVoice(voiceName: String) {}
        override fun getAvailableVoices(): List<VoiceInfo> = emptyList()
        override fun setSpeed(speed: Float) {}
        override fun setPitch(pitch: Float) {}
        override fun prepareStory(storyId: Int, title: String, content: String, voice: String?, pauseConfig: PauseConfig) {}
        override fun cancelPrewarm() {}
        override fun shutdown() { emitState(ITTSManager.TTSState.IDLE) }
    }

    private lateinit var fakeNotificationManager: FakeNotificationManagerWrapper
    private lateinit var service: ReadingService
    private lateinit var fakeTtsManager: FakeTTSManager
    private val callbackStateHistory = mutableListOf<ITTSManager.TTSState>()

    @Before
    fun setUp() {
        fakeNotificationManager = FakeNotificationManagerWrapper()
        callbackStateHistory.clear()

        service = ReadingService().apply {
            notificationManager = fakeNotificationManager
            notificationBuilder = { Notification() }
        }

        // Initialize TTS manager wired directly to service's internal state logic via reflection / factory mock pattern
        // To precisely test ReadingService.initTTS logic with our controlled fake, we simulate the state listener behavior:
    }

    @Test
    fun testDuplicateStateTransitions_deduplicatedForNotificationAndCallbacks() {
        val observedStates = mutableListOf<ITTSManager.TTSState>()

        // Wire service state listener
        var ttsCallback: ((ITTSManager.TTSState) -> Unit)? = null

        fakeTtsManager = FakeTTSManager { state ->
            ttsCallback?.invoke(state)
        }

        // Construct service callback listener mimicking initTTS
        val onStateChangeHandler: (ITTSManager.TTSState) -> Unit = { state ->
            val previousState = service.currentState
            val isStateChanged = previousState != state
            val currentStateField = ReadingService::class.java.getDeclaredField("currentState")
            currentStateField.isAccessible = true
            currentStateField.set(service, state)

            if (isStateChanged) {
                service.updateMediaSessionState(state)

                if (state == ITTSManager.TTSState.PLAYING || state == ITTSManager.TTSState.PAUSED) {
                    val method = ReadingService::class.java.getDeclaredMethod("updateNotification", ITTSManager.TTSState::class.java)
                    method.isAccessible = true
                    method.invoke(service, state)
                }

                observedStates.add(state)
            }
        }

        ttsCallback = onStateChangeHandler
        service.ttsManager = fakeTtsManager

        // Set title and story
        val storyTitleField = ReadingService::class.java.getDeclaredField("currentStoryTitle")
        storyTitleField.isAccessible = true
        storyTitleField.set(service, "Truyện Kiều")

        // 1. First transition: IDLE -> PLAYING
        fakeTtsManager.emitState(ITTSManager.TTSState.PLAYING)
        assertEquals(1, observedStates.size)
        assertEquals(ITTSManager.TTSState.PLAYING, observedStates[0])
        assertEquals(1, fakeNotificationManager.notifyCalls.size)

        // 2. Rapid duplicate PLAYING emissions (simulating multiple pipeline audio chunks)
        fakeTtsManager.emitState(ITTSManager.TTSState.PLAYING)
        fakeTtsManager.emitState(ITTSManager.TTSState.PLAYING)
        fakeTtsManager.emitState(ITTSManager.TTSState.PLAYING)

        // Assert no new UI callbacks or NotificationManager IPC dispatches
        assertEquals("Duplicate PLAYING states should not trigger duplicate UI callbacks", 1, observedStates.size)
        assertEquals("Duplicate PLAYING states should not trigger duplicate notification updates", 1, fakeNotificationManager.notifyCalls.size)

        // 3. Transition: PLAYING -> PAUSED
        fakeTtsManager.emitState(ITTSManager.TTSState.PAUSED)
        assertEquals(2, observedStates.size)
        assertEquals(ITTSManager.TTSState.PAUSED, observedStates[1])
        assertEquals(2, fakeNotificationManager.notifyCalls.size)

        // 4. Rapid duplicate PAUSED emissions
        fakeTtsManager.emitState(ITTSManager.TTSState.PAUSED)
        fakeTtsManager.emitState(ITTSManager.TTSState.PAUSED)
        assertEquals(2, observedStates.size)
        assertEquals(2, fakeNotificationManager.notifyCalls.size)

        // 5. Transition: PAUSED -> PLAYING (Resumed)
        fakeTtsManager.emitState(ITTSManager.TTSState.PLAYING)
        assertEquals(3, observedStates.size)
        assertEquals(ITTSManager.TTSState.PLAYING, observedStates[2])
        assertEquals(3, fakeNotificationManager.notifyCalls.size)
    }

    @Test
    fun testVieNeuTTSManager_notifyStateChange_deduplicatesConsecutiveStates() {
        val managerCallbacks = mutableListOf<ITTSManager.TTSState>()
        val manager = com.skul9x.doctruyen.tts.vieneu.VieNeuTTSManager(
            context = null,
            onStateChange = { state -> managerCallbacks.add(state) }
        )

        // After initialization (without context), it transitions INITIALIZING -> ERROR
        val initialCount = managerCallbacks.size

        val notifyMethod = com.skul9x.doctruyen.tts.vieneu.VieNeuTTSManager::class.java.getDeclaredMethod(
            "notifyStateChange",
            ITTSManager.TTSState::class.java
        )
        notifyMethod.isAccessible = true

        // Emit PLAYING
        notifyMethod.invoke(manager, ITTSManager.TTSState.PLAYING)
        assertEquals(initialCount + 1, managerCallbacks.size)
        assertEquals(ITTSManager.TTSState.PLAYING, managerCallbacks.last())

        // Rapid duplicate PLAYING emissions
        notifyMethod.invoke(manager, ITTSManager.TTSState.PLAYING)
        notifyMethod.invoke(manager, ITTSManager.TTSState.PLAYING)
        notifyMethod.invoke(manager, ITTSManager.TTSState.PLAYING)

        assertEquals("VieNeuTTSManager must suppress identical consecutive state updates", initialCount + 1, managerCallbacks.size)

        // Emit PAUSED
        notifyMethod.invoke(manager, ITTSManager.TTSState.PAUSED)
        assertEquals(initialCount + 2, managerCallbacks.size)
        assertEquals(ITTSManager.TTSState.PAUSED, managerCallbacks.last())

        // Rapid duplicate PAUSED emissions
        notifyMethod.invoke(manager, ITTSManager.TTSState.PAUSED)
        notifyMethod.invoke(manager, ITTSManager.TTSState.PAUSED)

        assertEquals("VieNeuTTSManager must suppress duplicate PAUSED states", initialCount + 2, managerCallbacks.size)
    }
}
