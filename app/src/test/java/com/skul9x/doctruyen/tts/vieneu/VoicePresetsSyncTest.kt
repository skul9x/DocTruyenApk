package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class VoicePresetsSyncTest {

    private val expectedVoices = listOf(
        "Minh Đức", "Phạm Tuyên", "Thái Sơn", "Xuân Vĩnh", "Thanh Bình",
        "Trúc Ly", "Ngọc Linh", "Đoan Trang", "Mai Anh", "Thục Đoan",
        "Minh Triết", "Thùy Dung", "Quang Sơn", "Ngọc Trân", "Mỹ Duyên",
        "Quỳnh Anh", "Đức Trí", "Kim Thanh", "Ngọc Huyền", "Adam"
    )

    private val northVoices = setOf("Minh Đức", "Phạm Tuyên", "Thanh Bình", "Trúc Ly", "Ngọc Linh", "Đoan Trang", "Mai Anh", "Quỳnh Anh", "Ngọc Huyền")
    private val centralVoices = setOf("Quang Sơn", "Ngọc Trân")
    private val southVoices = setOf("Thái Sơn", "Xuân Vĩnh", "Thục Đoan", "Minh Triết", "Thùy Dung", "Mỹ Duyên", "Đức Trí", "Kim Thanh", "Adam")

    @Before
    fun setUp() {
        val file = File("src/main/assets/vieneu/voices_v3_turbo.json")
        val altFile = File("app/src/main/assets/vieneu/voices_v3_turbo.json")
        val targetFile = if (file.exists()) file else altFile
        assertTrue("voices_v3_turbo.json asset file must exist", targetFile.exists())
        VoicePresets.loadFromJson(targetFile.readText(Charsets.UTF_8))
    }

    @Test
    fun testAllTwentyVoicesLoadingAndMetadataIntegrity() {
        val available = VoicePresets.availableVoices
        assertEquals("Must contain exactly 20 preset voices", 20, available.size)

        // Check default voice name is set from JSON
        assertEquals("Adam", VoicePresets.defaultVoiceName)

        for (voiceName in expectedVoices) {
            assertTrue("Expected voice '$voiceName' must be available", available.contains(voiceName))
            val preset = VoicePresets.getVoice(voiceName)
            assertNotNull("Preset for '$voiceName' must not be null", preset)
            preset!!

            assertEquals(voiceName, preset.name)

            // Verify 192-dim speaker embeddings
            assertEquals("Speaker embedding must have 192 dimensions for $voiceName", 192, preset.speakerEmb.size)
            for (v in preset.speakerEmb) {
                assertFalse("Embedding value must not be NaN", v.isNaN())
                assertFalse("Embedding value must not be infinite", v.isInfinite())
            }

            // Verify reference codes
            assertNotNull("Codes must be present for $voiceName", preset.codes)
            val codes = preset.codes!!
            assertTrue("Codes frames must be > 0 for $voiceName", codes.isNotEmpty())
            for (frame in codes) {
                assertEquals("Each codebook frame must have 16 indices for $voiceName", 16, frame.size)
            }

            // Verify gender resolution
            assertTrue("Gender display must be 'Nam' or 'Nữ' for $voiceName", preset.genderDisplay in listOf("Nam", "Nữ"))

            // Verify region resolution
            when {
                northVoices.contains(voiceName) -> assertEquals("Giọng Miền Bắc", preset.regionDisplay)
                centralVoices.contains(voiceName) -> assertEquals("Giọng Miền Trung", preset.regionDisplay)
                southVoices.contains(voiceName) -> assertEquals("Giọng Miền Nam", preset.regionDisplay)
                else -> fail("Unknown region group for $voiceName")
            }

            // Verify style display
            assertTrue(
                "Style display must start with 'Phong cách' or 'Giọng đọc' for $voiceName, got: ${preset.styleDisplay}",
                preset.styleDisplay.startsWith("Phong cách") || preset.styleDisplay.startsWith("Giọng đọc")
            )

            // Verify formatted descriptions and display names
            val desc = preset.getFormattedDescription()
            assertTrue("Formatted description must contain gender for $voiceName", desc.contains(preset.genderDisplay))
            assertTrue("Formatted description must contain region for $voiceName", desc.contains(preset.regionDisplay))
            assertTrue("Formatted description must contain style for $voiceName", desc.contains(preset.styleDisplay))

            val displayName = preset.getFormattedDisplayName()
            assertTrue("Display name must start with voice name for $voiceName", displayName.startsWith(voiceName))
            assertTrue("Display name must contain full description for $voiceName", displayName.contains(desc))

            // Verify object helper methods
            val embFromHelper = VoicePresets.getVoiceEmbedding(voiceName)
            assertNotNull(embFromHelper)
            assertArrayEquals(preset.speakerEmb, embFromHelper, 0.00001f)

            val codesFromHelper = VoicePresets.getRefCodes(voiceName)
            assertNotNull(codesFromHelper)
            assertEquals(preset.codes!!.size, codesFromHelper!!.size)
        }

        // Test fallback resolution for unknown voice returns default voice (Adam)
        val fallback = VoicePresets.getVoice("NonExistentVoiceName")
        assertNotNull(fallback)
        assertEquals("Adam", fallback!!.name)
    }
}
