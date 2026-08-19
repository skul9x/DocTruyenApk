package com.skul9x.doctruyen.tts.vieneu

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class TTSAudioPipelineJvmTest {

    private lateinit var engine: VieNeuOnnxEngine
    private lateinit var pipeline: TTSAudioPipeline

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
        pipeline.release()
        engine.close()
    }

    @Test
    fun testPipelineInitialState() {
        assertEquals(TTSAudioPipeline.PipelineState.Idle, pipeline.currentState)
        assertEquals(0, pipeline.currentChunkIndex)
        assertEquals(0, pipeline.totalChunks)
        assertEquals(0f, pipeline.progress, 0.001f)
    }

    @Test
    fun testPipelineControls() = runBlocking {
        val states = mutableListOf<TTSAudioPipeline.PipelineState>()
        val text = "Xin chào. Tôi là trợ lý ảo."

        pipeline.startReading(text) { state ->
            states.add(state)
        }

        assertEquals(2, pipeline.totalChunks)
        assertTrue(
            pipeline.currentState is TTSAudioPipeline.PipelineState.Synthesizing ||
            pipeline.currentState is TTSAudioPipeline.PipelineState.Playing ||
            pipeline.currentState is TTSAudioPipeline.PipelineState.Idle
        )

        delay(100)
        pipeline.pause()
        assertEquals(TTSAudioPipeline.PipelineState.Paused, pipeline.currentState)

        pipeline.resume()
        assertTrue(
            pipeline.currentState is TTSAudioPipeline.PipelineState.Synthesizing ||
            pipeline.currentState is TTSAudioPipeline.PipelineState.Playing ||
            pipeline.currentState is TTSAudioPipeline.PipelineState.Paused ||
            pipeline.currentState is TTSAudioPipeline.PipelineState.Idle
        )

        pipeline.stop()
        assertEquals(TTSAudioPipeline.PipelineState.Idle, pipeline.currentState)
        assertEquals(0, pipeline.currentChunkIndex)
    }

    @Test
    fun testPipelineSeek() {
        pipeline.startReading("Câu một. Câu hai. Câu ba. Câu bốn.")
        assertEquals(4, pipeline.totalChunks)

        pipeline.seekToChunk(2)
        assertEquals(2, pipeline.currentChunkIndex)
        assertEquals(0.5f, pipeline.progress, 0.001f)

        pipeline.stop()
    }
}
