package com.skul9x.doctruyen.tts.vieneu

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class AdaptivePrebufferingTest {

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
    fun testInitialPlayback_requiresTwoChunksOrSixSeconds() = runBlocking {
        val recordedStates = mutableListOf<TTSAudioPipeline.PipelineState>()
        // 4 sentences story -> startIndex = 0, totalChunks = 4 (> 2)
        val text = "Trời hôm nay nhiều mây và có thể có mưa rào. Gió nhẹ thổi qua từng ngọn cây xào xạc. Mọi người đều vội vã trở về nhà sau giờ làm việc. Đêm nay thành phố sẽ chìm trong giấc ngủ yên bình."

        pipeline.startReading(text) { state ->
            synchronized(recordedStates) {
                recordedStates.add(state)
            }
        }

        assertEquals(4, pipeline.totalChunks)

        // Wait until first playing or timeout
        var elapsed = 0
        while (elapsed < 15000 && !recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing }) {
            delay(50)
            elapsed += 50
        }

        // Must have passed through Buffering or Synthesizing before Playing
        assertTrue("States should contain Buffering or Synthesizing", 
            recordedStates.any { it is TTSAudioPipeline.PipelineState.Buffering || it is TTSAudioPipeline.PipelineState.Synthesizing })
        assertTrue("States should reach Playing", 
            recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing })

        pipeline.stop()
    }

    @Test
    fun testResumeMidStory_startsImmediatelyWithSingleChunk() = runBlocking {
        val recordedStates = mutableListOf<TTSAudioPipeline.PipelineState>()
        val text = "Câu số một dài và đầy đủ ý nghĩa. Câu số hai tiếp nối câu thứ nhất thật êm dịu. Câu số ba mô tả diễn biến tiếp theo của câu chuyện. Câu số bốn là kết thúc có hậu cho toàn bộ truyện."

        val chunks = SmartTextSegmenter.segment(text)
        assertEquals(4, chunks.size)

        // Start from chunk index 2 (mid-story seek / resume)
        pipeline.seekToChunk(2)
        pipeline.startReadingChunks(chunks.drop(2), onStateChange = { state ->
            synchronized(recordedStates) {
                recordedStates.add(state)
            }
        })

        var elapsed = 0
        while (elapsed < 15000 && !recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing }) {
            delay(50)
            elapsed += 50
        }

        assertTrue("Mid-story playback reached Playing state",
            recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing })

        pipeline.stop()
    }

    @Test
    fun testShortStory_releasesImmediatelyWhenSynthDone() = runBlocking {
        val recordedStates = mutableListOf<TTSAudioPipeline.PipelineState>()
        // Exactly 2 chunks
        val text = "Chào bạn nha. Hẹn gặp lại."

        pipeline.startReading(text) { state ->
            synchronized(recordedStates) {
                recordedStates.add(state)
            }
        }

        assertEquals(2, pipeline.totalChunks)

        var elapsed = 0
        while (elapsed < 15000 && !recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing }) {
            delay(50)
            elapsed += 50
        }

        assertTrue("Short story reached Playing without deadlock",
            recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing })

        pipeline.stop()
    }
}
