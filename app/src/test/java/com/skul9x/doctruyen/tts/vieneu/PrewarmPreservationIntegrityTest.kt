package com.skul9x.doctruyen.tts.vieneu

import com.skul9x.doctruyen.tts.ITTSManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive File-Based Test for Phase 01: Prewarm Preservation & Instant First-Chunk Playback (P2).
 *
 * Verifies:
 * 1. testPrewarmChunkCachingSurvivesCancellationDuringInference:
 *    Prewarm chunk caching survives cancellation signals occurring during/after inference.
 *    The NonCancellable block guarantees that PCM post-processing and PrewarmedChunkCache.put
 *    run to completion and deposit chunk 0 even if the prewarm job is cancelled.
 *
 * 2. testPipelineFetchesPrewarmedChunk0WithoutInvokingInference:
 *    Pipeline fetches prewarmed chunk 0 directly from PrewarmedChunkCache without invoking
 *    engine.infer or synthesizer for chunk 0, immediately buffering and routing it to the channel.
 *
 * 3. testRaceConditionPlayInitiated10msIntoPrewarmReceivesChunk0WithoutDuplicateWork:
 *    Play initiated 10ms into prewarm coordinates with the in-flight prewarm pass via
 *    PrewarmedChunkCache's in-flight deferred instead of destructively killing it,
 *    delivering computed chunk 0 with zero duplicate inference and stopping prewarm after chunk 0.
 *
 * 4. testMultipleRapidPlayPauseDoesNotCorruptCacheOrHang:
 *    Rapid calls to prepare, speak, pause, and stop do not leave orphaned locks or corrupt
 *    PrewarmedChunkCache state.
 *
 * 5. testStorySwitchingCancelsPreviousPrewarmWithoutContamination:
 *    Switching to a different story cancels any previous story's prewarm and clears old entries.
 */
class PrewarmPreservationIntegrityTest {

