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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Comprehensive File-Based Test for Phase 02: Bounded Audio Channel & Playback Concurrency.
 *
 * Verifies:
 * 1. testBoundedChannelBackpressureSuspension: Producer coroutine suspends when queue reaches capacity
 *    (e.g., 4 chunks) and resumes only when consumer drains an item.
 * 2. testMemoryCapUnderLongStoryPlayback: Memory footprint of queued audio remains strictly bounded
 *    below 15MB when streaming 150 consecutive chunks.
 * 3. testDispatcherSeparationForAudioTrack: Blocking audio playback operations execute on dedicated
 *    audio dispatchers without monopolizing Dispatchers.Default.
 * 4. testImmediateCancellationAndChannelDrain: Calling stop() or pause() cleanly drains the channel
 *    and terminates active playback jobs without leaks.
 */
class BoundedAudioChannelPipelineTest {

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
    }

    @After
    fun teardown() {
        if (::engine.isInitialized) {
            engine.close()
        }
    }

    /**
     * Test 1: Verifies that producer coroutine suspends when channel reaches capacity (e.g., 4 chunks)
     * and resumes only when consumer drains an item.
     */
    @Test
    fun testBoundedChannelBackpressureSuspension() = runBlocking {
        val capacity = 4
        val totalTestChunks = 10
        val synthesizedChunks = AtomicInteger(0)
        val consumerDrainGate = CompletableDeferred<Unit>()

        // Custom player that blocks consumption until consumerDrainGate is triggered
        val blockingAudioPlayer = object : AudioPlayer() {
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                // Block playback of the first drained chunk until allowed
                runBlocking {
                    consumerDrainGate.await()
                }
                return size
            }
        }

        // Custom fast synthesizer tracking every synthesized chunk
        val pipeline = TTSAudioPipeline(
            engine = engine,
            maxQueueCapacity = capacity,
            customAudioPlayer = blockingAudioPlayer,
            customSynthesizer = { chunk, _ ->
                synthesizedChunks.incrementAndGet()
                ShortArray(4800) { 1 } // 0.1s PCM
            }
        )

        try {
            val chunkList = (0 until totalTestChunks).map { i ->
                TextChunk(index = i, text = "Đoạn văn kiểm thử số $i.", pauseType = PauseType.SENTENCE_BREAK, pauseDurationMs = 50)
            }

            // Start pipeline reading
            pipeline.startReadingChunks(chunkList)

            // Allow producer coroutine to run until it hits channel capacity backpressure
            var waitTime = 0L
            while (synthesizedChunks.get() < capacity && waitTime < 2000L) {
                delay(20)
                waitTime += 20L
            }

            // Give extra time to ensure producer has suspended on channel.send() for chunk 4
            delay(150)

            // The producer should have synthesized at most capacity items (or suspended on the capacity+1 send)
            // Active queued items in channel must not exceed capacity
            val countBeforeDrain = pipeline.queuedChunkCount
            assertTrue(
                "Queued chunks ($countBeforeDrain) must be <= capacity ($capacity)",
                countBeforeDrain <= capacity
            )

            // Chunks beyond capacity + buffer in-flight must NOT have been synthesized while consumer is blocked
            val synthCountBeforeRelease = synthesizedChunks.get()
            assertTrue(
                "Synthesized chunks ($synthCountBeforeRelease) must be bounded (<= ${capacity + 2})",
                synthCountBeforeRelease <= capacity + 2
            )
            assertTrue(
                "Synthesized chunks ($synthCountBeforeRelease) must be less than total ($totalTestChunks) while consumer is blocked",
                synthCountBeforeRelease < totalTestChunks
            )

            // Now release consumer drain gate - allows consumer to consume chunks
            consumerDrainGate.complete(Unit)

            // Wait for pipeline to finish all 10 chunks
            withTimeout(5000L) {
                while (pipeline.currentState !is TTSAudioPipeline.PipelineState.Idle && synthesizedChunks.get() < totalTestChunks) {
                    if (pipeline.currentState is TTSAudioPipeline.PipelineState.Error) {
                        fail("Pipeline entered error state: ${(pipeline.currentState as TTSAudioPipeline.PipelineState.Error).message}")
                    }
                    delay(30)
                }
            }

            // Verify producer resumed and completed all chunks
            assertEquals("All chunks must be synthesized after unblocking consumer", totalTestChunks, synthesizedChunks.get())
        } finally {
            pipeline.release()
        }
    }

    /**
     * Test 2: Asserts that queue memory footprint remains bounded below 15MB when simulating 150 consecutive chunks.
     */
    @Test
    fun testMemoryCapUnderLongStoryPlayback() = runBlocking {
        val totalStoryChunks = 150
        val maxMemoryBytesObserved = AtomicLong(0L)
        val maxQueueSizeObserved = AtomicInteger(0)
        val chunksConsumed = AtomicInteger(0)

        // Mock fast non-blocking audio player that simulates realistic AudioTrack consumption
        val fastPlayer = object : AudioPlayer() {
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                chunksConsumed.incrementAndGet()
                return size
            }
        }

        var pipelineRef: TTSAudioPipeline? = null

        // Each chunk is 48,000 samples = 96,000 bytes (1 second audio).
        // 150 chunks in an unbounded queue would occupy 150 * 96,000 = 14.4MB ~ 15MB+.
        // With capacity = 4, queue memory must remain < 500KB (bounded < 15MB).
        val pipeline = TTSAudioPipeline(
            engine = engine,
            maxQueueCapacity = 4,
            customAudioPlayer = fastPlayer,
            customSynthesizer = { chunk, _ ->
                pipelineRef?.let { p ->
                    val currentMemory = p.queuedMemoryBytes
                    val currentQueue = p.queuedChunkCount
                    maxMemoryBytesObserved.updateAndGet { prev -> maxOf(prev, currentMemory) }
                    maxQueueSizeObserved.updateAndGet { prev -> maxOf(prev, currentQueue) }
                }
                delay(2) // Simulate synthesis time
                ShortArray(48000) { 42 } // 96KB per chunk
            }
        )
        pipelineRef = pipeline

        try {
            val chunkList = (0 until totalStoryChunks).map { i ->
                TextChunk(index = i, text = "Chương $i: Dòng chảy thời gian của câu chuyện dài.", pauseType = PauseType.SENTENCE_BREAK, pauseDurationMs = 50)
            }

            pipeline.startReadingChunks(chunkList)

            // Monitor execution until all 150 chunks are played
            withTimeout(15000L) {
                while (pipeline.currentState !is TTSAudioPipeline.PipelineState.Idle || chunksConsumed.get() < totalStoryChunks) {
                    val mem = pipeline.queuedMemoryBytes
                    val qSize = pipeline.queuedChunkCount
                    maxMemoryBytesObserved.updateAndGet { prev -> maxOf(prev, mem) }
                    maxQueueSizeObserved.updateAndGet { prev -> maxOf(prev, qSize) }
                    delay(10)
                }
            }

            val maxMem = maxMemoryBytesObserved.get()
            val maxQueue = maxQueueSizeObserved.get()

            // Requirement: Queue memory occupied must be strictly capped under 15MB
            val fifteenMbBytes = 15L * 1024L * 1024L
            assertTrue(
                "Max queue memory ($maxMem bytes) must be strictly capped under 15MB ($fifteenMbBytes bytes)",
                maxMem < fifteenMbBytes
            )

            // Queue capacity ceiling must be respected
            assertTrue(
                "Max queue size observed ($maxQueue) must be <= 4",
                maxQueue <= 4
            )

            assertEquals("All 150 chunks must be consumed", totalStoryChunks, chunksConsumed.get())
        } finally {
            pipeline.release()
        }
    }

    /**
     * Test 3: Verifies that blocking audio playback operations execute on dedicated audio dispatchers
     * without monopolizing Dispatchers.Default.
     */
    @Test
    fun testDispatcherSeparationForAudioTrack() = runBlocking {
        val executedThreads = CopyOnWriteArrayList<String>()
        val defaultThreadMonopolized = AtomicBoolean(false)

        val customAudioPlayer = object : AudioPlayer() {
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int {
                val threadName = Thread.currentThread().name
                executedThreads.add(threadName)
                // Check if write executed on Dispatchers.Default compute pool
                if (threadName.contains("DefaultDispatcher-worker-") && !threadName.contains("VieNeu-AudioTrack")) {
                    defaultThreadMonopolized.set(true)
                }
                return size
            }
        }

        val pipeline = TTSAudioPipeline(
            engine = engine,
            customAudioPlayer = customAudioPlayer,
            customSynthesizer = { _, _ ->
                ShortArray(2400) { 10 }
            }
        )

        try {
            val chunkList = (0 until 5).map { i ->
                TextChunk(index = i, text = "Kiểm thử luồng âm thanh câu số $i.", pauseType = PauseType.SENTENCE_BREAK, pauseDurationMs = 50)
            }

            pipeline.startReadingChunks(chunkList)

            withTimeout(5000L) {
                while (pipeline.currentState !is TTSAudioPipeline.PipelineState.Idle) {
                    if (pipeline.currentState is TTSAudioPipeline.PipelineState.Error) {
                        fail("Pipeline entered error state: ${(pipeline.currentState as TTSAudioPipeline.PipelineState.Error).message}")
                    }
                    delay(20)
                }
            }

            assertTrue("Playback writes must have been recorded", executedThreads.isNotEmpty())

            // Assert that dedicated audio dispatcher was used (VieNeu-AudioTrack-Thread)
            val dedicatedThreadUsage = executedThreads.any { it.contains("VieNeu-AudioTrack-Thread") }
            assertTrue(
                "Audio writes must execute on dedicated AudioTrack dispatcher thread, executed on: $executedThreads",
                dedicatedThreadUsage
            )

            // Assert zero blocking writes on Dispatchers.Default
            assertFalse(
                "Hardware audio writes must NOT block Dispatchers.Default worker threads",
                defaultThreadMonopolized.get()
            )
        } finally {
            pipeline.release()
        }
    }

    /**
     * Test 4: Verifies that calling stop() cleanly drains the channel and terminates
     * active playback jobs without leaks, while pause() preserves buffers cooperatively.
     */
    @Test
    fun testImmediateCancellationAndChannelDrain() = runBlocking {
        val totalChunks = 20
        val pipeline = TTSAudioPipeline(
            engine = engine,
            maxQueueCapacity = 4,
            customSynthesizer = { _, _ ->
                delay(30)
                ShortArray(48000) { 1 }
            }
        )

        try {
            val chunkList = (0 until totalChunks).map { i ->
                TextChunk(index = i, text = "Đoạn văn kiểm tra hủy bỏ câu số $i.", pauseType = PauseType.SENTENCE_BREAK, pauseDurationMs = 50)
            }

            // 1. Start pipeline
            pipeline.startReadingChunks(chunkList)

            // Wait until some chunks are produced and buffered
            var wait = 0
            while (pipeline.queuedChunkCount == 0 && wait < 50) {
                delay(20)
                wait++
            }

            // 2. Pause pipeline
            pipeline.pause()
            assertEquals("State must be Paused", TTSAudioPipeline.PipelineState.Paused, pipeline.currentState)

            // Assert channel retains queued chunks and memory (cooperative buffer retention)
            assertTrue("Queued chunks must be retained after pause", pipeline.queuedChunkCount > 0)
            assertTrue("Queued memory bytes must be retained after pause", pipeline.queuedMemoryBytes > 0L)

            // 3. Resume pipeline
            val pausedIndex = pipeline.currentChunkIndex
            pipeline.resume()
            assertTrue(
                "State after resume must be Synthesizing, Buffering, or Playing",
                pipeline.currentState is TTSAudioPipeline.PipelineState.Synthesizing ||
                        pipeline.currentState is TTSAudioPipeline.PipelineState.Buffering ||
                        pipeline.currentState is TTSAudioPipeline.PipelineState.Playing
            )

            delay(60)

            // 4. Stop pipeline
            pipeline.stop()
            assertEquals("State must be Idle after stop", TTSAudioPipeline.PipelineState.Idle, pipeline.currentState)
            assertEquals("Current chunk index must be reset to 0 after stop", 0, pipeline.currentChunkIndex)
            assertEquals("Queued chunk count must be 0 after stop", 0, pipeline.queuedChunkCount)
            assertEquals("Queued memory bytes must be 0 after stop", 0L, pipeline.queuedMemoryBytes)
        } finally {
            pipeline.release()
        }
    }
}
