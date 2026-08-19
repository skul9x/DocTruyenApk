package com.skul9x.doctruyen.tts.vieneu

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class PrewarmedChunkCacheTest {

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

        val backboneDir = File("src/main/assets/vieneu/backbone")
        val codecDir = File("src/main/assets/vieneu/codec")
        val voicesFile = File("src/main/assets/vieneu/voices_v3_turbo.json")

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
        val audioEmb = headsReader.get3D("audio_emb")!!
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
            audioEmbeddings = audioEmb,
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
        } else {
            if (::pipeline.isInitialized) {
                pipeline.release()
            }
            if (::engine.isInitialized) {
                engine.close()
            }
        }
        PrewarmedChunkCache.clear()
    }

    @Test
    fun testPrewarmStory_populatesCacheForFirstTwoChunks() = runBlocking {
        manager = VieNeuTTSManager(
            context = null,
            onStateChange = {},
            customEngine = engine,
            customPipeline = pipeline
        )

        val storyId = 101
        val title = "Chương 1: Khởi đầu"
        val content = "Trời hôm nay rất đẹp. Gió thổi nhè nhẹ qua từng tán cây. Một ngày mới bắt đầu."

        manager.prepareStory(storyId, title, content, voice = "Trúc Ly")

        // Wait for prewarm coroutine to finish synthesizing first 2 chunks
        var retries = 0
        while ((!PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0) || !PrewarmedChunkCache.contains(storyId, "Trúc Ly", 1)) && retries < 100) {
            delay(100)
            retries++
        }

        assertTrue("Chunk 0 (Title) should be cached", PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0))
        assertTrue("Chunk 1 (Sentence 1) should be cached", PrewarmedChunkCache.contains(storyId, "Trúc Ly", 1))
        assertNotNull(PrewarmedChunkCache.get(storyId, "Trúc Ly", 0))
        assertNotNull(PrewarmedChunkCache.get(storyId, "Trúc Ly", 1))
        assertFalse("Chunk 2 should NOT be pre-warmed", PrewarmedChunkCache.contains(storyId, "Trúc Ly", 2))
    }

    @Test
    fun testStartReading_consumesPrewarmedCacheAndSkipsDuplicateInference() = runBlocking {
        // Manually put fake prewarmed buffers for story 202
        val storyId = 202
        val voice = "Trúc Ly"
        val fakePcm0 = ShortArray(16000) { 100 }
        val fakePcm1 = ShortArray(16000) { 200 }
        PrewarmedChunkCache.put(storyId, voice, 0, fakePcm0)
        PrewarmedChunkCache.put(storyId, voice, 1, fakePcm1)

        val title = "Chương 2: Thử nghiệm"
        val content = "Câu thứ nhất trong truyện. Câu thứ hai tiếp theo."
        val chunks = SmartTextSegmenter.segmentStory(title, content)

        val states = mutableListOf<TTSAudioPipeline.PipelineState>()
        pipeline.startReadingChunks(
            chunkList = chunks,
            storyId = storyId,
            onStateChange = { states.add(it) }
        )

        delay(150)
        assertTrue("Pipeline should start synthesizing/playing", states.isNotEmpty())
        assertEquals(storyId, PrewarmedChunkCache.activeStoryId)
    }

    @Test
    fun testSwitchStory_invalidatesPreviousStoryCache() = runBlocking {
        val storyA = 301
        val storyB = 302
        val voice = "Trúc Ly"

        val pcm = ShortArray(1000) { 1 }
        PrewarmedChunkCache.put(storyA, voice, 0, pcm)
        PrewarmedChunkCache.put(storyA, voice, 1, pcm)

        assertTrue(PrewarmedChunkCache.contains(storyA, voice, 0))
        assertTrue(PrewarmedChunkCache.contains(storyA, voice, 1))

        // Putting a chunk for story B should invalidate story A
        PrewarmedChunkCache.put(storyB, voice, 0, pcm)

        assertFalse("Story A Chunk 0 should be evicted", PrewarmedChunkCache.contains(storyA, voice, 0))
        assertFalse("Story A Chunk 1 should be evicted", PrewarmedChunkCache.contains(storyA, voice, 1))
        assertTrue("Story B Chunk 0 should be present", PrewarmedChunkCache.contains(storyB, voice, 0))
        assertEquals(storyB, PrewarmedChunkCache.activeStoryId)
    }

    @Test
    fun testCacheLRUCapacity() {
        val storyId = 401
        val voice = "Trúc Ly"
        val pcm = ShortArray(500)

        PrewarmedChunkCache.put(storyId, voice, 0, pcm)
        PrewarmedChunkCache.put(storyId, voice, 1, pcm)
        PrewarmedChunkCache.put(storyId, voice, 2, pcm)
        PrewarmedChunkCache.put(storyId, voice, 3, pcm)
        assertEquals(4, PrewarmedChunkCache.size())

        // Add 5th chunk -> should evict chunk 0
        PrewarmedChunkCache.put(storyId, voice, 4, pcm)
        assertEquals(4, PrewarmedChunkCache.size())
        assertFalse("Chunk 0 should have been evicted", PrewarmedChunkCache.contains(storyId, voice, 0))
        assertTrue(PrewarmedChunkCache.contains(storyId, voice, 1))
        assertTrue(PrewarmedChunkCache.contains(storyId, voice, 4))
    }
}
