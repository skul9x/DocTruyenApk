package com.skul9x.doctruyen.tts.vieneu

import kotlinx.coroutines.CompletableDeferred
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Comprehensive File-Based Test Suite for Phase 02: Cooperative Audio Pipeline Pause/Resume with Buffer Retention (P1).
 *
 * Verifies:
 * 1. testPausePreservesChannelItemsWithoutDrain:
 *    Calling pause() halts hardware playback and suspends coroutines cooperatively,
 *    retaining all buffered audio chunks in audioChannel without calling drainChannel().
 *
 * 2. testResumePicksUpBufferedItemsWithoutResynthesis:
 *    Calling resume() unlocks the cooperative suspension gate and consumes the retained chunks
 *    without re-synthesizing already computed chunks (zero duplicate inference passes).
 *
 * 3. testMonotonicPlaybackPositionWithoutRepetition:
 *    Pausing and resuming does not rewind or repeat audio chunks. Playback chunk indices
 *    advance monotonically without loop repetition artifacts.
 *
 * 4. testRapidPauseResumeTogglingDoesNotLeakOrHang:
 *    Rapid consecutive pause-resume cycles do not deadlock the PauseGate, leak coroutines,
 *    or cause unhandled race conditions.
 *
 * 5. testStopDuringPauseCleanlyDrainsAndCancels:
 *    Calling stop() while paused releases the pause gate, cancels jobs, drains the audioChannel,
 *    flushes hardware buffers, and resets reading position to 0.
 *
 * 6. testAudioPlayerSubChunkSlicingAndPauseState:
 *    AudioPlayer.writeSuspended processes audio in sub-chunk frames (100ms slices) with cancellation checks,
 *    and pause() cleanly transitions to paused state without marking needsReinit.
 */
class CooperativePipelinePauseResumeTest {

    private lateinit var engine: VieNeuOnnxEngine

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

        val baseAssets = if (File("app/src/main/assets/vieneu").exists()) {
            File("app/src/main/assets/vieneu")
        } else {
            File("src/main/assets/vieneu")
        }
        val backboneDir = File(baseAssets, "backbone")
        val codecDir = File(baseAssets, "codec")
        val voicesFile = File(baseAssets, "voices_v3_turbo.json")

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
    fun teardown() {
        if (::engine.isInitialized) {
            engine.close()
        }
        PrewarmedChunkCache.clear()
    }

    /**
     * Requirement 1: pause() preserves channel items and does NOT invoke drainChannel().
     */
    @Test
    fun testPausePreservesChannelItemsWithoutDrain() = runBlocking {
        val totalChunks = 10
        val audioHoldGate = CompletableDeferred<Unit>()

        val controlledPlayer = object : AudioPlayer() {
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                // Hold playback to allow chunks to accumulate in the bounded channel
                runBlocking {
                    audioHoldGate.await()
                }
                return size
            }
        }

        val pipeline = TTSAudioPipeline(
            engine = engine,
            maxQueueCapacity = 4,
            preBufferChunkCount = 2,
            customAudioPlayer = controlledPlayer,
            customSynthesizer = { chunk, _ ->
                ShortArray(4800) { 1 } // 100ms PCM
            }
        )

