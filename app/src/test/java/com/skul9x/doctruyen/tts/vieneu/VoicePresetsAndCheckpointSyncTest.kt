package com.skul9x.doctruyen.tts.vieneu

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class VoicePresetsAndCheckpointSyncTest {

    private lateinit var voicesFile: File
    private lateinit var backboneDir: File

    @Before
    fun setUp() {
        VoicePresets.clear()

        val baseDir = if (File("app/src/main/assets/vieneu").exists()) {
            File("app/src/main/assets/vieneu")
        } else {
            File("src/main/assets/vieneu")
        }
        voicesFile = File(baseDir, "voices_v3_turbo.json")
        backboneDir = File(baseDir, "backbone")

        assertTrue("voices_v3_turbo.json must exist at ${voicesFile.absolutePath}", voicesFile.exists())
        VoicePresets.loadFromJson(voicesFile.readText(Charsets.UTF_8))
    }

    @After
    fun tearDown() {
        VoicePresets.clear()
    }

    /**
     * Scenario 1: Verify that exactly 25 voices exist with 192-dim speaker embeddings,
     * valid reference codes (16 indices per frame), correct attributes, and model weight alignment.
     */
    @Test
    fun testAll25VoicesLoadCorrectly() {
        val available = VoicePresets.availableVoices
        assertEquals("Must contain exactly 25 preset voices", 25, available.size)

        val expected25Voices = listOf(
            "Adam bựa", "Trúc Ly", "Thiện Minh", "Mai Anh", "Hải Đăng", "Thùy Dung",
            "Thiền Tâm Đức", "Ngọc Huyền", "Quang Sơn", "Ngọc Trân", "Minh Đức", "Phạm Tuyên",
            "Thái Sơn", "Xuân Vĩnh", "Thanh Bình", "Ngọc Linh", "Đoan Trang", "Thục Đoan",
            "Minh Triết", "Mỹ Duyên", "Quỳnh Anh", "Đức Trí", "Kim Thanh", "Adam", "Quốc Tuấn"
        )

        // Ensure original name 'Adam bựa' is retained as required
        assertTrue("Must retain original voice name 'Adam bựa'", available.contains("Adam bựa"))

        for (voiceName in expected25Voices) {
            assertTrue("Expected voice '$voiceName' must be present in available voices", available.contains(voiceName))
            val preset = VoicePresets.getVoice(voiceName)
            assertNotNull("Preset for '$voiceName' must not be null", preset)
            preset!!
            assertEquals("Preset name must match", voiceName, preset.name)

            // 192-dim speaker embedding verification
            assertEquals("Speaker embedding must have 192 dimensions for '$voiceName'", 192, preset.speakerEmb.size)
            for (v in preset.speakerEmb) {
                assertFalse("Embedding value in '$voiceName' must not be NaN", v.isNaN())
                assertFalse("Embedding value in '$voiceName' must not be infinite", v.isInfinite())
            }

            // Reference codebook frames verification (16-codebook)
            assertNotNull("Codes must be present for '$voiceName'", preset.codes)
            val codes = preset.codes!!
            assertTrue("Codes must have at least one frame for '$voiceName'", codes.isNotEmpty())
            for (frame in codes) {
                assertEquals("Each frame in '$voiceName' codes must have 16 codebook indices", 16, frame.size)
            }

            // Formatting helper verification
            assertTrue("Gender display must be 'Nam' or 'Nữ' for '$voiceName'", preset.genderDisplay in listOf("Nam", "Nữ"))
            assertTrue("Region display must be formatted for '$voiceName'", preset.regionDisplay.startsWith("Giọng Miền "))
            assertTrue(
                "Style display must indicate voice style for '$voiceName'",
                preset.styleDisplay.startsWith("Phong cách") || preset.styleDisplay.startsWith("Giọng đọc")
            )

            // Embedding and code helpers
            val emb = VoicePresets.getVoiceEmbedding(voiceName)
            assertNotNull("VoicePresets.getVoiceEmbedding must return embedding for '$voiceName'", emb)
            assertArrayEquals(preset.speakerEmb, emb, 0.00001f)

            val refCodes = VoicePresets.getRefCodes(voiceName)
            assertNotNull("VoicePresets.getRefCodes must return codes for '$voiceName'", refCodes)
            assertEquals(preset.codes!!.size, refCodes!!.size)
        }

        // Verify model architecture checkpoint alignment
        val configFile = File(backboneDir, "config.json")
        assertTrue("Backbone config.json must exist", configFile.exists())
        val configJson = JSONObject(configFile.readText(Charsets.UTF_8))
        assertEquals("Config speaker_embedding_dim must align with 192-dim voice presets", 192, configJson.getInt("speaker_embedding_dim"))
        assertEquals("Config n_vq codebooks must align with 16-codebook voice preset frames", 16, configJson.getInt("n_vq"))
        assertEquals("Model architecture must be VieNeuV3TurboForTTS", "VieNeuV3TurboForTTS", configJson.getJSONArray("architectures").getString(0))

        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
        assertTrue("Backbone vieneu_v3_heads.npz must exist for checkpoint weights", headsFile.exists())
    }

    /**
     * Scenario 2: Assert that requesting 'Minh Quân Pro', 'Minh Quân', 'Anh Khôi',
     * and 'Mạnh Dũng' correctly resolves to 'Hải Đăng', 'Thiện Minh', and 'Quốc Tuấn'.
     */
    @Test
    fun testAliasResolution() {
        // Direct alias resolution through VoicePresets
        assertEquals("Hải Đăng", VoicePresets.resolveVoiceName("Minh Quân Pro"))
        assertEquals("Hải Đăng", VoicePresets.resolveVoiceName("Minh Quân"))
        assertEquals("Thiện Minh", VoicePresets.resolveVoiceName("Anh Khôi"))
        assertEquals("Quốc Tuấn", VoicePresets.resolveVoiceName("Mạnh Dũng"))

        // getVoice alias resolution
        val haiDangFromPro = VoicePresets.getVoice("Minh Quân Pro")
        assertNotNull(haiDangFromPro)
        assertEquals("Hải Đăng", haiDangFromPro!!.name)

        val haiDangFromMinhQuan = VoicePresets.getVoice("Minh Quân")
        assertNotNull(haiDangFromMinhQuan)
        assertEquals("Hải Đăng", haiDangFromMinhQuan!!.name)

        val thienMinhFromAnhKhoi = VoicePresets.getVoice("Anh Khôi")
        assertNotNull(thienMinhFromAnhKhoi)
        assertEquals("Thiện Minh", thienMinhFromAnhKhoi!!.name)

        val quocTuanFromManhDung = VoicePresets.getVoice("Mạnh Dũng")
        assertNotNull(quocTuanFromManhDung)
        assertEquals("Quốc Tuấn", quocTuanFromManhDung!!.name)

        // Alias resolution through VieNeuTTSManager companion & instance
        assertEquals("Hải Đăng", VieNeuTTSManager.resolveVoiceName("Minh Quân Pro"))
        assertEquals("Hải Đăng", VieNeuTTSManager.resolveVoiceName("Minh Quân"))
        assertEquals("Thiện Minh", VieNeuTTSManager.resolveVoiceName("Anh Khôi"))
        assertEquals("Quốc Tuấn", VieNeuTTSManager.resolveVoiceName("Mạnh Dũng"))

        val manager = VieNeuTTSManager(context = null, onStateChange = {})
        manager.setVoice("Minh Quân Pro")
        assertEquals("Hải Đăng", manager.voiceName)

        manager.setVoice("Minh Quân")
        assertEquals("Hải Đăng", manager.voiceName)

        manager.setVoice("Anh Khôi")
        assertEquals("Thiện Minh", manager.voiceName)

        manager.setVoice("Mạnh Dũng")
        assertEquals("Quốc Tuấn", manager.voiceName)
    }

    /**
     * Scenario 3: Verify that selecting 'Thiền Tâm Đức' applies temperature 0.25f
     * and extended pause durations (clause pause 300ms vs 180ms, sentence pause 600ms vs 350ms).
     */
    @Test
    fun testThienTamDucCustomProfile() {
        // Tuning profile constants
        val thienTamDucProfile = VieNeuTTSManager.getTuningProfile("Thiền Tâm Đức")
        assertEquals("Thiền Tâm Đức must have sampling temperature 0.25f", 0.25f, thienTamDucProfile.temperature, 0.0001f)
        assertEquals("Thiền Tâm Đức must have extended clause pause 300ms", 300, thienTamDucProfile.clausePauseMs)
        assertEquals("Thiền Tâm Đức must have extended sentence pause 600ms", 600, thienTamDucProfile.sentencePauseMs)

        // Default voice tuning profile comparison
        val defaultProfile = VieNeuTTSManager.getTuningProfile("Hải Đăng")
        assertEquals("Default voice must have sampling temperature 0.45f", 0.45f, defaultProfile.temperature, 0.0001f)
        assertEquals("Default voice must have standard clause pause 180ms", 180, defaultProfile.clausePauseMs)
        assertEquals("Default voice must have standard sentence pause 350ms", 350, defaultProfile.sentencePauseMs)

        // Manager instance state tuning
        val manager = VieNeuTTSManager(context = null, onStateChange = {})
        manager.setVoice("Thiền Tâm Đức")
        assertEquals("Manager voiceName must be Thiền Tâm Đức", "Thiền Tâm Đức", manager.voiceName)
        assertEquals("Manager active profile temperature must be 0.25f", 0.25f, manager.tuningProfile.temperature, 0.0001f)
        assertEquals("Manager active profile clause pause must be 300ms", 300, manager.tuningProfile.clausePauseMs)
        assertEquals("Manager active profile sentence pause must be 600ms", 600, manager.tuningProfile.sentencePauseMs)

        val pauseConfig = manager.getEffectivePauseConfig()
        assertEquals("Effective pause config clausePauseMs must be 300ms", 300, pauseConfig.clausePauseMs)
        assertEquals("Effective pause config sentencePauseMs must be 600ms", 600, pauseConfig.sentencePauseMs)

        // Switch to default voice and assert restoration
        manager.setVoice("Hải Đăng")
        assertEquals("Hải Đăng", manager.voiceName)
        assertEquals(0.45f, manager.tuningProfile.temperature, 0.0001f)
        assertEquals(180, manager.tuningProfile.clausePauseMs)
        assertEquals(350, manager.tuningProfile.sentencePauseMs)

        val defaultPauseConfig = manager.getEffectivePauseConfig()
        assertEquals(180, defaultPauseConfig.clausePauseMs)
        assertEquals(350, defaultPauseConfig.sentencePauseMs)
    }

    /**
     * Scenario 4: Verify fallback when voice is unspecified or unknown resolves to 'Hải Đăng'.
     */
    @Test
    fun testDefaultVoiceIsHaiDang() {
        assertEquals("Default voice name in VoicePresets must be Hải Đăng", "Hải Đăng", VoicePresets.defaultVoiceName)
        assertEquals("DEFAULT_VOICE_NAME in VieNeuTTSManager must be Hải Đăng", "Hải Đăng", VieNeuTTSManager.DEFAULT_VOICE_NAME)

        // Null and empty resolution
        assertEquals("Null input resolves to Hải Đăng", "Hải Đăng", VoicePresets.resolveVoiceName(null))
        assertEquals("Empty input resolves to Hải Đăng", "Hải Đăng", VoicePresets.resolveVoiceName(""))
        assertEquals("Blank input resolves to Hải Đăng", "Hải Đăng", VoicePresets.resolveVoiceName("   "))
        assertEquals("Null input resolves to Hải Đăng via manager", "Hải Đăng", VieNeuTTSManager.resolveVoiceName(null))
        assertEquals("Empty input resolves to Hải Đăng via manager", "Hải Đăng", VieNeuTTSManager.resolveVoiceName(""))

        // Non-existent unknown voice name resolution
        assertEquals("Unknown voice resolves to Hải Đăng", "Hải Đăng", VoicePresets.resolveVoiceName("NonExistentVoiceXYZ"))
        assertEquals("Unknown voice getVoice resolves to Hải Đăng", "Hải Đăng", VoicePresets.getVoice("NonExistentVoiceXYZ")?.name)
        assertEquals("Unknown voice resolves to Hải Đăng via manager", "Hải Đăng", VieNeuTTSManager.resolveVoiceName("NonExistentVoiceXYZ"))

        // Fresh manager initialization defaults to Hải Đăng
        val manager = VieNeuTTSManager(context = null, onStateChange = {})
        assertEquals("Fresh VieNeuTTSManager default voice must be Hải Đăng", "Hải Đăng", manager.voiceName)
    }
}
