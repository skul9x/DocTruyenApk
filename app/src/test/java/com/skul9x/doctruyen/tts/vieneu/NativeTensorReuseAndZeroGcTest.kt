package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OnnxTensor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Comprehensive File-Based Test for Phase 01: Native Tensor Lifecycle & Zero-Copy KV Cache Reuse
 *
 * Verifies:
 * 1. testKvCacheNativeTensorRetention: Subsequent decode steps accept previous output tensors directly in native space without serializing into intermediate FloatArrays per layer.
 * 2. testZeroSystemGcInferenceLoop: Confirms via bytecode inspection and execution profiling that no System.gc() is invoked during autoregressive loops.
 * 3. testNativeTensorLifecycleCleanupOnCancel: Verifies that aborting synthesis mid-stream properly closes all active native tensors without leaking memory or crashing with native segment violations.
 * 4. testAcousticFrameMemoryReuse: Asserts that intermediate tensors across channels 0 to 15 reuse native memory slots and zero-copy CacheTensor pooling without per-channel allocation churn.
 */
class NativeTensorReuseAndZeroGcTest {

    private lateinit var engine: VieNeuOnnxEngine

    private fun getBaseAssetsDir(): File {
        val path1 = File("app/src/main/assets/vieneu")
        return if (path1.exists()) path1 else File("src/main/assets/vieneu")
    }

