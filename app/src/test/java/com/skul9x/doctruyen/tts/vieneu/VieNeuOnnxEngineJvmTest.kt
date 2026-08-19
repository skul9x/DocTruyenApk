package com.skul9x.doctruyen.tts.vieneu

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class VieNeuOnnxEngineJvmTest {

    private lateinit var engine: VieNeuOnnxEngine

    @Before
    fun setup() {
        val backboneDir = File("src/main/assets/vieneu/backbone")
        val codecDir = File("src/main/assets/vieneu/codec")
        val voicesFile = File("src/main/assets/vieneu/voices_v3_turbo.json")

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
    fun teardown() {
        engine.close()
    }

    @Test
    fun testConfigLoaded() {
        val config = engine.config
        assertTrue("Hidden size should be > 0", config.hiddenSize > 0)
        assertTrue("nVq should be > 0", config.nVq > 0)
        assertEquals(12, config.numHiddenLayers)
    }

    @Test
    fun testTokenizerEncode() {
        val phonemes = "s in1 ch aw2"
        val tokens = engine.tokenizer.encode(phonemes)
        assertTrue(tokens.isNotEmpty())
        assertArrayEquals(intArrayOf(159, 348, 154, 93, 343, 148, 341, 163, 94), tokens)
    }

    @Test
    fun testFullInferenceJvm() {
        val phonemes = "s in1 ch aw2"
        val startTime = System.currentTimeMillis()
        val pcmAudio = engine.infer(
            phonemes = phonemes,
            voiceName = "Trúc Ly",
            maxNewFrames = 100
        )
        val elapsed = System.currentTimeMillis() - startTime

        assertNotNull("PCM audio should not be null", pcmAudio)
        assertTrue("PCM audio should have samples", pcmAudio.isNotEmpty())
        assertTrue("PCM audio should have > 1000 samples (at 48kHz)", pcmAudio.size > 1000)

        val maxVal = pcmAudio.maxOrNull() ?: 0f
        val minVal = pcmAudio.minOrNull() ?: 0f
        assertTrue("Audio max should be <= 1.5, got $maxVal", maxVal <= 1.5f)
        assertTrue("Audio min should be >= -1.5, got $minVal", minVal >= -1.5f)

        val durationSec = pcmAudio.size.toFloat() / 48000f
        println("✅ Generated ${durationSec}s of audio (${pcmAudio.size} samples) in ${elapsed}ms")
        println("   Samples range: [$minVal, $maxVal]")
    }
}
