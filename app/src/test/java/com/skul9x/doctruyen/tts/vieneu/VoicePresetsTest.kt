package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VoicePresetsTest {

    @Test
    fun testLoadVoicesFromJson() {
        val file = File("src/main/assets/vieneu/voices_v3_turbo.json")
        if (file.exists()) {
            VoicePresets.loadFromJson(file.readText(Charsets.UTF_8))
            val voices = VoicePresets.availableVoices
            assertTrue(voices.contains("Trúc Ly"))
            assertTrue(voices.contains("Minh Đức"))
            assertTrue(voices.contains("Phạm Tuyên"))

            val trucLy = VoicePresets.getVoice("Trúc Ly")
            assertNotNull(trucLy)
            assertEquals(192, trucLy!!.speakerEmb.size)
            assertNotNull(trucLy.codes)
            assertTrue(trucLy.codes!!.isNotEmpty())
            assertEquals(16, trucLy.codes!![0].size)
        }
    }
}
