package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VieNeuConfigTest {

    @Test
    fun testDefaultConfig() {
        val config = VieNeuConfig()
        assertEquals(16, config.nVq)
        assertEquals(768, config.hiddenSize)
        assertEquals(12, config.numHiddenLayers)
        assertEquals(1024, config.audioPadTokenId)
        assertEquals(3, config.textPromptStartTokenId)
        assertEquals(4, config.textPromptEndTokenId)
        assertEquals(5, config.speechGenerationStartTokenId)
        assertEquals(6, config.speechGenerationEndTokenId)
        assertEquals(7, config.audioRefSlotTokenId)
        assertEquals(419, config.textVocabSize)
        assertEquals(16, config.defaultStyleTokenId)
        assertEquals(64, config.headDim)
        assertEquals(96, config.localHeadDim)
    }

    @Test
    fun testParseConfigFile() {
        val configFile = File("src/main/assets/vieneu/backbone/config.json")
        if (configFile.exists()) {
            val jsonStr = configFile.readText(Charsets.UTF_8)
            val config = VieNeuConfig.fromJson(jsonStr)
            assertEquals(16, config.nVq)
            assertEquals(768, config.hiddenSize)
            assertEquals(12, config.numHiddenLayers)
            assertEquals(1024, config.audioPadTokenId)
            assertEquals(3, config.textPromptStartTokenId)
            assertEquals(4, config.textPromptEndTokenId)
            assertEquals(5, config.speechGenerationStartTokenId)
            assertEquals(6, config.speechGenerationEndTokenId)
            assertEquals(7, config.audioRefSlotTokenId)
            assertEquals(419, config.textVocabSize)
            assertEquals(16, config.defaultStyleTokenId)
            assertEquals(1, config.localNumHiddenLayers)
            assertEquals(8, config.localNumAttentionHeads)
            assertTrue(config.useSpeakerEmbedding)
            assertEquals(192, config.speakerEmbeddingDim)
        }
    }
}