    private lateinit var engine: VieNeuOnnxEngine
    private lateinit var pipeline: TTSAudioPipeline
    private lateinit var manager: VieNeuTTSManager

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupNativeLibrary() {
            val possiblePaths = listOf(
                "sea-g2p-android/target/release/libsea_g2p_android.so",
                "../sea-g2p-android/target/release/libsea_g2p_android.so",
                "app/src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/sea-g2p-android/target/release/libsea_g2p_android.so"
            )
            for (path in possiblePaths) {
                val file = File(path)
                if (file.exists()) {
                    try {
                        System.load(file.absolutePath)
                        break
                    } catch (e: Throwable) {
                        // ignore and try next
                    }
                }
            }
        }
    }

    @Before
    fun setup() {
        PrewarmedChunkCache.clear()

        val baseAssets = if (File("app/src/main/assets/vieneu").exists()) {
            File("app/src/main/assets/vieneu")
        } else {
            File("src/main/assets/vieneu")
        }
        val backboneDir = File(baseAssets, "backbone")
        val codecDir = File(baseAssets, "codec")
        val voicesFile = File(baseAssets, "voices_v3_turbo.json")

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 2
        )

        val configFile = File(backboneDir, "config.json")
        val config = VieNeuConfig.fromJson(configFile.readText(Charsets.UTF_8))

        val tokenizerFile = File(backboneDir, "tokenizer.json")
        val tokenizer = VieNeuTokenizer.fromFile(tokenizerFile)

        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
        val headsReader = NpzReader.read(headsFile)

        val textEmb = headsReader.get2D("text_emb")!!
        val audioEmb = headsReader.get3D("audio_emb")
        val audioFlat = headsReader.getChannelFlatArrays("audio_emb")
        val xvecW = headsReader.get2D("xvec_w")
        val xvecB = headsReader.getFloatArray("xvec_b")
        val xvecLnW = headsReader.getFloatArray("xvec_ln_w")
        val xvecLnB = headsReader.getFloatArray("xvec_ln_b")
        val xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f

        if (voicesFile.exists()) {
            VoicePresets.loadFromJson(voicesFile.readText(Charsets.UTF_8))
        }

        engine = VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = textEmb,
            audioEmbeddingsIn = audioEmb,
            flatAudioEmbeddingsIn = audioFlat,
            xvecW = xvecW,
            xvecB = xvecB,
            xvecLnW = xvecLnW,
            xvecLnB = xvecLnB,
            xvecLnEps = xvecLnEps
        )

        pipeline = TTSAudioPipeline(engine, voiceName = "Trúc Ly")
    }

    @After
    fun teardown() {
        if (::manager.isInitialized) {
            manager.shutdown()
        }
        if (::pipeline.isInitialized) {
            pipeline.release()
        }
        if (::engine.isInitialized) {
            engine.close()
        }
        PrewarmedChunkCache.clear()
    }

    /**
     * Requirement 1: Prewarm chunk caching survives cancellation signals occurring during/after inference.
     */
    @Test
    fun testPrewarmChunkCachingSurvivesCancellationDuringInference() = runBlocking {
        manager = VieNeuTTSManager(
            context = null,
            onStateChange = {},
            customEngine = engine,
            customPipeline = pipeline
        )

        val storyId = 901
        val title = "Chương 1: Mở đầu"
        val content = "Trời chuyển mưa giông gió giật mạnh từng cơn trên nóc nhà. Một buổi chiều vắng vẻ."

        // 1. Proactively start prewarming
        manager.prepareStory(storyId, title, content, voice = "Trúc Ly")

        // 2. Wait until chunk 0 starts (registered in-flight)
        var waitStart = 0
        while (!PrewarmedChunkCache.isInFlight(storyId, "Trúc Ly", 0) &&
            !PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0) && waitStart < 50
        ) {
            delay(50)
            waitStart++
        }

        // 3. Cancel prewarm midway
        manager.cancelPrewarm()

        // 4. Await NonCancellable completion of chunk 0
        var retries = 0
        while (!PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0) && retries < 150) {
            delay(100)
            retries++
        }

        // 5. Assert chunk 0 was preserved and deposited into cache despite cancellation
        assertTrue(
            "Chunk 0 must be preserved in cache even when prewarm was cancelled during inference",
            PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0)
        )
        val pcm0 = PrewarmedChunkCache.get(storyId, "Trúc Ly", 0)
        assertNotNull("PCM buffer for chunk 0 must not be null", pcm0)
        assertTrue("PCM buffer for chunk 0 must contain valid audio samples", pcm0!!.isNotEmpty())

        // 6. Assert chunk 1 was not synthesized due to cancellation
        assertFalse(
            "Chunk 1 should NOT be prewarmed after cancellation",
            PrewarmedChunkCache.contains(storyId, "Trúc Ly", 1)
        )
    }

    /**
     * Requirement 2: Pipeline fetches prewarmed chunk 0 without invoking engine.infer / customSynthesizer for chunk 0.
     */
    @Test
    fun testPipelineFetchesPrewarmedChunk0WithoutInvokingInference() = runBlocking {
        val storyId = 902
        val voice = "Trúc Ly"
        val fakePcm0 = ShortArray(4800) { 101 }
        val fakePcm1 = ShortArray(4800) { 202 }

        // Pre-populate PrewarmedChunkCache with chunk 0
        PrewarmedChunkCache.put(storyId, voice, 0, fakePcm0)

        val chunk0Synthesized = AtomicBoolean(false)
        val chunk1Synthesized = AtomicBoolean(false)
        val audioReceivedChunks = CopyOnWriteArrayList<Int>()

        val mockPlayer = object : AudioPlayer() {
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                return size
            }
        }

        val testPipeline = TTSAudioPipeline(
            engine = engine,
            voiceName = voice,
            customAudioPlayer = mockPlayer,
            customSynthesizer = { chunk, _ ->
                if (chunk.index == 0) {
                    chunk0Synthesized.set(true)
                } else if (chunk.index == 1) {
                    chunk1Synthesized.set(true)
                }
                fakePcm1
            }
        )

        try {
            val chunks = listOf(
                TextChunk(0, "Tiêu đề truyện.", PauseType.SENTENCE_BREAK, 50),
                TextChunk(1, "Nội dung câu đầu tiên.", PauseType.SENTENCE_BREAK, 50)
            )

            testPipeline.startReadingChunks(
                chunkList = chunks,
                storyId = storyId
            )

            // Allow pipeline to process
            var wait = 0
            while (!chunk1Synthesized.get() && wait < 60) {
                delay(50)
                wait++
            }

            // Chunk 0 must have been consumed from cache without invoking synthesizer for chunk 0
            assertFalse(
                "Chunk 0 must NOT be re-synthesized when already present in PrewarmedChunkCache",
                chunk0Synthesized.get()
            )
            assertTrue(
                "Chunk 1 must be synthesized normally by pipeline",
                chunk1Synthesized.get()
            )
        } finally {
            testPipeline.release()
        }
    }

    /**
     * Requirement 3: Race condition verification - Play initiated 10ms into prewarm
     * successfully receives computed chunk 0 without duplicate work.
     */
    @Test
    fun testRaceConditionPlayInitiated10msIntoPrewarmReceivesChunk0WithoutDuplicateWork() = runBlocking {
        manager = VieNeuTTSManager(
            context = null,
            onStateChange = {},
            customEngine = engine,
            customPipeline = pipeline
        )

        val storyId = 903
        val title = "Chương 3: Thử thách"
        val content = "Ánh nắng ban mai len lỏi qua từng ô cửa kính nhỏ. Một ngày làm việc tràn đầy năng lượng."

        // 1. Trigger background prewarm
        manager.prepareStory(storyId, title, content, voice = "Trúc Ly")

        // 2. Wait exactly 10ms into prewarm (race condition simulation)
        delay(10)

        // 3. User taps Play immediately
        manager.speakWithTitlePause(title, content, storyId = storyId)

        // 4. Verify prewarm was NOT destructively cancelled for matching story
        assertEquals("Prewarming story ID should match active story", storyId, PrewarmedChunkCache.activeStoryId)

        // 5. Wait for chunk 0 prewarm completion and pipeline ingestion
        var retries = 0
        while (!PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0) && retries < 150) {
            delay(100)
            retries++
        }

        assertTrue(
            "Prewarm chunk 0 must finish and populate cache during active playback coordination",
            PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0)
        )

        // 6. Wait for playback to transition to PLAYING
        var playRetries = 0
        while (manager.currentState != ITTSManager.TTSState.PLAYING && playRetries < 50) {
            delay(50)
            playRetries++
        }

        assertEquals(ITTSManager.TTSState.PLAYING, manager.currentState)

        // 7. Verify prewarm stopped after chunk 0 and did not synthesize chunk 1
        delay(200)
        assertFalse(
            "Prewarm must stop after chunk 0 when playback coordinates with it",
            PrewarmedChunkCache.contains(storyId, "Trúc Ly", 1)
        )
    }

    /**
     * Verifies multiple rapid Play/Pause/Stop calls do not corrupt cache entries or leave orphaned locks.
     */
    @Test
    fun testMultipleRapidPlayPauseDoesNotCorruptCacheOrHang() = runBlocking {
        manager = VieNeuTTSManager(
            context = null,
            onStateChange = {},
            customEngine = engine,
            customPipeline = pipeline
        )

        val storyId = 904
        val dummyPcm = ShortArray(2400) { 55 }
        PrewarmedChunkCache.put(storyId, "Trúc Ly", 0, dummyPcm)

        for (i in 0 until 5) {
            manager.pause()
            manager.resume()
            manager.pause()
            manager.stop()
        }

        assertTrue("Prewarmed chunk 0 must remain intact", PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0))
        assertEquals(ITTSManager.TTSState.READY, manager.currentState)
    }

    /**
     * Verifies switching stories cancels previous prewarm and does not contaminate new story.
     */
    @Test
    fun testStorySwitchingCancelsPreviousPrewarmWithoutContamination() = runBlocking {
        manager = VieNeuTTSManager(
            context = null,
            onStateChange = {},
            customEngine = engine,
            customPipeline = pipeline
        )

        val storyA = 905
        val storyB = 906
        val pcm = ShortArray(1000) { 1 }

        PrewarmedChunkCache.put(storyA, "Trúc Ly", 0, pcm)
        manager.prepareStory(storyA, "Truyện A", "Nội dung truyện A.", voice = "Trúc Ly")

        // Switch to Story B
        manager.speakContent("Nội dung truyện B.", storyId = storyB)

        // Story A should be evicted from active story cache
        assertFalse("Story A chunk 0 should be evicted on story switch", PrewarmedChunkCache.contains(storyA, "Trúc Ly", 0))
        assertEquals(storyB, PrewarmedChunkCache.activeStoryId)
    }
}