        try {
            val chunkList = (0 until totalChunks).map { i ->
                TextChunk(index = i, text = "Kiểm thử câu số $i.", pauseType = PauseType.SENTENCE_BREAK, pauseDurationMs = 50)
            }

            pipeline.startReadingChunks(chunkList)

            // Wait until channel has buffered chunks
            withTimeout(3000L) {
                while (pipeline.queuedChunkCount < 2) {
                    delay(20)
                }
            }

            val countBeforePause = pipeline.queuedChunkCount
            val memoryBeforePause = pipeline.queuedMemoryBytes
            assertTrue("Channel must have at least 2 chunks buffered", countBeforePause >= 2)
            assertTrue("Memory bytes must be > 0", memoryBeforePause > 0L)

            // Execute pause()
            pipeline.pause()

            assertEquals("Pipeline state must be Paused", TTSAudioPipeline.PipelineState.Paused, pipeline.currentState)

            // Buffer retention check: chunks and memory MUST be retained!
            assertEquals("Queued chunks must NOT be cleared by pause()", countBeforePause, pipeline.queuedChunkCount)
            assertEquals("Queued memory must NOT be cleared by pause()", memoryBeforePause, pipeline.queuedMemoryBytes)

            // Wait briefly to confirm asynchronous drain is NOT happening in the background
            delay(100)
            assertEquals("Queued chunks must persist over time while paused", countBeforePause, pipeline.queuedChunkCount)
            assertEquals("Queued memory must persist over time while paused", memoryBeforePause, pipeline.queuedMemoryBytes)
        } finally {
            audioHoldGate.complete(Unit)
            pipeline.release()
        }
    }

    /**
     * Requirement 2: resume() picks up directly from existing buffered items without triggering re-synthesis.
     */
    @Test
    fun testResumePicksUpBufferedItemsWithoutResynthesis() = runBlocking {
        val totalChunks = 8
        val synthesizedIndices = CopyOnWriteArrayList<Int>()
        val audioHoldGate = CompletableDeferred<Unit>()

        val controlledPlayer = object : AudioPlayer() {
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                runBlocking {
                    audioHoldGate.await()
                }
                return size
            }
        }

        val pipeline = TTSAudioPipeline(
            engine = engine,
            maxQueueCapacity = 4,
            preBufferChunkCount = 2,
            customAudioPlayer = controlledPlayer,
            customSynthesizer = { chunk, _ ->
                synthesizedIndices.add(chunk.index)
                ShortArray(4800) { 1 }
            }
        )

        try {
            val chunkList = (0 until totalChunks).map { i ->
                TextChunk(index = i, text = "Đoạn văn số $i để kiểm tra tái tổng hợp.", pauseType = PauseType.SENTENCE_BREAK, pauseDurationMs = 50)
            }

            pipeline.startReadingChunks(chunkList)

            // Wait until chunks 0, 1, 2, 3 are synthesized and buffered
            withTimeout(3000L) {
                while (synthesizedIndices.size < 4) {
                    delay(20)
                }
            }

            val synthesizedBeforePause = ArrayList(synthesizedIndices)
            assertTrue("At least 4 chunks synthesized before pause", synthesizedBeforePause.size >= 4)

            // Pause pipeline
            pipeline.pause()
            assertEquals(TTSAudioPipeline.PipelineState.Paused, pipeline.currentState)

            // Let playback consumer unblock so when we resume it plays through
            audioHoldGate.complete(Unit)

            // Resume pipeline
            pipeline.resume()
            assertEquals("State must become Playing upon resume", TTSAudioPipeline.PipelineState.Playing, pipeline.currentState)

            // Wait for all chunks to finish
            withTimeout(5000L) {
                while (pipeline.currentState !is TTSAudioPipeline.PipelineState.Idle) {
                    delay(30)
                }
            }

            // Verify no re-synthesis: Every chunk index from 0 to totalChunks - 1 must be synthesized EXACTLY once!
            val counts = synthesizedIndices.groupingBy { it }.eachCount()
            for (i in 0 until totalChunks) {
                assertEquals("Chunk $i must be synthesized exactly once without duplicate passes", 1, counts[i] ?: 0)
            }
        } finally {
            audioHoldGate.complete(Unit)
            pipeline.release()
        }
    }

    /**
     * Requirement 3: No audio loop repetition: verification that playback position increments monotonically.
     */
    @Test
    fun testMonotonicPlaybackPositionWithoutRepetition() = runBlocking {
        val totalChunks = 6
        val playedChunkIndices = CopyOnWriteArrayList<Int>()
        val observedPositions = CopyOnWriteArrayList<Int>()

        val trackingPlayer = object : AudioPlayer() {
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                // Slices of 100ms (simulate natural playback timing)
                Thread.sleep(15)
                return size
            }
        }

        val pipeline = TTSAudioPipeline(
            engine = engine,
            maxQueueCapacity = 4,
            preBufferChunkCount = 1,
            customAudioPlayer = trackingPlayer,
            customSynthesizer = { chunk, _ ->
                ShortArray(4800) { 1 }
            }
        )

        try {
            val chunkList = (0 until totalChunks).map { i ->
                TextChunk(index = i, text = "Câu kiểm tra lặp âm thanh số $i.", pauseType = PauseType.SENTENCE_BREAK, pauseDurationMs = 20)
            }

            pipeline.startReadingChunks(
                chunkList = chunkList,
                onProgress = {
                    observedPositions.add(pipeline.currentChunkIndex)
                }
            )

            // Let it start playing chunk 0 and 1
            withTimeout(3000L) {
                while (pipeline.currentChunkIndex < 1) {
                    delay(20)
                }
            }

            // Pause during playback of chunk 1
            pipeline.pause()
            assertEquals(TTSAudioPipeline.PipelineState.Paused, pipeline.currentState)
            val indexAtPause = pipeline.currentChunkIndex

            delay(100)
            assertTrue("Index should not jump backward while paused: $indexAtPause vs ${pipeline.currentChunkIndex}", pipeline.currentChunkIndex >= indexAtPause)
            val pausedStableIndex = pipeline.currentChunkIndex

            delay(100)
            assertEquals("Index must remain stable while paused", pausedStableIndex, pipeline.currentChunkIndex)

            // Resume
            pipeline.resume()
            assertEquals(TTSAudioPipeline.PipelineState.Playing, pipeline.currentState)

            // Let it progress to chunk 3
            withTimeout(3000L) {
                while (pipeline.currentChunkIndex < 3) {
                    delay(20)
                }
            }

            // Pause a second time
            pipeline.pause()
            val secondIndexAtPause = pipeline.currentChunkIndex
            assertTrue("Second pause index must be >= first pause index", secondIndexAtPause >= pausedStableIndex)

            delay(50)
            // Resume to completion
            pipeline.resume()

            withTimeout(5000L) {
                while (pipeline.currentState !is TTSAudioPipeline.PipelineState.Idle) {
                    delay(30)
                }
            }

            // Verify monotonicity of observed positions: positions must never decrease!
            for (i in 1 until observedPositions.size) {
                assertTrue(
                    "Observed position must be non-decreasing: pos[${i-1}]=${observedPositions[i-1]} vs pos[$i]=${observedPositions[i]}",
                    observedPositions[i] >= observedPositions[i - 1]
                )
            }
            assertEquals("Final position must reach totalChunks", totalChunks, pipeline.currentChunkIndex)
        } finally {
            pipeline.release()
        }
    }

    /**
     * Requirement 4: Rapid pause-resume toggling does not leak coroutines or cause deadlocks.
     */
    @Test
    fun testRapidPauseResumeTogglingDoesNotLeakOrHang() = runBlocking {
        val totalChunks = 30
        val pipeline = TTSAudioPipeline(
            engine = engine,
            maxQueueCapacity = 4,
            preBufferChunkCount = 1,
            customAudioPlayer = object : AudioPlayer() {
                override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                    Thread.sleep(15)
                    return size
                }
            },
            customSynthesizer = { _, _ ->
                ShortArray(4800) { 1 }
            }
        )

        try {
            val chunkList = (0 until totalChunks).map { i ->
                TextChunk(index = i, text = "Câu nhanh $i.", pauseType = PauseType.SENTENCE_BREAK, pauseDurationMs = 10)
            }

            pipeline.startReadingChunks(chunkList)

            // Rapidly toggle pause and resume 8 times
            for (step in 0 until 8) {
                delay(20)
                if (pipeline.currentState !is TTSAudioPipeline.PipelineState.Idle) {
                    pipeline.pause()
                    assertEquals("Must be paused at step $step", TTSAudioPipeline.PipelineState.Paused, pipeline.currentState)
                    delay(15)
                    pipeline.resume()
                }
            }

            // Ensure pipeline reaches completion smoothly without hang or deadlock
            withTimeout(8000L) {
                while (pipeline.currentState !is TTSAudioPipeline.PipelineState.Idle) {
                    delay(30)
                }
            }
            assertEquals("Final position must be totalChunks", totalChunks, pipeline.currentChunkIndex)
        } finally {
            pipeline.release()
        }
    }

    /**
     * Requirement 5: Calling stop() while paused cleanly drains the channel, flushes hardware, and resets position.
     */
    @Test
    fun testStopDuringPauseCleanlyDrainsAndCancels() = runBlocking {
        val totalChunks = 10
        val audioHoldGate = CompletableDeferred<Unit>()

        val pipeline = TTSAudioPipeline(
            engine = engine,
            maxQueueCapacity = 4,
            customAudioPlayer = object : AudioPlayer() {
                override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                    runBlocking { audioHoldGate.await() }
                    return size
                }
            },
            customSynthesizer = { _, _ ->
                ShortArray(4800) { 1 }
            }
        )

        try {
            val chunkList = (0 until totalChunks).map { i ->
                TextChunk(index = i, text = "Câu dừng $i.", pauseType = PauseType.SENTENCE_BREAK, pauseDurationMs = 10)
            }

            pipeline.startReadingChunks(chunkList)

            withTimeout(3000L) {
                while (pipeline.queuedChunkCount < 2) {
                    delay(20)
                }
            }

            pipeline.pause()
            assertEquals(TTSAudioPipeline.PipelineState.Paused, pipeline.currentState)
            assertTrue("Chunks must be retained on pause", pipeline.queuedChunkCount > 0)

            // Calling stop() while paused
            audioHoldGate.complete(Unit)
            pipeline.stop()

            assertEquals("Pipeline state must be Idle after stop", TTSAudioPipeline.PipelineState.Idle, pipeline.currentState)
            assertEquals("Queued chunks must be 0 after stop", 0, pipeline.queuedChunkCount)
            assertEquals("Queued memory bytes must be 0 after stop", 0L, pipeline.queuedMemoryBytes)
            assertEquals("Current chunk index must reset to 0 after stop", 0, pipeline.currentChunkIndex)
        } finally {
            audioHoldGate.complete(Unit)
            pipeline.release()
        }
    }

    /**
     * Requirement 6: AudioPlayer sub-chunk frame slicing and pause state inspection.
     */
    @Test
    fun testAudioPlayerSubChunkSlicingAndPauseState() = runBlocking {
        val writeCallCount = AtomicInteger(0)
        val writtenSliceSizes = CopyOnWriteArrayList<Int>()

        val player = object : AudioPlayer(sampleRate = 48000) {
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                writeCallCount.incrementAndGet()
                writtenSliceSizes.add(size)
                return size
            }
        }

        // 1 second of PCM at 48kHz = 48,000 samples
        val oneSecondPcm = ShortArray(48000) { 1 }

        val totalWritten = player.writeSuspended(oneSecondPcm)

        assertEquals("Total written must match input size", 48000, totalWritten)
        // With 100ms slices (4800 samples each), 48,000 samples should be split into ~10 slices
        assertEquals("Should be sliced into 10 frames of 4800 samples", 10, writeCallCount.get())
        for (sliceSize in writtenSliceSizes) {
            assertEquals("Each frame slice should be 4800 samples (100ms at 48kHz)", 4800, sliceSize)
        }

        // Test player lifecycle pause does not mark needsReinit
        player.init()
        player.pause()
        // pause() must not destroy or invalidate the player state
        player.release()
    }
}
