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

            val thuyDung = VoicePresets.getVoice("Thùy Dung")
            assertNotNull(thuyDung)
            assertEquals("Nữ", thuyDung!!.genderDisplay)
            assertEquals("Giọng Miền Nam", thuyDung.regionDisplay)
            assertEquals("Phong cách tin tức", thuyDung.styleDisplay)
            assertEquals("Nữ - Giọng Miền Nam - Phong cách tin tức", thuyDung.getFormattedDescription())
            assertEquals("Thùy Dung - Nữ - Giọng Miền Nam - Phong cách tin tức", thuyDung.getFormattedDisplayName())

            val minhDuc = VoicePresets.getVoice("Minh Đức")
            assertNotNull(minhDuc)
            assertEquals("Nam", minhDuc!!.genderDisplay)
            assertEquals("Giọng Miền Bắc", minhDuc.regionDisplay)
            assertEquals("Phong cách tin tức", minhDuc.styleDisplay)
            assertEquals("Minh Đức - Nam - Giọng Miền Bắc - Phong cách tin tức", minhDuc.getFormattedDisplayName())

            val thaiSon = VoicePresets.getVoice("Thái Sơn")
            assertNotNull(thaiSon)
            assertEquals("Nam", thaiSon!!.genderDisplay)
            assertEquals("Giọng Miền Nam", thaiSon.regionDisplay)
            assertEquals("Phong cách kể chuyện", thaiSon.styleDisplay)
            assertEquals("Thái Sơn - Nam - Giọng Miền Nam - Phong cách kể chuyện", thaiSon.getFormattedDisplayName())

            val trucLyPreset = VoicePresets.getVoice("Trúc Ly")!!
            assertEquals("Nữ", trucLyPreset.genderDisplay)
            assertEquals("Giọng Miền Bắc", trucLyPreset.regionDisplay)
            assertEquals("Phong cách tự nhiên", trucLyPreset.styleDisplay)
            assertEquals("Trúc Ly - Nữ - Giọng Miền Bắc - Phong cách tự nhiên", trucLyPreset.getFormattedDisplayName())

            // Verify all voices produce valid formatted names
            for (name in voices) {
                val p = VoicePresets.getVoice(name)!!
                val formattedName = p.getFormattedDisplayName()
                assertTrue("Formatted name should start with voice name", formattedName.startsWith(name))
                assertTrue("Formatted name should contain hyphen separator", formattedName.contains(" - "))
                println("Voice '$name' => '$formattedName'")
            }
        }
    }
}
