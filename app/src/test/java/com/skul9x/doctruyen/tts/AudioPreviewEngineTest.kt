package com.skul9x.doctruyen.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AudioPreviewEngineTest {

    private class FakeAudioPreviewPlayer : IAudioPreviewPlayer {
        val playCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)
        val releaseCalls = AtomicInteger(0)
        var lastPlayedPcm: ShortArray? = null
        var completionCallback: (() -> Unit)? = null

        @Volatile
        override var isPlaying: Boolean = false

        override fun play(pcm16: ShortArray, onCompletion: () -> Unit) {
            playCalls.incrementAndGet()
            lastPlayedPcm = pcm16
            isPlaying = true
            completionCallback = onCompletion
        }

        override fun stop() {
            stopCalls.incrementAndGet()
            isPlaying = false
            completionCallback = null
        }

        override fun release() {
            releaseCalls.incrementAndGet()
            isPlaying = false
            completionCallback = null
        }

        fun triggerCompletion() {
            if (isPlaying) {
                isPlaying = false
                val cb = completionCallback
                completionCallback = null
                cb?.invoke()
            }
        }
    }

    private class FakeTtsEngineWrapper : ITtsEngineWrapper {
        var setVoiceNameCalls = AtomicInteger(0)
        var lastVoiceNameSet: String? = null
        var speakCalls = AtomicInteger(0)
        var lastSpokenText: String? = null
        var lastUtteranceId: String? = null
        var stopCalls = AtomicInteger(0)
        var listener: UtteranceProgressListener? = null

        override fun setSpeechRate(rate: Float): Int = TextToSpeech.SUCCESS
        override fun setPitch(pitch: Float): Int = TextToSpeech.SUCCESS
        override fun setLanguage(locale: Locale): Int = TextToSpeech.SUCCESS
        override fun setVoice(voice: Voice): Int = TextToSpeech.SUCCESS
        override fun setVoiceByName(voiceName: String): Int {
            setVoiceNameCalls.incrementAndGet()
            lastVoiceNameSet = voiceName
            return TextToSpeech.SUCCESS
        }
        override fun getVoices(): Set<Voice>? = null
        override fun getVoiceInfoList(): List<VoiceInfo>? = null

        override fun speak(text: String, queueMode: Int, params: Bundle?, utteranceId: String): Int {
            speakCalls.incrementAndGet()
            lastSpokenText = text
            lastUtteranceId = utteranceId
            return TextToSpeech.SUCCESS
        }

        override fun speakLegacy(text: String, queueMode: Int, params: HashMap<String, String>): Int = TextToSpeech.SUCCESS
        override fun playSilentUtterance(durationMs: Long, queueMode: Int, utteranceId: String): Int = TextToSpeech.SUCCESS
        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener) {
            this.listener = listener
        }
        override fun stop(): Int {
            stopCalls.incrementAndGet()
            return TextToSpeech.SUCCESS
        }
        override fun shutdown() {}
    }

    private lateinit var cache: AudioPreviewCache
    private lateinit var player: FakeAudioPreviewPlayer
    private lateinit var fakeGoogleTts: FakeTtsEngineWrapper

    @Before
    fun setup() {
        cache = AudioPreviewCache()
        player = FakeAudioPreviewPlayer()
        fakeGoogleTts = FakeTtsEngineWrapper()
    }

    @Test
    fun testAudioPreviewCachePutGetContainsAndRemove() {
        val voiceId = "thai_son"
        val pcm = shortArrayOf(100, 200, 300, -100, -200)

        assertFalse(cache.contains(voiceId))
        assertNull(cache.get(voiceId))
        assertEquals(0, cache.entryCount)
        assertEquals(0L, cache.totalBytes)

        cache.put(voiceId, pcm)

        assertTrue(cache.contains(voiceId))
        assertNotNull(cache.get(voiceId))
        assertArrayEquals(pcm, cache.get(voiceId))
        assertEquals(1, cache.entryCount)
        assertEquals(10L, cache.totalBytes) // 5 samples * 2 bytes = 10 bytes

        val removed = cache.remove(voiceId)
        assertNotNull(removed)
        assertArrayEquals(pcm, removed)
        assertFalse(cache.contains(voiceId))
        assertEquals(0, cache.entryCount)
        assertEquals(0L, cache.totalBytes)
    }

    @Test
    fun testAudioPreviewCacheLRUEvictionByEntryCount() {
        // Max 3 entries
        val boundedCache = AudioPreviewCache(maxEntries = 3, maxSizeBytes = 10000L)
        val pcm1 = shortArrayOf(1)
        val pcm2 = shortArrayOf(2)
        val pcm3 = shortArrayOf(3)
        val pcm4 = shortArrayOf(4)

        boundedCache.put("v1", pcm1)
        boundedCache.put("v2", pcm2)
        boundedCache.put("v3", pcm3)
        assertEquals(3, boundedCache.entryCount)

        // Access v1 so v2 becomes the oldest
        assertNotNull(boundedCache.get("v1"))

        // Add 4th entry -> v2 should be evicted!
        boundedCache.put("v4", pcm4)
        assertEquals(3, boundedCache.entryCount)
        assertTrue(boundedCache.contains("v1"))
        assertFalse(boundedCache.contains("v2"))
        assertTrue(boundedCache.contains("v3"))
        assertTrue(boundedCache.contains("v4"))
    }

    @Test
    fun testAudioPreviewCacheLRUEvictionByByteBudget() {
        // 20 bytes budget (each ShortArray of 5 elements is 10 bytes)
        val boundedCache = AudioPreviewCache(maxEntries = 10, maxSizeBytes = 20L)
        val pcmA = ShortArray(5) { 1 } // 10 bytes
        val pcmB = ShortArray(5) { 2 } // 10 bytes
        val pcmC = ShortArray(5) { 3 } // 10 bytes

        boundedCache.put("voiceA", pcmA)
        assertEquals(10L, boundedCache.totalBytes)

        boundedCache.put("voiceB", pcmB)
        assertEquals(20L, boundedCache.totalBytes)

        // Adding voiceC requires 10 bytes, total would be 30 > 20 -> voiceA evicted!
        boundedCache.put("voiceC", pcmC)
        assertEquals(20L, boundedCache.totalBytes)
        assertFalse(boundedCache.contains("voiceA"))
        assertTrue(boundedCache.contains("voiceB"))
        assertTrue(boundedCache.contains("voiceC"))
    }

    @Test
    fun testSampleGreetingGenerationForVoices() {
        val voiceVieNeu = VoiceInfo(
            id = "thaison",
            displayName = "Thái Sơn",
            gender = "Nam",
            region = "Miền Nam",
            style = "Kể chuyện"
        )
        val greetingVieNeu = AudioPreviewEngine.generateGreetingText(voiceVieNeu)
        assertEquals("Xin chào, tôi là Thái Sơn, rất vui được đọc truyện cùng bạn.", greetingVieNeu)

        val voiceGoogle = VoiceInfo(
            id = "vi-vn-x-vic-network",
            displayName = "Google Tiếng Việt 1",
            gender = "Nữ"
        )
        val greetingGoogle = AudioPreviewEngine.generateGreetingText(voiceGoogle)
        assertEquals("Xin chào, tôi là Google Tiếng Việt 1, rất vui được đọc truyện cùng bạn.", greetingGoogle)

        val fallbackNameGreeting = AudioPreviewEngine.generateGreetingText("Hải Đăng")
        assertEquals("Xin chào, tôi là Hải Đăng, rất vui được đọc truyện cùng bạn.", fallbackNameGreeting)

        val emptyDisplayNameVoice = VoiceInfo(id = "minh_duc", displayName = "")
        val greetingFallbackId = AudioPreviewEngine.generateGreetingText(emptyDisplayNameVoice)
        assertEquals("Xin chào, tôi là minh_duc, rất vui được đọc truyện cùng bạn.", greetingFallbackId)
    }

    @Test
    fun testPreviewStateTransitionsLoadingToPlayingToStopped() = runBlocking {
        val stateHistory = Collections.synchronizedList(mutableListOf<AudioPreviewState>())
        val testPcm = shortArrayOf(10, 20, 30)

        val engine = AudioPreviewEngine(
            cache = cache,
            audioPlayer = player,
            customSynthesizer = { _, _ ->
                delay(50)
                testPcm
            },
            dispatcher = Dispatchers.Unconfined,
            onStateChange = { stateHistory.add(it) }
        )

        val voice = VoiceInfo(id = "truc_ly", displayName = "Trúc Ly")
        engine.play(voice)

        // Initial state should transition to Loading
        assertTrue(stateHistory.any { it is AudioPreviewState.Loading && it.voiceId == "truc_ly" })

        // Wait briefly for synthesis to finish
        delay(100)

        // State transitions to Playing
        assertTrue(stateHistory.any { it is AudioPreviewState.Playing && it.voiceId == "truc_ly" })
        assertEquals(1, player.playCalls.get())
        assertArrayEquals(testPcm, player.lastPlayedPcm)
        assertTrue(engine.isPlaying)

        // Simulate natural audio playback completion
        player.triggerCompletion()

        assertTrue(stateHistory.any { it is AudioPreviewState.Stopped && it.voiceId == "truc_ly" })
        assertFalse(engine.isPlaying)
    }

    @Test
    fun testSingleActivePlaybackCancelsPreviousPreview() = runBlocking {
        val synthesisCalls = AtomicInteger(0)
        val stateHistory = Collections.synchronizedList(mutableListOf<AudioPreviewState>())

        val engine = AudioPreviewEngine(
            cache = cache,
            audioPlayer = player,
            customSynthesizer = { voice, _ ->
                synthesisCalls.incrementAndGet()
                delay(100) // Simulate synthesis time
                shortArrayOf(1, 2, 3)
            },
            dispatcher = Dispatchers.Default,
            onStateChange = { stateHistory.add(it) }
        )

        val voiceA = VoiceInfo(id = "voiceA", displayName = "Giọng A")
        val voiceB = VoiceInfo(id = "voiceB", displayName = "Giọng B")

        // Trigger Voice A
        engine.play(voiceA)
        delay(20) // While Voice A is still in Loading

        // Trigger Voice B immediately -> must cancel Voice A
        engine.play(voiceB)
        delay(200)

        // Voice B should be Playing, Voice A should NOT have reached Playing
        val voiceAPlaying = stateHistory.filterIsInstance<AudioPreviewState.Playing>().any { it.voiceId == "voiceA" }
        val voiceBPlaying = stateHistory.filterIsInstance<AudioPreviewState.Playing>().any { it.voiceId == "voiceB" }

        assertFalse("Voice A must not finish into Playing after being cancelled", voiceAPlaying)
        assertTrue("Voice B must reach Playing", voiceBPlaying)
        assertEquals("voiceB", (engine.currentState as AudioPreviewState.Playing).voiceId)
    }

    @Test
    fun testStopHaltsPlaybackAndReleasesResources() = runBlocking {
        val stateHistory = Collections.synchronizedList(mutableListOf<AudioPreviewState>())
        val engine = AudioPreviewEngine(
            cache = cache,
            audioPlayer = player,
            customSynthesizer = { _, _ ->
                shortArrayOf(10, 20)
            },
            dispatcher = Dispatchers.Unconfined,
            onStateChange = { stateHistory.add(it) }
        )

        val voice = VoiceInfo(id = "xuan_vinh", displayName = "Xuân Vĩnh")
        engine.play(voice)

        assertTrue(engine.isPlaying)
        assertEquals(1, player.playCalls.get())

        // Stop actively playing preview
        engine.stop()

        assertFalse(engine.isPlaying)
        assertEquals(1, player.stopCalls.get())
        assertTrue(engine.currentState is AudioPreviewState.Stopped)
    }

    @Test
    fun testToggleFunctionality() = runBlocking {
        val engine = AudioPreviewEngine(
            cache = cache,
            audioPlayer = player,
            customSynthesizer = { _, _ -> shortArrayOf(5, 5, 5) },
            dispatcher = Dispatchers.Unconfined
        )

        val voiceA = VoiceInfo(id = "voiceA", displayName = "Giọng A")
        val voiceB = VoiceInfo(id = "voiceB", displayName = "Giọng B")

        // 1. Toggle voiceA from Idle -> Starts Playing
        engine.toggle(voiceA)
        assertTrue(engine.isPlaying)
        assertEquals("voiceA", (engine.currentState as AudioPreviewState.Playing).voiceId)

        // 2. Toggle voiceA while Playing -> Stops
        engine.toggle(voiceA)
        assertFalse(engine.isPlaying)
        assertTrue(engine.currentState is AudioPreviewState.Stopped)

        // 3. Toggle voiceB while Stopped -> Starts voiceB
        engine.toggle(voiceB)
        assertTrue(engine.isPlaying)
        assertEquals("voiceB", (engine.currentState as AudioPreviewState.Playing).voiceId)
    }

    @Test
    fun testCacheHitAvoidsReSynthesis() = runBlocking {
        val synthesisCount = AtomicInteger(0)
        val pcmSample = shortArrayOf(1, 2, 3, 4)

        val engine = AudioPreviewEngine(
            cache = cache,
            audioPlayer = player,
            customSynthesizer = { _, _ ->
                synthesisCount.incrementAndGet()
                pcmSample
            },
            dispatcher = Dispatchers.Unconfined
        )

        val voice = VoiceInfo(id = "adam_bua", displayName = "Adam bựa")

        // First play: Cache Miss -> Synthesizer invoked
        engine.play(voice)
        assertEquals(1, synthesisCount.get())
        assertTrue(cache.contains("adam_bua"))
        assertEquals(1, player.playCalls.get())

        // Natural completion
        player.triggerCompletion()
        assertFalse(engine.isPlaying)

        // Second play: Cache Hit -> Synthesizer NOT invoked (0 ms latency!)
        engine.play(voice)
        assertEquals(1, synthesisCount.get()) // Still 1!
        assertEquals(2, player.playCalls.get())
        assertTrue(engine.isPlaying)
        assertEquals("adam_bua", (engine.currentState as AudioPreviewState.Playing).voiceId)
    }

    @Test
    fun testCoroutineCancellationAndDialogDismissalRelease() = runBlocking {
        val engine = AudioPreviewEngine(
            cache = cache,
            audioPlayer = player,
            customSynthesizer = { _, _ ->
                delay(500)
                shortArrayOf(1, 2)
            },
            dispatcher = Dispatchers.Default
        )

        val voice = VoiceInfo(id = "thien_minh", displayName = "Thiện Minh")
        engine.play(voice)
        delay(30)
        assertTrue(engine.isLoading)

        // Simulate dialog dismissal / activity destruction
        engine.release()

        assertEquals(AudioPreviewState.Idle, engine.currentState)
        assertEquals(1, player.releaseCalls.get())

        // Wait to confirm synthesis never completed into Playing
        delay(600)
        assertEquals(AudioPreviewState.Idle, engine.currentState)
        assertEquals(0, player.playCalls.get())
    }

    @Test
    fun testGoogleTTSPreviewWithUtteranceListener() {
        val stateHistory = Collections.synchronizedList(mutableListOf<AudioPreviewState>())
        val engine = AudioPreviewEngine(
            cache = cache,
            audioPlayer = player,
            googleTtsWrapper = fakeGoogleTts,
            dispatcher = Dispatchers.Unconfined,
            onStateChange = { stateHistory.add(it) }
        )

        val googleVoice = VoiceInfo(
            id = "vi-vn-x-gft-local",
            displayName = "Google Tiếng Việt Nữ"
        )

        // Play Google TTS preview
        engine.play(googleVoice, engineType = ITTSManager.EngineType.GOOGLE_TTS)

        assertEquals("vi-vn-x-gft-local", fakeGoogleTts.lastVoiceNameSet)
        assertEquals(1, fakeGoogleTts.speakCalls.get())
        assertEquals("Xin chào, tôi là Google Tiếng Việt Nữ, rất vui được đọc truyện cùng bạn.", fakeGoogleTts.lastSpokenText)
        assertEquals("preview_vi-vn-x-gft-local", fakeGoogleTts.lastUtteranceId)
        assertNotNull(fakeGoogleTts.listener)

        // Verify Loading state reached
        assertTrue(stateHistory.any { it is AudioPreviewState.Loading && it.voiceId == "vi-vn-x-gft-local" })

        // Simulate UtteranceProgressListener onStart
        fakeGoogleTts.listener?.onStart("preview_vi-vn-x-gft-local")
        assertTrue(engine.isPlaying)
        assertEquals("vi-vn-x-gft-local", (engine.currentState as AudioPreviewState.Playing).voiceId)

        // Simulate UtteranceProgressListener onDone
        fakeGoogleTts.listener?.onDone("preview_vi-vn-x-gft-local")
        assertFalse(engine.isPlaying)
        assertTrue(engine.currentState is AudioPreviewState.Stopped)

        // Test Stop
        engine.play(googleVoice, engineType = ITTSManager.EngineType.GOOGLE_TTS)
        engine.stop()
        assertEquals(1, fakeGoogleTts.stopCalls.get())
        assertTrue(engine.currentState is AudioPreviewState.Stopped)
    }

    @Test
    fun testErrorHandlingGracefulFallback() = runBlocking {
        val stateHistory = Collections.synchronizedList(mutableListOf<AudioPreviewState>())
        val engine = AudioPreviewEngine(
            cache = cache,
            audioPlayer = player,
            customSynthesizer = { _, _ ->
                throw RuntimeException("ONNX Inference OOM Failure")
            },
            dispatcher = Dispatchers.Unconfined,
            onStateChange = { stateHistory.add(it) }
        )

        val voice = VoiceInfo(id = "corrupted_voice", displayName = "Lỗi")
        engine.play(voice)

        val errorState = engine.currentState as? AudioPreviewState.Error
        assertNotNull("Must transition to Error state when synthesis fails", errorState)
        assertEquals("corrupted_voice", errorState?.voiceId)
        assertTrue(errorState?.message?.contains("ONNX Inference OOM Failure") == true)
        assertFalse(engine.isPlaying)
        assertEquals(0, player.playCalls.get())
    }

    @Test
    fun testConcurrencyAndThreadSafety() {
        val engine = AudioPreviewEngine(
            cache = cache,
            audioPlayer = player,
            customSynthesizer = { voice, _ ->
                shortArrayOf(voice.id.length.toShort())
            },
            dispatcher = Dispatchers.Unconfined
        )

        val threadCount = 10
        val iterationsPerThread = 50
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            Thread({
                try {
                    for (i in 0 until iterationsPerThread) {
                        val voice = VoiceInfo(id = "voice_${t}_$i", displayName = "Voice $t $i")
                        when (i % 3) {
                            0 -> engine.play(voice)
                            1 -> engine.toggle(voice)
                            2 -> engine.stop()
                        }
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }).start()
        }

        assertTrue("Concurrent operations timed out", latch.await(5, TimeUnit.SECONDS))
        assertEquals("No concurrency errors or deadlocks allowed", 0, errors.get())

        // Final cleanup
        engine.release()
        assertEquals(AudioPreviewState.Idle, engine.currentState)
    }
}
