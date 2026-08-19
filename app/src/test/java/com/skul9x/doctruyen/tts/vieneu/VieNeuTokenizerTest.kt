package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class VieNeuTokenizerTest {

    private lateinit var tokenizer: VieNeuTokenizer

    @Before
    fun setup() {
        val file = File("src/main/assets/vieneu/backbone/tokenizer.json")
        assertTrue("tokenizer.json should exist", file.exists())
        tokenizer = VieNeuTokenizer.fromFile(file)
    }

    @Test
    fun testVocabSize() {
        assertTrue(tokenizer.vocabSize > 0)
    }

    @Test
    fun testEncodeKnownPhonemes() {
        val phonemes = "s in1 ch aw2"
        val tokens = tokenizer.encode(phonemes)
        assertNotNull(tokens)
        assertTrue(tokens.isNotEmpty())

        // Expected tokens from Python reference: [159, 348, 154, 93, 343, 148, 341, 163, 94]
        val expected = intArrayOf(159, 348, 154, 93, 343, 148, 341, 163, 94)
        assertArrayEquals(expected, tokens)
    }

    @Test
    fun testEncodeComplexPhonemes() {
        val phonemes = "t i1 e2 n g4 v i1 e2 t5"
        val tokens = tokenizer.encode(phonemes)
        val expected = intArrayOf(160, 348, 93, 345, 94, 353, 76, 147, 96, 360, 348, 93, 345, 94, 358, 97)
        assertArrayEquals(expected, tokens)
    }

    @Test
    fun testEncodeEmpty() {
        val tokens = tokenizer.encode("")
        assertEquals(0, tokens.size)
    }
}