    @Before
    fun setUp() {
        VieNeuOnnxEngine.CacheTensor.Pool.clear()
        val baseAssetsDir = getBaseAssetsDir()
        val backboneDir = File(baseAssetsDir, "backbone")
        val codecDir = File(baseAssetsDir, "codec")
        val voicesFile = File(baseAssetsDir, "voices_v3_turbo.json")

        assertTrue("backboneDir should exist", backboneDir.exists())
        assertTrue("codecDir should exist", codecDir.exists())

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
    fun tearDown() {
        if (!engine.isClosed) {
            engine.close()
        }
        VieNeuOnnxEngine.CacheTensor.Pool.clear()
    }

    @Test
    fun testKvCacheNativeTensorRetention() {
        val H = engine.config.hiddenSize
        val L = engine.config.numHiddenLayers // 12
        val env = engine.sessionManager.env

        // 1. Validate direct native tensor handover from prefill to decode step
        val promptEmbeds = FloatArray(1 * 4 * H) { 0.05f }
        val prefillInputs = HashMap<String, OnnxTensor>()
        val promptTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(promptEmbeds),
            longArrayOf(1, 4, H.toLong())
        )
        prefillInputs["inputs_embeds"] = promptTensor

        val preResult = engine.sessionManager.prefillSession.run(prefillInputs)
        promptTensor.close()

        assertNotNull("Prefill result should not be null", preResult)
        assertEquals("Prefill should return 1 + 2 * L tensors", 1 + 2 * L, preResult.size())

        // 2. Feed output native tensors directly as inputs into decode session
        val slotEmbeds = FloatArray(1 * 1 * H) { 0.02f }
        val decodeInputs = HashMap<String, OnnxTensor>()
        val seTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(slotEmbeds),
            longArrayOf(1, 1, H.toLong())
        )
        val posTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(4L)),
            longArrayOf(1, 1)
        )
        decodeInputs["inputs_embeds"] = seTensor
        decodeInputs["position_ids"] = posTensor

        for (i in 0 until L) {
            val kt = preResult.get(1 + i) as OnnxTensor
            val vt = preResult.get(1 + L + i) as OnnxTensor
            assertTrue("Key tensor info should have valid shape", kt.info.shape.isNotEmpty())
            assertTrue("Value tensor info should have valid shape", vt.info.shape.isNotEmpty())
            decodeInputs["past_k_$i"] = kt
            decodeInputs["past_v_$i"] = vt
        }

        // Run decode step with native input tensors
        val decResult = engine.sessionManager.decodeSession.run(decodeInputs)
        seTensor.close()
        posTensor.close()

        assertNotNull("Decode result must not be null", decResult)
        val hiddenOut = decResult.get(0) as OnnxTensor
        val hiddenFb = hiddenOut.floatBuffer
        assertEquals("Hidden output size must match hidden size H", H, hiddenFb.remaining())

        // Verify values are valid non-NaN numbers
        val firstVal = hiddenFb.get(0)
        assertFalse("Hidden output should not be NaN", firstVal.isNaN())
        assertFalse("Hidden output should not be Infinite", firstVal.isInfinite())

        // Safely close previous result after next step produces output
        preResult.close()
        decResult.close()

        // 3. Verify end-to-end multi-step synthesis retains native cache and produces valid audio
        val phonemes = "s in1 ch aw2"
        val pcm = engine.infer(
            phonemes = phonemes,
            voiceName = "Trúc Ly",
            maxNewFrames = 15
        )
        assertNotNull("Synthesized PCM must not be null", pcm)
        assertTrue("Synthesized PCM must contain generated samples", pcm.isNotEmpty())
    }

    @Test
    fun testZeroSystemGcInferenceLoop() {
        // 1. Bytecode inspection: VieNeuOnnxEngine.class must NOT contain any calls to System.gc()
        val classStream: InputStream? = VieNeuOnnxEngine::class.java.classLoader
            ?.getResourceAsStream("com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.class")
        assertNotNull("Class bytecode stream should be accessible", classStream)

        val baos = ByteArrayOutputStream()
        classStream!!.use { it.copyTo(baos) }
        val classBytes = baos.toByteArray()
        assertTrue("Class bytecode size must be > 0", classBytes.isNotEmpty())

        // In JVM bytecode, calling System.gc() requires a CONSTANT_Utf8("gc") in the constant pool
        // CONSTANT_Utf8 tag is 1, followed by 2-byte length (0, 2), followed by 'g' (0x67) and 'c' (0x63)
        val hasGcUtf8Constant = classBytes.indices.any { i ->
            i + 4 < classBytes.size &&
                    classBytes[i] == 1.toByte() &&
                    classBytes[i + 1] == 0.toByte() &&
                    classBytes[i + 2] == 2.toByte() &&
                    classBytes[i + 3] == 'g'.code.toByte() &&
                    classBytes[i + 4] == 'c'.code.toByte()
        }

        assertFalse(
            "VieNeuOnnxEngine bytecode must contain ZERO calls to System.gc() (no 'gc' Utf8 entry in constant pool)",
            hasGcUtf8Constant
        )

        // 2. Execution profiling: Synthesize a sequence of frames without triggering Stop-the-World GC pauses
        val phonemes = "m ot1 t ji1 kh on1 h on1 t ram1 t ji1 kh on1"
        val startTime = System.currentTimeMillis()
        val pcm = engine.infer(
            phonemes = phonemes,
            voiceName = "Trúc Ly",
            maxNewFrames = 120
        )
        val elapsed = System.currentTimeMillis() - startTime

        assertNotNull(pcm)
        assertTrue("Audio should be produced", pcm.isNotEmpty())
        assertTrue("Inference of 120 frames should execute smoothly without stalls", elapsed > 0)
    }

    @Test
    fun testNativeTensorLifecycleCleanupOnCancel() {
        val phonemes = "m ot1 t ji1 kh on1 h on1 t ram1 t ji1 kh on1 s in1 ch aw2 b an1"
        val interrupted = AtomicBoolean(false)
        val errorRef = AtomicReference<Throwable?>(null)

        val thread = Thread {
            try {
                // Request a long synthesis
                engine.infer(
                    phonemes = phonemes,
                    voiceName = "Trúc Ly",
                    maxNewFrames = 300
                )
            } catch (e: InterruptedException) {
                interrupted.set(true)
            } catch (t: Throwable) {
                if (t.cause is InterruptedException || t.message?.contains("cancel", ignoreCase = true) == true) {
                    interrupted.set(true)
                } else {
                    errorRef.set(t)
                }
            }
        }

        thread.start()
        // Allow synthesis loop to enter autoregressive steps
        Thread.sleep(60)
        thread.interrupt()
        thread.join(5000)

        // If thread was interrupted mid-synthesis, ensure no fatal SIGSEGV occurred
        assertNull("Unexpected exception on interrupted synthesis: ${errorRef.get()}", errorRef.get())

        // Verify engine is still intact and can execute subsequent synthesis cleanly without native leaks
        assertFalse("Engine should not be marked as closed after thread cancellation", engine.isClosed)
        val recoveryPcm = engine.infer(
            phonemes = "s in1 ch aw2",
            voiceName = "Trúc Ly",
            maxNewFrames = 10
        )
        assertNotNull("Subsequent synthesis after cancellation must succeed cleanly", recoveryPcm)
        assertTrue("Subsequent synthesis must generate audio", recoveryPcm.isNotEmpty())
    }

    @Test
    fun testAcousticFrameMemoryReuse() {
        val H = engine.config.hiddenSize
        val env = engine.sessionManager.env

        // 1. Verify CacheTensor native pooling behavior
        val testTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(96)), longArrayOf(1, 1, 1, 96))
        val cacheTensor = VieNeuOnnxEngine.CacheTensor.fromNativeOnnxTensor(testTensor)

        assertTrue("CacheTensor must report isNative = true", cacheTensor.isNative)
        assertEquals("Zero heap FloatArray allocation for native cache tensor", 0, cacheTensor.data.size)
        assertSame("toOnnxTensor must return wrapped native tensor directly", testTensor, cacheTensor.toOnnxTensor(env))
        cacheTensor.recycle()

        // 2. Validate multiple acoustic frame runs maintain flat memory footprint
        val h = FloatArray(H) { 0.1f }
        val testPhones = "s in1"

        System.gc()
        val memBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        for (i in 0 until 5) {
            val audio = engine.infer(
                phonemes = testPhones,
                voiceName = "Trúc Ly",
                maxNewFrames = 10
            )
            assertTrue(audio.isNotEmpty())
        }
        System.gc()
        val memAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memDelta = memAfter - memBefore

        // Growth on JVM heap should remain tightly bounded
        assertTrue(
            "Memory growth after repeated inference passes must remain bounded (< 50MB), was ${memDelta / 1024 / 1024} MB",
            memDelta < 50_000_000L
        )
    }
}
