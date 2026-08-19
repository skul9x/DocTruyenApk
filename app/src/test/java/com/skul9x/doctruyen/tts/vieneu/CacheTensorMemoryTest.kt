package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CacheTensorMemoryTest {

    @Test
    fun testCacheTensorRecycleAndPool() {
        VieNeuOnnxEngine.CacheTensor.Pool.clear()

        val shape = longArrayOf(1, 8, 10, 96)
        val data = FloatArray(1 * 8 * 10 * 96) { 1.0f }

        val tensor1 = VieNeuOnnxEngine.CacheTensor.Pool.obtain(shape, data)
        assertNotNull(tensor1)
        assertEquals(7680, tensor1.data.size)

        tensor1.recycle()
        assertEquals(0, tensor1.data.size)
        assertEquals(0, tensor1.shape.size)

        // Obtain should reuse pooled instance
        val shape2 = longArrayOf(1, 8, 11, 96)
        val data2 = FloatArray(1 * 8 * 11 * 96) { 2.0f }
        val tensor2 = VieNeuOnnxEngine.CacheTensor.Pool.obtain(shape2, data2)

        assertTrue("Should reuse recycled instance", tensor1 === tensor2)
        assertEquals(data2.size, tensor2.data.size)
    }

    @Test
    fun testMemoryBoundAfterInference() {
        val backboneDir = File("src/main/assets/vieneu/backbone")
        val codecDir = File("src/main/assets/vieneu/codec")
        val voicesFile = File("src/main/assets/vieneu/voices_v3_turbo.json")

        if (!backboneDir.exists() || !codecDir.exists()) {
            return
        }

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

        if (voicesFile.exists()) {
            VoicePresets.loadFromJson(voicesFile.readText(Charsets.UTF_8))
        }

        val engine = VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = headsReader.get2D("text_emb")!!,
            audioEmbeddings = headsReader.get3D("audio_emb")!!,
            xvecW = headsReader.get2D("xvec_w"),
            xvecB = headsReader.getFloatArray("xvec_b"),
            xvecLnW = headsReader.getFloatArray("xvec_ln_w"),
            xvecLnB = headsReader.getFloatArray("xvec_ln_b"),
            xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f
        )

        try {
            System.gc()
            val beforeMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

            val pcm = engine.infer(
                phonemes = "s in1 ch aw2",
                voiceName = "Trúc Ly",
                maxNewFrames = 15
            )
            assertTrue(pcm.isNotEmpty())

            System.gc()
            val afterMem = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
            // Ensure no huge memory explosion (allow reasonable variance)
            assertTrue("Memory growth must stay bounded", afterMem < beforeMem + 150_000_000L)
        } finally {
            engine.close()
        }
    }
}
