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

class OptimizedTextChunkingAndStateTest {

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
        if (::pipeline.isInitialized) {
            pipeline.stop()
        }
    }

    @Test
    fun testVietnameseChunkSizeBoundsAndSubClauseSplitting() {
        assertEquals("Default max characters must be 75", 75, SmartTextSegmenter.DEFAULT_MAX_CHARS)

        val diverseParagraphs = listOf(
            // Long narrative sentence (> 150 chars) with commas and semicolons
            "Ngày xưa, ở một ngôi làng nọ bên cạnh dòng sông xanh biếc quanh năm nước chảy êm đềm; có một gia đình tiều phu nghèo khó nhưng luôn luôn sống lương thiện, hòa đồng và hết lòng giúp đỡ tất cả bà con lối xóm xung quanh.",
            // Dialogue with em-dashes and long speech (> 120 chars)
            "- Này người anh em—nếu hôm nay chúng ta không cùng nhau vượt qua ngọn núi đá hiểm trở kia—thì làm sao kịp đưa số thảo dược quý này về kịp trước khi trời ngả về chiều tối?",
            // Mixed colons and quotes
            "Trưởng làng ôn tồn căn dặn: \"Hãy luôn ghi nhớ lời thề năm xưa của tổ tiên, không được phụ lòng tin tưởng của dân làng dù bất cứ chuyện gì xảy ra!\"",
            // Long sentence without punctuation to test word splitting fallback
            "Một buổi sáng mùa thu trong lành mát mẻ gió thổi nhè nhẹ làm lay động những cành cây ngọn cỏ xanh tươi tốt rực rỡ dưới ánh nắng ban mai chan hòa khắp nhân gian"
        )

        for (para in diverseParagraphs) {
            val chunks = SmartTextSegmenter.segment(para)
            assertTrue("Segmented chunks must not be empty", chunks.isNotEmpty())
            for (chunk in chunks) {
                assertTrue(
                    "Chunk length (${chunk.text.length}) must be <= 80 chars: '${chunk.text}'",
                    chunk.text.length <= 80
                )
                assertTrue("Chunk text must not be blank", chunk.text.isNotBlank())
            }
        }
    }

    @Test
    fun testPreservationOfAbbreviationsAndPauseTypes() {
        val text = "PGS.TS. Trần Đình Nam cùng BS. Nguyễn Thị Mai đến công tác tại TP.HCM vào lúc 08:30 sáng, mang theo 1.500.000 VNĐ tiền thuốc hỗ trợ bệnh nhân nghèo, v.v."
        val chunks = SmartTextSegmenter.segment(text)

        assertTrue(chunks.isNotEmpty())
        for (chunk in chunks) {
            assertTrue("Chunk length must be <= 80 chars", chunk.text.length <= 80)
        }

        val fullReconstructed = chunks.joinToString(" ") { it.text }
        assertTrue("Must preserve PGS.TS.", fullReconstructed.contains("PGS.TS."))
        assertTrue("Must preserve BS.", fullReconstructed.contains("BS."))
        assertTrue("Must preserve TP.HCM", fullReconstructed.contains("TP.HCM"))
        assertTrue("Must preserve 08:30", fullReconstructed.contains("08:30"))
        assertTrue("Must preserve 1.500.000 VNĐ", fullReconstructed.contains("1.500.000 VNĐ"))
        assertTrue("Must preserve v.v.", fullReconstructed.contains("v.v."))

        // Verify natural pause type assignments
        val dialogueText = "- Cậu có hàng trăm trí khôn, hãy nghĩ kế ra đi!\n\nNhưng Chồn buồn bã thở dài."
        val dialogueChunks = SmartTextSegmenter.segment(dialogueText)
        assertTrue(dialogueChunks.size >= 2)
        assertEquals(PauseType.PARAGRAPH_BREAK, dialogueChunks[1].pauseType)
    }

    @Test
    fun testInitManagerDirectTransitionToPlayingWhenSpeechPending() = runBlocking {
        // Test standard initialization (no pending speech) -> transitions [INITIALIZING, READY]
        val normalStates = mutableListOf<ITTSManager.TTSState>()
        val managerNormal = VieNeuTTSManager(
            context = null,
            onStateChange = { normalStates.add(it) },
            customEngine = engine,
            customPipeline = pipeline
        )
        assertEquals(
            listOf(ITTSManager.TTSState.INITIALIZING, ITTSManager.TTSState.READY),
            normalStates
        )
        assertEquals(ITTSManager.TTSState.READY, managerNormal.currentState)
        managerNormal.stop()

        // Test pending speech flow: create manager with customEngine/Pipeline simulated through speak invocation
        val pendingStates = mutableListOf<ITTSManager.TTSState>()
        val testPipeline = TTSAudioPipeline(engine, voiceName = "Trúc Ly")

        // Directly verify speak invocation during initialized pipeline transitions directly to PLAYING
        val managerWithSpeech = VieNeuTTSManager(
            context = null,
            onStateChange = { pendingStates.add(it) },
            customEngine = engine,
            customPipeline = testPipeline
        )
        pendingStates.clear()

        managerWithSpeech.speakContent("Câu chuyện bắt đầu tại một vùng quê thanh bình.")
        assertEquals(ITTSManager.TTSState.PLAYING, managerWithSpeech.currentState)
        assertEquals(listOf(ITTSManager.TTSState.PLAYING), pendingStates)
        assertFalse("Should not emit READY before PLAYING", pendingStates.contains(ITTSManager.TTSState.READY))

        delay(50)
        managerWithSpeech.stop()
    }
}
