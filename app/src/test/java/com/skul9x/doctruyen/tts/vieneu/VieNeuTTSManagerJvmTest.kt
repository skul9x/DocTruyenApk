package com.skul9x.doctruyen.tts.vieneu

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class VieNeuTTSManagerJvmTest {

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
    }

    @Test
    fun testManagerInitializationAndVoices() {
        val states = mutableListOf<VieNeuTTSManager.TTSState>()
        manager = VieNeuTTSManager(
            context = null,
            onStateChange = { states.add(it) },
            customEngine = engine,
            customPipeline = pipeline
        )

        assertTrue(states.contains(VieNeuTTSManager.TTSState.INITIALIZING))
        assertTrue(states.contains(VieNeuTTSManager.TTSState.READY))
        assertEquals(VieNeuTTSManager.TTSState.READY, manager.currentState)

        val voices = manager.getAvailableVoices()
        assertTrue("Voices list should not be empty", voices.isNotEmpty())
        assertTrue("Should contain Trúc Ly", voices.contains("Trúc Ly"))

        manager.setVoice("Minh Đức")
        assertEquals("Minh Đức", manager.voiceName)
    }

    @Test
    fun testManagerPlaybackControls() = runBlocking {
        val states = mutableListOf<VieNeuTTSManager.TTSState>()
        val progressList = mutableListOf<Int>()
        manager = VieNeuTTSManager(
            context = null,
            onStateChange = { states.add(it) },
            onProgressChange = { progressList.add(it) },
            customEngine = engine,
            customPipeline = pipeline
        )

        // Start speaking
        manager.speakContent("Xin chào bạn.")
        assertEquals(VieNeuTTSManager.TTSState.PLAYING, manager.currentState)
        assertTrue(manager.isPlaying())
        assertFalse(manager.isPaused())

        delay(50)
        // Pause
        manager.pause()
        assertEquals(VieNeuTTSManager.TTSState.PAUSED, manager.currentState)
        assertTrue(manager.isPaused())
        assertFalse(manager.isPlaying())

        delay(50)
        // Resume
        manager.resume()
        assertEquals(VieNeuTTSManager.TTSState.PLAYING, manager.currentState)

        delay(50)
        // Stop
        manager.stop()
        assertEquals(VieNeuTTSManager.TTSState.READY, manager.currentState)
        assertFalse(manager.isPlaying())
        assertFalse(manager.isPaused())
    }

    @Test
    fun testSpeakWithTitlePauseControls() = runBlocking {
        val states = mutableListOf<VieNeuTTSManager.TTSState>()
        val progressList = mutableListOf<Int>()
        manager = VieNeuTTSManager(
            context = null,
            onStateChange = { states.add(it) },
            onProgressChange = { progressList.add(it) },
            customEngine = engine,
            customPipeline = pipeline
        )

        manager.speakWithTitlePause(
            title = "Chương Một",
            content = "Ngày xửa ngày xưa ở một ngôi làng nhỏ."
        )

        assertEquals(VieNeuTTSManager.TTSState.PLAYING, manager.currentState)
        assertTrue(manager.isPlaying())

        delay(50)
        manager.stop()
        assertEquals(VieNeuTTSManager.TTSState.READY, manager.currentState)
    }
}
