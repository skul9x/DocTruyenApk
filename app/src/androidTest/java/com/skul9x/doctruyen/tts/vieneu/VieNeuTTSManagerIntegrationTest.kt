package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VieNeuTTSManagerIntegrationTest {

    private lateinit var context: Context
    private lateinit var manager: VieNeuTTSManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun teardown() {
        if (::manager.isInitialized) {
            manager.shutdown()
        }
    }

    @Test
    fun testInitializationLifecycle() {
        val states = mutableListOf<VieNeuTTSManager.TTSState>()
        val readyLatch = CountDownLatch(1)

        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { state ->
                states.add(state)
                if (state == VieNeuTTSManager.TTSState.READY) {
                    readyLatch.countDown()
                }
            }
        )

        // Wait up to 30 seconds for initialization
        val initialized = readyLatch.await(30, TimeUnit.SECONDS)

        assertTrue("Manager should reach READY state within 30s", initialized)
        assertTrue("States should include INITIALIZING", 
            states.contains(VieNeuTTSManager.TTSState.INITIALIZING))
        assertTrue("States should include READY", 
            states.contains(VieNeuTTSManager.TTSState.READY))
        println("✅ Initialization lifecycle: ${states.joinToString(" → ")}")
    }

    @Test
    fun testSpeakAndStateTransitions() = runBlocking {
        val states = mutableListOf<VieNeuTTSManager.TTSState>()
        val doneLatch = CountDownLatch(1)

        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { state ->
                states.add(state)
                if (state == VieNeuTTSManager.TTSState.READY && states.count { 
                    it == VieNeuTTSManager.TTSState.PLAYING 
                } > 0) {
                    doneLatch.countDown()
                }
            }
        )

        // Wait for READY
        delay(10000)

        // Speak a short sentence
        manager.speakContent("Xin chào.")

        // Wait for completion (max 30s)
        doneLatch.await(30, TimeUnit.SECONDS)

        assertTrue("Should have entered PLAYING state", 
            states.contains(VieNeuTTSManager.TTSState.PLAYING))
        println("✅ Speak lifecycle: ${states.joinToString(" → ")}")
    }

    @Test
    fun testPauseResume() = runBlocking {
        val states = mutableListOf<VieNeuTTSManager.TTSState>()

        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { state -> states.add(state) }
        )

        delay(10000) // wait for init

        // Start speaking a longer text
        manager.speakContent("Ngày xưa có một chàng trai nghèo sống bên bờ sông. Anh ta rất chăm chỉ.")

        delay(3000) // let it start playing

        // Pause
        manager.pause()
        assertTrue("Should be paused", manager.isPaused())
        assertFalse("Should not be playing", manager.isPlaying())

        delay(1000)

        // Resume
        manager.resume()
        delay(2000)

        assertTrue("Should have transitioned through PAUSED state", 
            states.contains(VieNeuTTSManager.TTSState.PAUSED))
        println("✅ Pause/Resume test passed")
    }

    @Test
    fun testVoiceSelection() = runBlocking {
        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { }
        )
        delay(10000)

        val voices = manager.getAvailableVoices()
        assertTrue("Should have at least 1 preset voice", voices.isNotEmpty())
        println("Available voices: $voices")

        // Set a different voice
        if (voices.size > 1) {
            manager.setVoice(voices[1])
            println("✅ Voice changed to: ${voices[1]}")
        }
    }

    @Test
    fun testSpeakWithTitlePause() = runBlocking {
        var progressValues = mutableListOf<Int>()

        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { },
            onProgressChange = { progress -> progressValues.add(progress) }
        )
        delay(10000)

        manager.speakWithTitlePause(
            title = "Chương Một",
            content = "Ngày xưa có một chàng trai."
        )

        delay(15000) // wait for title + pause + content

        assertTrue("Progress should have been reported", progressValues.isNotEmpty())
        println("✅ speakWithTitlePause completed. Progress values: $progressValues")
    }

    @Test
    fun testStopCleansUp() = runBlocking {
        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { }
        )
        delay(10000)

        manager.speakContent("Đây là một câu dài để test chức năng dừng giữa chừng.")
        delay(2000) // let it start

        manager.stop()

        assertFalse("Should not be playing after stop", manager.isPlaying())
        assertFalse("Should not be paused after stop", manager.isPaused())
        println("✅ Stop cleanup test passed")
    }
}
