package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NpzReaderTest {

    @Test
    fun testReadHeadsNpz() {
        val npzFile = File("src/main/assets/vieneu/backbone/vieneu_v3_heads.npz")
        assertTrue("vieneu_v3_heads.npz should exist", npzFile.exists())

        val reader = NpzReader.read(npzFile)
        assertTrue(reader.has("text_emb"))
        assertTrue(reader.has("audio_emb"))
        assertTrue(reader.has("xvec_w"))
        assertTrue(reader.has("xvec_b"))
        assertTrue(reader.has("xvec_ln_w"))
        assertTrue(reader.has("xvec_ln_b"))
        assertTrue(reader.has("xvec_ln_eps"))

        val textEmb2D = reader.get2D("text_emb")
        assertNotNull(textEmb2D)
        assertEquals(419, textEmb2D!!.size)
        assertEquals(768, textEmb2D[0].size)

        val audioEmb3D = reader.get3D("audio_emb")
        assertNotNull(audioEmb3D)
        assertEquals(16, audioEmb3D!!.size)
        assertEquals(1024, audioEmb3D[0].size)
        assertEquals(768, audioEmb3D[0][0].size)

        val xvecW = reader.get2D("xvec_w")
        assertNotNull(xvecW)
        assertEquals(768, xvecW!!.size)
        assertEquals(192, xvecW[0].size)

        val xvecB = reader.getFloatArray("xvec_b")
        assertNotNull(xvecB)
        assertEquals(768, xvecB!!.size)

        val eps = reader.getScalarFloat("xvec_ln_eps")
        assertNotNull(eps)
        assertTrue(eps!! > 0f)
    }
}
