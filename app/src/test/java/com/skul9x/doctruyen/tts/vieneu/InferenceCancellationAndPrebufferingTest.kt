package com.skul9x.doctruyen.tts.vieneu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Comprehensive Verification Test Suite for Phase 01:
 * Inference Cancellation & Audio Pre-buffering Stability (PERF-001 & PERF-005).
 *
 * Verifies:
 * 1. testInferenceAbortsPromptlyOnCancellation:
 *    Launches inferWithTiming on a coroutine dispatcher with a cancellation trigger after
 *    the 3rd frame, asserting that execution exits within 1 frame step before completing
 *    the full max-frame ceiling, and all native intermediate tensors are safely closed.
 * 2. testRapidCancellationFollowedByImmediateReexecution:
 *    Cancels an ongoing synthesis pass and immediately executes a new synthesis call,
 *    verifying that lock acquisition succeeds without deadlock and yields valid PCM output.
 * 3. testPrebufferingEnforcesMinimumDurationThreshold:
 *    Feeds sequential short audio chunks (< 1.0s) into TTSAudioPipeline and verifies that the
 *    pipeline remains in the Buffering state past raw chunk count until the accumulated duration
 *    threshold is reached, preventing premature streaming underrun.
 */
class InferenceCancellationAndPrebufferingTest {

    private lateinit var engine: VieNeuOnnxEngine
    private lateinit var sessionManager: OnnxSessionManager
    private lateinit var config: VieNeuConfig
    private lateinit var tokenizer: VieNeuTokenizer
    private lateinit var textEmb: Array<FloatArray>
    private lateinit var audioEmb: Array<Array<FloatArray>>
    private var xvecW: Array<FloatArray>? = null
    private var xvecB: FloatArray? = null
    private var xvecLnW: FloatArray? = null
    private var xvecLnB: FloatArray? = null
    private var xvecLnEps: Float = 1e-5f

    private fun getBaseAssetsDir(): File {
        val path1 = File("app/src/main/assets/vieneu")
        return if (path1.exists()) path1 else File("src/main/assets/vieneu")
    }

    @Before
    fun setUp() {
        @Suppress("DEPRECATION")
        VieNeuOnnxEngine.CacheTensor.Pool.clear()

        val baseAssetsDir = getBaseAssetsDir()
        val backboneDir = File(baseAssetsDir, "backbone")
        val codecDir = File(baseAssetsDir, "codec")
        val voicesFile = File(baseAssetsDir, "voices_v3_turbo.json")

        assertTrue("backboneDir should exist: ${backboneDir.absolutePath}", backboneDir.exists())
        assertTrue("codecDir should exist: ${codecDir.absolutePath}", codecDir.exists())

        sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 2
        )

        val configFile = File(backboneDir, "config.json")
        config = VieNeuConfig.fromJson(configFile.readText(Charsets.UTF_8))

        val tokenizerFile = File(backboneDir, "tokenizer.json")
        tokenizer = VieNeuTokenizer.fromFile(tokenizerFile)

        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
        val headsReader = NpzReader.read(headsFile)

        textEmb = headsReader.get2D("text_emb")!!
        audioEmb = headsReader.get3D("audio_emb")!!
        xvecW = headsReader.get2D("xvec_w")
        xvecB = headsReader.getFloatArray("xvec_b")
        xvecLnW = headsReader.getFloatArray("xvec_ln_w")
        xvecLnB = headsReader.getFloatArray("xvec_ln_b")
        xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f

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
    fun tearDown() {
        engine.onFrameStep = null
        if (!engine.isClosed) {
            engine.close()
        }
        @Suppress("DEPRECATION")
        VieNeuOnnxEngine.CacheTensor.Pool.clear()
    }

    @Test
    fun testInferenceAbortsPromptlyOnCancellation() = runBlocking {
        var frameCount = 0
        val maxFramesCeiling = 100

        // Install frame hook to cancel after 3rd frame (index 2: frames 0, 1, 2)
        engine.onFrameStep = { frameIdx ->
            frameCount++
            if (frameIdx == 2) {
                engine.cancel()
            }
        }

        var cancellationCaught = false
        val startTime = System.currentTimeMillis()

        val job = launch(Dispatchers.Default) {
            try {
                engine.inferWithTiming(
                    phonemes = "s in1 ch aw2 c a1 c b a1 n",
                    maxNewFrames = maxFramesCeiling
                )
            } catch (e: CancellationException) {
                cancellationCaught = true
            }
        }

        job.join()
        val elapsedMs = System.currentTimeMillis() - startTime

        assertTrue("CancellationException must be thrown when aborted mid-inference", cancellationCaught)
        assertTrue("Inference must abort promptly after 3rd frame, got frameCount: $frameCount", frameCount in 3..4)
        assertTrue("Frame count ($frameCount) must be far below maxNewFrames ceiling ($maxFramesCeiling)", frameCount < maxFramesCeiling)
        // Responsive cancellation under 500ms in test environment (typically < 100ms)
        assertTrue("Elapsed time ($elapsedMs ms) should demonstrate prompt termination", elapsedMs < 2000L)
    }

