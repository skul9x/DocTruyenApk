package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VieNeuOnnxEngineTest {

    private lateinit var context: Context
    private lateinit var engine: VieNeuOnnxEngine

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        SeaG2P.init(context)
        engine = VieNeuOnnxEngine.create(context)
    }

    @After
    fun teardown() {
        engine.close()
    }

    @Test
    fun testConfigLoaded() {
        val config = engine.config
        assertTrue("Hidden size should be > 0", config.hiddenSize > 0)
        assertTrue("nVq should be > 0", config.nVq > 0)
        println("Config loaded: hidden=${config.hiddenSize}, nVq=${config.nVq}, layers=${config.numHiddenLayers}")
    }

    @Test
    fun testTokenizerEncode() {
        val phonemes = "s in1 ch aw2"
        val tokens = engine.tokenizer.encode(phonemes)
        assertTrue("Tokenizer should produce non-empty output", tokens.isNotEmpty())
        assertTrue("All token IDs should be positive", tokens.all { it >= 0 })
        println("Tokenized '$phonemes' → ${tokens.toList()}")
    }

    @Test
    fun testNpzReaderLoadsEmbeddings() {
        val textEmb = engine.textEmbeddings
        assertTrue("Text embeddings should have rows", textEmb.isNotEmpty())
        println("Text embedding matrix: ${textEmb.size} tokens x ${textEmb[0].size} dims")
    }

    @Test
    fun testOnnxSessionsLoaded() {
        // Verify all 4 ONNX sessions are loaded without error
        assertNotNull("Prefill session should be loaded", engine.sessionManager.prefillSession)
        assertNotNull("Decode session should be loaded", engine.sessionManager.decodeSession)
        assertNotNull("Acoustic session should be loaded", engine.sessionManager.acousticSession)
        assertNotNull("Codec session should be loaded", engine.sessionManager.codecSession)
        println("✅ All 4 ONNX sessions loaded successfully")
    }

    @Test
    fun testFullInference() {
        // Given a phonemized Vietnamese sentence
        val phonemes = SeaG2P.phonemize("Xin chào")

        // When we run full inference
        val startTime = System.currentTimeMillis()
        val pcmAudio = engine.infer(phonemes, voiceName = "Trúc Ly")
        val elapsed = System.currentTimeMillis() - startTime

        // Then we should get valid PCM audio data
        assertNotNull("PCM audio should not be null", pcmAudio)
        assertTrue("PCM audio should have samples", pcmAudio.isNotEmpty())
        assertTrue("PCM audio should have > 1000 samples (at 48kHz)", pcmAudio.size > 1000)

        // Verify audio values are in valid range [-1.5, 1.5]
        val maxVal = pcmAudio.maxOrNull() ?: 0f
        val minVal = pcmAudio.minOrNull() ?: 0f
        assertTrue("Audio max should be <= 1.5, got $maxVal", maxVal <= 1.5f)
        assertTrue("Audio min should be >= -1.5, got $minVal", minVal >= -1.5f)

        val durationSec = pcmAudio.size.toFloat() / 48000f
        println("✅ Generated ${durationSec}s of audio in ${elapsed}ms")
        println("   Samples: ${pcmAudio.size}, Range: [$minVal, $maxVal]")
    }
}
