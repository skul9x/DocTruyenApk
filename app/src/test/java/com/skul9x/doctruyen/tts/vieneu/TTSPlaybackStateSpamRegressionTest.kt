package com.skul9x.doctruyen.tts.vieneu

import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.utils.DebugLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Collections

/**
 * End-to-end regression test to guarantee that state spamming is completely eliminated
 * under multi-chunk story synthesis and playback scenarios.
 */
class TTSPlaybackStateSpamRegressionTest {

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
        }
        if (::pipeline.isInitialized) {
            pipeline.release()
        }
        if (::engine.isInitialized) {
            engine.close()
        }
    }

    @Test
    fun testMultiChunkStoryNarration_strictlyBoundedStateTransitionsAndAccurateProgress() = runBlocking {
        val stateHistory = Collections.synchronizedList(mutableListOf<ITTSManager.TTSState>())
        val progressHistory = Collections.synchronizedList(mutableListOf<Int>())

        manager = VieNeuTTSManager(
            context = null,
            onStateChange = { state ->
                stateHistory.add(state)
            },
            onProgressChange = { progress ->
                progressHistory.add(progress)
            },
            customEngine = engine,
            customPipeline = pipeline
        )

        // Initialization emitted INITIALIZING -> READY
        assertEquals(ITTSManager.TTSState.READY, manager.currentState)
        val initialStates = synchronized(stateHistory) { stateHistory.toList() }
        assertEquals(listOf(ITTSManager.TTSState.INITIALIZING, ITTSManager.TTSState.READY), initialStates)

        // Clear history before narration session to measure only story playback lifecycle
        stateHistory.clear()
        progressHistory.clear()

        // 10-chunk story text
        val title = "Chương 1: Khởi Đầu"
        val content = """
            Ngày xửa ngày xưa tại một ngôi làng ven biển thanh bình.
            Mặt trời vừa ló rạng tỏa ánh nắng ấm áp khắp muôn nơi.
            Tiếng sóng vỗ rì rào hòa cùng tiếng chim hót líu lo.
            Một cậu bé tên là Nam thức dậy từ rất sớm.
            Cậu chuẩn bị hành trang để bắt đầu cuộc hành trình mới.
            Con đường phía trước còn rất nhiều điều bí ẩn đón chờ.
            Gió biển mát rượi thổi qua từng rặng phi lao.
            Mọi người trong làng đều gửi lời chúc bình an đến cậu.
            Nam mỉm cười tự tin bước đi trên con đường làng quen thuộc.
        """.trimIndent()

        manager.speakWithTitlePause(title = title, content = content, storyId = 101)

        // Verify it entered PLAYING state
        assertEquals(ITTSManager.TTSState.PLAYING, manager.currentState)
        assertTrue(manager.isPlaying())

        // Wait for synthesis & playback of chunks or completion
        var elapsed = 0
        while (elapsed < 20000 && manager.currentState == ITTSManager.TTSState.PLAYING && progressHistory.lastOrNull() != 100) {
            delay(100)
            elapsed += 100
        }

        // Stop manager if still playing
        if (manager.isPlaying()) {
            manager.stop()
        }

        val capturedStates = synchronized(stateHistory) { stateHistory.toList() }
        val capturedProgress = synchronized(progressHistory) { progressHistory.toList() }

        println("END-TO-END CAPTURED STATES: $capturedStates")
        println("END-TO-END CAPTURED PROGRESS: $capturedProgress")

        // 1. Verify bounded state changes: PLAYING -> READY (at most 2 transitions during narration)
        assertTrue("State transitions during narration must be strictly bounded <= 3", capturedStates.size <= 3)
        assertEquals("First transition must be PLAYING", ITTSManager.TTSState.PLAYING, capturedStates.first())
        assertEquals("Final state must be READY", ITTSManager.TTSState.READY, capturedStates.last())

        // 2. Verify no consecutive duplicate states exist
        for (i in 0 until capturedStates.size - 1) {
            assertNotEquals(
                "Found consecutive duplicate state at index $i: ${capturedStates[i]} == ${capturedStates[i+1]}",
                capturedStates[i],
                capturedStates[i + 1]
            )
        }

        // 3. Verify progress callbacks accurately reported progress
        assertTrue("Progress callbacks must be received", capturedProgress.isNotEmpty())
        assertTrue("Progress should start near 0%", capturedProgress.first() <= 20)

        // 4. Verify pause -> resume sequence produces exactly PAUSED -> PLAYING without duplicate spam
        stateHistory.clear()
        manager.speakContent("Một đoạn văn bản ngắn để kiểm tra tạm dừng.")
        assertEquals(ITTSManager.TTSState.PLAYING, manager.currentState)

        delay(100)
        manager.pause()
        assertEquals(ITTSManager.TTSState.PAUSED, manager.currentState)

        delay(100)
        manager.resume()
        assertEquals(ITTSManager.TTSState.PLAYING, manager.currentState)

        delay(100)
        manager.stop()
        assertEquals(ITTSManager.TTSState.READY, manager.currentState)

        val pauseResumeStates = synchronized(stateHistory) { stateHistory.toList() }
        println("PAUSE/RESUME CAPTURED STATES: $pauseResumeStates")
        assertEquals(
            "Pause/Resume sequence should produce exact expected states",
            listOf(
                ITTSManager.TTSState.PLAYING,
                ITTSManager.TTSState.PAUSED,
                ITTSManager.TTSState.PLAYING,
                ITTSManager.TTSState.READY
            ),
            pauseResumeStates
        )
    }
}