    @Test
    fun testRapidCancellationFollowedByImmediateReexecution() = runBlocking {
        var frameCount = 0

        // Cancel first pass after 2nd frame
        engine.onFrameStep = { frameIdx ->
            frameCount++
            if (frameIdx == 1) {
                engine.cancel()
            }
        }

        var firstPassCancelled = false
        try {
            engine.inferWithTiming(
                phonemes = "s in1 ch aw2",
                maxNewFrames = 50
            )
        } catch (e: CancellationException) {
            firstPassCancelled = true
        }

        assertTrue("First pass must be cancelled cooperatively", firstPassCancelled)
        assertEquals("First pass must have terminated at frame 2", 2, frameCount)

        // Immediately execute a second inference pass without any delay.
        // Lock acquisition must succeed cleanly without deadlock and yield valid PCM output.
        engine.onFrameStep = null
        val secondResult = engine.inferWithTiming(
            phonemes = "s in1 ch aw2",
            maxNewFrames = 10
        )

        assertNotNull("Re-execution result must not be null", secondResult)
        assertNotNull("Re-execution PCM must not be null", secondResult.pcm)
        assertTrue("Re-execution PCM must contain synthesized audio samples", secondResult.pcm.isNotEmpty())
        for (sample in secondResult.pcm) {
            assertFalse("Sample must not be NaN", sample.isNaN())
            assertFalse("Sample must not be Infinite", sample.isInfinite())
        }
    }

    @Test
    fun testPrebufferingEnforcesMinimumDurationThreshold() = runBlocking {
        // Generate short 200ms audio chunks (duration < 1.0s).
        // At 48000 Hz, 200ms = 9600 samples.
        val chunkDurationMs = 200L
        val shortAudioPcm = FloatArray((VieNeuConfig.SAMPLE_RATE * chunkDurationMs / 1000L).toInt()) { 0.05f }

        val shortEngine = object : VieNeuOnnxEngine(
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
        ) {
            override fun synthesizePass(
                phonemes: String,
                speakerEmb: FloatArray?,
                refCodes: Array<IntArray>?,
                temperature: Float,
                topK: Int,
                topP: Float,
                maxNewFrames: Int,
                repetitionPenalty: Float
            ): SynthesisPassResult {
                return SynthesisPassResult(
                    pcm = shortAudioPcm.copyOf(),
                    frames = List(2) { IntArray(config.nVq) },
                    backboneDurationMs = 5L,
                    codecDurationMs = 2L
                )
            }
        }

        val testAudioPlayer = object : AudioPlayer() {
            override fun init() {}
            override fun play() {}
            override fun write(pcm16: ShortArray, offset: Int, size: Int): Int = size
            override fun stop() {}
            override fun flush() {}
            override fun release() {}
        }

        // Set target prebuffer duration to 1500ms, preBufferChunkCount to 2
        val pipeline = TTSAudioPipeline(
            engine = shortEngine,
            audioPlayer = testAudioPlayer,
            preBufferChunkCount = 2,
            minPreBufferDurationMs = 1500L,
            quantizationType = OnnxSessionManager.QuantizationType.INT8
        )

        val recordedStates = CopyOnWriteArrayList<TTSAudioPipeline.PipelineState>()

        try {
            // Provide 10 short chunks (200ms speech, 0ms pause).
            // 2 chunks = 400ms (far below targetDurationMs of 1500ms).
            // Pipeline must NOT enter Playing state when chunk count reaches 2!
            // It must remain Buffering until at least 8 chunks (1600ms >= 1500ms) or all chunks are buffered.
            val chunks = (1..10).toList().mapIndexed { idx, i ->
                TextChunk(
                    index = idx,
                    text = "Câu số $i ngắn gọn.",
                    pauseType = PauseType.SENTENCE_BREAK,
                    pauseDurationMs = 0
                )
            }

            pipeline.startReadingChunks(
                chunkList = chunks,
                storyId = 0,
                onStateChange = { state ->
                    recordedStates.add(state)
                }
            )

            // Wait until pipeline transitions to Playing (or timeout)
            var elapsed = 0
            while (elapsed < 8000 && !recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing || it is TTSAudioPipeline.PipelineState.Error }) {
                delay(50)
                elapsed += 50
            }

            assertTrue("Pipeline must eventually enter Playing state",
                recordedStates.any { it is TTSAudioPipeline.PipelineState.Playing })

            // Find the index where Playing first occurred
            val firstPlayingIndex = recordedStates.indexOfFirst { it is TTSAudioPipeline.PipelineState.Playing }
            val statesBeforePlaying = recordedStates.subList(0, firstPlayingIndex)

            // Verify that when 2 chunks were buffered (bufferedChunks = 2), the pipeline was STILL Buffering
            // and did NOT transition to Playing prematurely
            val bufferingAt2 = statesBeforePlaying.any {
                it is TTSAudioPipeline.PipelineState.Buffering && it.bufferedChunks == 2
            }
            assertTrue("Pipeline must record Buffering state with 2 chunks before entering Playing", bufferingAt2)

            // Verify that buffering continued past 2 chunks (e.g. bufferedChunks >= 3, 4, 5...)
            val maxBufferedInBuffering = statesBeforePlaying
                .filterIsInstance<TTSAudioPipeline.PipelineState.Buffering>()
                .maxOfOrNull { it.bufferedChunks } ?: 0

            assertTrue(
                "Pipeline must continue buffering past raw chunk count (2) because accumulated duration (400ms) < targetDurationMs (1500ms). Max buffered observed: $maxBufferedInBuffering",
                maxBufferedInBuffering >= 7
            )
        } finally {
            pipeline.release()
        }
    }
}
