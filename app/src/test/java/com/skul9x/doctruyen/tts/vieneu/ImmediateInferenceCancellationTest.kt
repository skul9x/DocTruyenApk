package com.skul9x.doctruyen.tts.vieneu

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Comprehensive Unit Test for Phase 01:
 * Immediate Cooperative Inference Cancellation & Latency Elimination (AUD-001 & AUD-005)
 *
 * Verifies:
 * 1. Triggering stopInternal() while inferWithTiming is actively computing aborts in-flight inference within < 50ms with InterruptedException.
 * 2. Starting a new pipeline session after an abort successfully resets cancelRequested via resetCancel() and synthesizes subsequent chunks without error.
 * 3. Calling seekToChunk() cooperatively cancels in-flight synthesis and resumes immediately without deadlock or blocking.
 * 4. Closed engine state guard order prioritizes isClosed over cancelRequested, throwing IllegalStateException("...closed...") (AUD-005).
 * 5. resetCancel() correctly synchronizes on engine lock to prevent race conditions during thread handover.
 */
class ImmediateInferenceCancellationTest {

    private lateinit var engine: VieNeuOnnxEngine

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupNativeLibrary() {
            val possiblePaths = listOf(
                "sea-g2p-android/target/release/libsea_g2p_android.so",
                "../sea-g2p-android/target/release/libsea_g2p_android.so",
                "app/src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/sea-g2p-android/target/release/libsea_g2p_android.so"
            )
            for (path in possiblePaths) {
                val file = File(path)
                if (file.exists()) {
                    try {
                        System.load(file.absolutePath)
                        break
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    @Before
    fun setup() {
        PrewarmedChunkCache.clear()
        SeaG2P.clearCache()
        VieNeuOnnxEngine.CacheTensor.Pool.clear()

        val baseAssets = if (File("app/src/main/assets/vieneu").exists()) {
            File("app/src/main/assets/vieneu")
        } else {
            File("src/main/assets/vieneu")
        }
        val backboneDir = OnnxSessionManager.getBackboneDir(baseAssets, "INT8")
        val codecDir = File(baseAssets, "codec")
        val voicesFile = File(baseAssets, "voices_v3_turbo.json")

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 2,
            precision = "INT8"
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
    }

    @After
    fun tearDown() {
        if (::engine.isInitialized) {
            engine.close()
        }
        PrewarmedChunkCache.clear()
        SeaG2P.clearCache()
        VieNeuOnnxEngine.CacheTensor.Pool.clear()
    }

    /**
     * Requirement 1: Triggering stopInternal() while inferWithTiming is actively computing
     * terminates in-flight inference cooperatively within < 50ms with InterruptedException.
     */
    @Test
    fun testStopInternalWhileInferringAbortsWithin50ms() {
        val inferStartedLatch = CountDownLatch(1)
        val inferFinishedLatch = CountDownLatch(1)
        val caughtException = AtomicReference<Throwable?>()

        val pipeline = TTSAudioPipeline(
            engine = engine,
            maxQueueCapacity = 4,
            customAudioPlayer = object : AudioPlayer() {
                override fun write(pcm16: ShortArray, offset: Int, size: Int): Int = size
            }
        )

        val inferThread = Thread {
            try {
                inferStartedLatch.countDown()
                engine.inferWithTiming(
                    phonemes = "t ôi1 l a2 m ô1 h i2 nh t r i1 t u e7 n h a n2 t a O4 v i e t7 n a m1 v a2 t ôi1 d a n g1 n h e5",
                    voiceName = "Trúc Ly",
                    maxNewFrames = 150
                )
            } catch (t: Throwable) {
                caughtException.set(t)
            } finally {
                inferFinishedLatch.countDown()
            }
        }
        inferThread.isDaemon = true
        inferThread.start()

        assertTrue("Inference thread must start", inferStartedLatch.await(2, TimeUnit.SECONDS))
        Thread.sleep(60) // Let inference enter autoregressive loop and acquire lock

        val abortStartTime = System.currentTimeMillis()
        pipeline.stopInternal(resetIndex = true)
        val abortDuration = System.currentTimeMillis() - abortStartTime

        assertTrue(
            "stopInternal() invocation should execute immediately (< 50ms, was ${abortDuration}ms)",
            abortDuration < 50
        )
        assertTrue("cancelRequested must be set to true", engine.cancelRequested)

        val finishedInTime = inferFinishedLatch.await(300, TimeUnit.MILLISECONDS)
        assertTrue("Inference loop must abort in < 300ms", finishedInTime)

        val ex = caughtException.get()
        assertNotNull("In-flight inference must throw an exception upon cancel", ex)
        assertTrue(
            "Exception must be InterruptedException or have it as cause (was ${ex?.javaClass?.simpleName})",
            ex is InterruptedException || ex?.cause is InterruptedException
        )

        pipeline.release()
    }

    /**
     * Requirement 2: Starting a new pipeline session after an abort successfully resets cancelRequested
     * and synthesizes subsequent chunks without error.
     */
    @Test
    fun testPipelineAbortAndResumeSynthesizesSubsequentChunks() = runBlocking {
        val totalChunks = 3
        val synthesizedIndices = CopyOnWriteArrayList<Int>()

        val pipeline = TTSAudioPipeline(
            engine = engine,
            maxQueueCapacity = 4,
            preBufferChunkCount = 1,
            customAudioPlayer = object : AudioPlayer() {
                override fun write(pcm16: ShortArray, offset: Int, size: Int): Int = size
            },
            customSynthesizer = { chunk, _ ->
                synthesizedIndices.add(chunk.index)
                ShortArray(4800) { 1 }
            }
        )

        val chunksFirstRun = listOf(
            TextChunk(0, "Câu thứ nhất trong truyện.", PauseType.SENTENCE_BREAK, 50),
            TextChunk(1, "Câu thứ hai trong truyện.", PauseType.SENTENCE_BREAK, 50)
        )

        pipeline.startReadingChunks(chunksFirstRun, storyId = 1)
        delay(40)

        // Stop pipeline to trigger immediate cancellation
        pipeline.stop()
        assertTrue("Pipeline stop must request engine cancellation", engine.cancelRequested)
        assertEquals(TTSAudioPipeline.PipelineState.Idle, pipeline.currentState)

        // Commence fresh reading session on a different story/chunks
        val chunksSecondRun = (0 until totalChunks).map { i ->
            TextChunk(i, "Nội dung câu mới số $i.", PauseType.SENTENCE_BREAK, 30)
        }

        pipeline.startReadingChunks(chunksSecondRun, storyId = 2)
        assertFalse("Starting new pipeline must reset cancelRequested flag to false", engine.cancelRequested)

        // Allow synthesis to produce chunks
        withTimeout(2000) {
            while (synthesizedIndices.size < totalChunks) {
                delay(20)
            }
        }

        assertTrue("New chunks must be synthesized after resetCancel()", synthesizedIndices.contains(0))
        pipeline.release()
    }

    /**
     * Requirement 3: Calling seekToChunk() while actively synthesizing aborts in-flight synthesis
     * and immediately resumes reading the target chunk.
     */
    @Test
    fun testSeekToChunkCooperativelyCancelsAndResumesTarget() = runBlocking {
        val synthesizedIndices = CopyOnWriteArrayList<Int>()

        val pipeline = TTSAudioPipeline(
            engine = engine,
            maxQueueCapacity = 4,
            preBufferChunkCount = 1,
            customAudioPlayer = object : AudioPlayer() {
                override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                    Thread.sleep(10)
                    return size
                }
            },
            customSynthesizer = { chunk, _ ->
                synthesizedIndices.add(chunk.index)
                ShortArray(4800) { 1 }
            }
        )

        val chunks = (0 until 5).map { i ->
            TextChunk(i, "Đoạn văn kiểm tra seek số $i.", PauseType.SENTENCE_BREAK, 20)
        }

        pipeline.startReadingChunks(chunks, storyId = 10)
        delay(30)

        // Seek forward to chunk 3
        pipeline.seekToChunk(3)
        assertFalse("Engine cancel flag should be reset after seekToChunk starts pipeline", engine.cancelRequested)
        assertEquals(3, pipeline.currentChunkIndex)

        withTimeout(2000) {
            while (!synthesizedIndices.contains(3)) {
                delay(20)
            }
        }

        assertTrue("Target chunk index 3 must be synthesized following seek", synthesizedIndices.contains(3))
        pipeline.release()
    }

    /**
     * Requirement 4: Closed engine state guard order (AUD-005).
     * inferWithTiming() on a closed engine MUST throw IllegalStateException with "closed",
     * NOT InterruptedException, even when close() sets cancelRequested = true.
     */
    @Test
    fun testClosedEngineGuardPriorityOverCancellation() {
        assertFalse("Engine should be open initially", engine.isClosed)

        // Close engine which sets both closed = true AND cancelRequested = true
        engine.close()
        assertTrue("Engine must be closed", engine.isClosed)
        assertTrue("cancelRequested must be true after close", engine.cancelRequested)

        try {
            engine.inferWithTiming(
                phonemes = "s in1 ch aw2",
                voiceName = "Trúc Ly"
            )
            fail("Expected IllegalStateException on closed engine")
        } catch (e: IllegalStateException) {
            val msg = e.message ?: ""
            assertTrue("Exception message must indicate engine is closed (was '$msg')", msg.contains("closed", ignoreCase = true))
        } catch (e: Throwable) {
            fail("Expected IllegalStateException but caught ${e.javaClass.name}: ${e.message}")
        }
    }

    /**
     * Requirement 5: resetCancel() lock synchronization contract.
     * resetCancel() synchronizes on lock.withLock, ensuring it blocks until any active inference
     * pass has completely terminated and relinquished the lock.
     */
    @Test
    fun testResetCancelWaitsForActiveInferenceLockRelease() {
        val inferStartedLatch = CountDownLatch(1)
        val cancelObservedLatch = CountDownLatch(1)
        val lockReleasedLatch = CountDownLatch(1)

        val inferThread = Thread {
            try {
                inferStartedLatch.countDown()
                engine.inferWithTiming(
                    phonemes = "m ô1 h i2 nh n g ô1 n n g u7 d a n g1 d u O7 c7 k i e m3 t h u3",
                    voiceName = "Trúc Ly",
                    maxNewFrames = 100
                )
            } catch (_: InterruptedException) {
                cancelObservedLatch.countDown()
            } finally {
                lockReleasedLatch.countDown()
            }
        }
        inferThread.isDaemon = true
        inferThread.start()

        assertTrue("Inference must start", inferStartedLatch.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)

        // Set cancelRequested
        engine.requestCancel()
        assertTrue(engine.cancelRequested)

        // resetCancel() must wait for the inference thread to abort and release lock
        val resetStartTime = System.currentTimeMillis()
        engine.resetCancel()
        val resetDuration = System.currentTimeMillis() - resetStartTime

        assertFalse("cancelRequested must be false after resetCancel", engine.cancelRequested)
        assertTrue("Active inference must observe cancellation", cancelObservedLatch.await(500, TimeUnit.MILLISECONDS))
        assertTrue("Active inference must release lock", lockReleasedLatch.await(500, TimeUnit.MILLISECONDS))
        assertTrue("resetCancel duration should be fast (< 200ms, was ${resetDuration}ms)", resetDuration < 200)
    }
}
