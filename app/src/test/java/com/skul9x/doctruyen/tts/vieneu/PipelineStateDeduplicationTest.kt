package com.skul9x.doctruyen.tts.vieneu

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Collections

class PipelineStateDeduplicationTest {

    private lateinit var engine: VieNeuOnnxEngine
    private lateinit var pipeline: TTSAudioPipeline

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupNativeLibrary() {
            val possiblePaths = listOf(
                "sea-g2p-android/target/release/libsea_g2p_android.so",
                "../sea-g2p-android/target/release/libsea_g2p_android.so",
                "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/sea-g2p-android/target/release/libsea_g2p_android.so",
                "src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "app/src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "../app/src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/app/src/main/jniLibs/x86_64/libsea_g2p_android.so"
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
    fun testPrebufferingAndPlayback_noConsecutiveDuplicateStateEmissions() = runBlocking {
        val stateHistory = Collections.synchronizedList(mutableListOf<TTSAudioPipeline.PipelineState>())

        val text = "Trời hôm nay nhiều mây và có thể có mưa rào rải rác. Gió nhẹ thổi qua từng hàng cây xanh mướt. Mọi người đều vội vã trở về nhà sau giờ làm việc căng thẳng. Đêm nay thành phố sẽ chìm trong giấc ngủ yên bình."

        pipeline.startReading(text) { state ->
            stateHistory.add(state)
        }

        // Wait until playback reaches Playing or finishes
        var elapsed = 0
        while (elapsed < 15000 && !stateHistory.any { it is TTSAudioPipeline.PipelineState.Playing }) {
            delay(50)
            elapsed += 50
        }

        // Allow some time for playing chunks
        delay(500)
        pipeline.stop()

        val captured = synchronized(stateHistory) { stateHistory.toList() }
        println("CAPTURED STATES: $captured")
        assertTrue("Must have captured state changes: $captured", captured.isNotEmpty())

        // Verify that there are NO consecutive duplicate states emitted
        for (i in 0 until captured.size - 1) {
            assertNotEquals(
                "Found consecutive duplicate state at index $i: ${captured[i]} == ${captured[i+1]}",
                captured[i],
                captured[i + 1]
            )
        }

        // Verify Buffering(0, 2) is emitted at most once (not spammed every 20ms during pre-buffering)
        val initialBufferingCount = captured.count { it == TTSAudioPipeline.PipelineState.Buffering(0, 2) }
        assertTrue("Buffering(0, 2) should be emitted at most 1 time, actual: $initialBufferingCount", initialBufferingCount <= 1)

        // Verify distinct transitions occur in correct order: Synthesizing -> (Buffering)* -> Playing -> Idle
        val stateTypes = captured.map { it.javaClass.simpleName }
        assertTrue("Should include Synthesizing: $stateTypes", stateTypes.contains("Synthesizing"))
        assertTrue("Should include Playing: $stateTypes", stateTypes.contains("Playing"))
        assertTrue("Should end with Idle: $stateTypes", stateTypes.last() == "Idle")
    }
}
