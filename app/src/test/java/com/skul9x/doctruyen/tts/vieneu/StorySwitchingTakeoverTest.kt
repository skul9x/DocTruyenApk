package com.skul9x.doctruyen.tts.vieneu

import com.skul9x.doctruyen.tts.ITTSManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class StorySwitchingTakeoverTest {

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
    fun testInstantTakeover_storyAToStoryB() = runBlocking {
        val states = mutableListOf<ITTSManager.TTSState>()
        manager = VieNeuTTSManager(
            context = null,
            onStateChange = { states.add(it) },
            customEngine = engine,
            customPipeline = pipeline
        )

        // 1. Start reading Story 1 (ID: 101)
        val story1Id = 101
        val story1Title = "Story 1"
        val story1Content = "Nội dung câu chuyện số một rất dài để kiểm tra đọc liên tục."
        manager.speakWithTitlePause(story1Title, story1Content, storyId = story1Id)

        delay(100)
        assertEquals(ITTSManager.TTSState.PLAYING, manager.currentState)

        // 2. Abort & switch to Story 2 (ID: 102)
        val story2Id = 102
        val story2Title = "Story 2"
        val story2Content = "Nội dung câu chuyện số hai bắt đầu ngay lập tức."

        // Stop previous story immediately & start Story 2
        manager.stop()
        assertEquals(ITTSManager.TTSState.READY, manager.currentState)

        manager.speakWithTitlePause(story2Title, story2Content, storyId = story2Id)
        delay(100)

        // 3. Assertions
        assertEquals(ITTSManager.TTSState.PLAYING, manager.currentState)
    }
}
