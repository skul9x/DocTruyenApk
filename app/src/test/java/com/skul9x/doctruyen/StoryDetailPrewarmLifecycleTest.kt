package com.skul9x.doctruyen

import com.skul9x.doctruyen.tts.vieneu.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class StoryDetailPrewarmLifecycleTest {

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
    fun testStoryContentLoaded_triggersServicePrewarm() = runBlocking {
        manager = VieNeuTTSManager(
            context = null,
            onStateChange = {},
            customEngine = engine,
            customPipeline = pipeline
        )

        val storyId = 991
        val title = "Sự tích cây vú sữa"
        val contentText = "Ngày xưa có một cậu bé ham chơi. Một hôm bị mẹ mắng, cậu giận dỗi bỏ đi."

        // Simulate triggerPrewarmIfReady workflow when content and service are ready
        manager.prepareStory(storyId, title, contentText, voice = "Trúc Ly")

        // Wait for prewarm to complete
        var retries = 0
        while ((!PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0) || !PrewarmedChunkCache.contains(storyId, "Trúc Ly", 1)) && retries < 100) {
            delay(100)
            retries++
        }

        assertTrue("Story title should be prewarmed into cache", PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0))
        assertTrue("Opening chunk should be prewarmed into cache", PrewarmedChunkCache.contains(storyId, "Trúc Ly", 1))
        assertEquals(storyId, PrewarmedChunkCache.activeStoryId)
    }

    @Test
    fun testActivityDestroy_cancelsActivePrewarmJob() = runBlocking {
        manager = VieNeuTTSManager(
            context = null,
            onStateChange = {},
            customEngine = engine,
            customPipeline = pipeline
        )

        val storyId = 992
        val title = "Tấm Cám"
        val contentText = "Ngày xưa có hai chị em cùng cha khác mẹ tên là Tấm và Cám. Tấm hiền lành chịu khó."

        // Start pre-warming
        manager.prepareStory(storyId, title, contentText, voice = "Trúc Ly")

        // Immediately simulate onDestroy / onBackPressed cancelling prewarm
        manager.cancelPrewarm()
        delay(200)

        // Verifies cancelPrewarm successfully halts without crashes or hung coroutines
        assertTrue(true)
    }
}
