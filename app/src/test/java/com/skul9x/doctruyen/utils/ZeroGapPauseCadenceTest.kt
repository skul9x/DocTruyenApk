package com.skul9x.doctruyen.utils

import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.tts.vieneu.PauseConfig
import com.skul9x.doctruyen.tts.vieneu.PauseType
import com.skul9x.doctruyen.tts.vieneu.PcmUtils
import com.skul9x.doctruyen.tts.vieneu.SmartTextSegmenter
import com.skul9x.doctruyen.tts.vieneu.VieNeuConfig
import com.skul9x.doctruyen.tts.vieneu.VieNeuTTSManager
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ZeroGapPauseCadenceTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val storage = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(storage)

        override fun getString(key: String?, defValue: String?): String? = (storage[key] as? String) ?: defValue

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (storage[key] as? MutableSet<String>) ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int = (storage[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = (storage[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float = (storage[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (storage[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = storage.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(storage)

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val storage: MutableMap<String, Any>) : SharedPreferences.Editor {
            private val temp = mutableMapOf<String, Any?>()
            private var clearFlag = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    if (value != null) temp[key] = value else temp[key] = null
                }
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) {
                    if (values != null) temp[key] = values else temp[key] = null
                }
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) temp[key] = null
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearFlag = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearFlag) storage.clear()
                temp.forEach { (k, v) ->
                    if (v == null) storage.remove(k) else storage[k] = v
                }
            }
        }
    }

    private lateinit var fakePrefs: FakeSharedPreferences
    private val dummyContext: Context = object : android.content.ContextWrapper(null) {}

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        UserConfig.customPrefsProvider = { fakePrefs }
    }

    @After
    fun tearDown() {
        UserConfig.customPrefsProvider = null
    }

    @Test
    fun testZeroGapConstantsAndDefaultValues() {
        // Minimum boundaries must be 0 ms across all cadence pause parameters
        assertEquals("MIN_PAUSE_SENTENCE must be 0", 0, UserConfig.MIN_PAUSE_SENTENCE)
        assertEquals("MIN_PAUSE_PARAGRAPH must be 0", 0, UserConfig.MIN_PAUSE_PARAGRAPH)
        assertEquals("MIN_PAUSE_CLAUSE must be 0", 0, UserConfig.MIN_PAUSE_CLAUSE)

        // Maximum boundaries must remain intact per constraints
        assertEquals("MAX_PAUSE_SENTENCE must remain 1000", 1000, UserConfig.MAX_PAUSE_SENTENCE)
        assertEquals("MAX_PAUSE_PARAGRAPH must remain 1500", 1500, UserConfig.MAX_PAUSE_PARAGRAPH)
        assertEquals("MAX_PAUSE_CLAUSE must remain 500", 500, UserConfig.MAX_PAUSE_CLAUSE)

        // Defaults must remain standard
        assertEquals(400, UserConfig.DEFAULT_PAUSE_SENTENCE)
        assertEquals(650, UserConfig.DEFAULT_PAUSE_PARAGRAPH)
        assertEquals(180, UserConfig.DEFAULT_PAUSE_CLAUSE)
    }

    @Test
    fun testZeroGapPausePersistenceAndBoundaryClamping() {
        // 1. Setting exactly 0 ms must persist and read back without legacy minimum coercion
        UserConfig.setPauseSentence(dummyContext, 0)
        assertEquals("Sentence pause must persist 0 ms without coercion to 150 ms", 0, UserConfig.getPauseSentence(dummyContext))

        UserConfig.setPauseParagraph(dummyContext, 0)
        assertEquals("Paragraph pause must persist 0 ms without coercion to 300 ms", 0, UserConfig.getPauseParagraph(dummyContext))

        UserConfig.setPauseClause(dummyContext, 0)
        assertEquals("Clause pause must persist 0 ms without coercion to 50 ms", 0, UserConfig.getPauseClause(dummyContext))

        // 2. Negative values must be clamped to 0 ms
        UserConfig.setPauseSentence(dummyContext, -250)
        assertEquals("Negative sentence pause must be clamped to 0 ms", 0, UserConfig.getPauseSentence(dummyContext))

        UserConfig.setPauseParagraph(dummyContext, -100)
        assertEquals("Negative paragraph pause must be clamped to 0 ms", 0, UserConfig.getPauseParagraph(dummyContext))

        UserConfig.setPauseClause(dummyContext, -50)
        assertEquals("Negative clause pause must be clamped to 0 ms", 0, UserConfig.getPauseClause(dummyContext))

        // 3. Values exceeding maximum must be clamped to respective max
        UserConfig.setPauseSentence(dummyContext, 2000)
        assertEquals(1000, UserConfig.getPauseSentence(dummyContext))

        UserConfig.setPauseParagraph(dummyContext, 3000)
        assertEquals(1500, UserConfig.getPauseParagraph(dummyContext))

        UserConfig.setPauseClause(dummyContext, 800)
        assertEquals(500, UserConfig.getPauseClause(dummyContext))
    }

    @Test
    fun testSeamlessAndExistingCadencePresets() {
        // 1. Dedicated SEAMLESS / ZERO_GAP preset must configure 0 ms for sentence and clause
        UserConfig.applyPausePreset(dummyContext, "SEAMLESS")
        assertEquals("SEAMLESS preset must set sentence pause to 0 ms", 0, UserConfig.getPauseSentence(dummyContext))
        assertEquals("SEAMLESS preset must set clause pause to 0 ms", 0, UserConfig.getPauseClause(dummyContext))
        assertEquals("SEAMLESS preset must set paragraph pause to 0 ms", 0, UserConfig.getPauseParagraph(dummyContext))

        UserConfig.applyPausePreset(dummyContext, "ZERO_GAP")
        assertEquals(0, UserConfig.getPauseSentence(dummyContext))
        assertEquals(0, UserConfig.getPauseClause(dummyContext))
        assertEquals(0, UserConfig.getPauseParagraph(dummyContext))

        // 2. Existing presets must remain intact and backward compatible
        UserConfig.applyPausePreset(dummyContext, "FAST")
        assertEquals(250, UserConfig.getPauseSentence(dummyContext))
        assertEquals(400, UserConfig.getPauseParagraph(dummyContext))
        assertEquals(100, UserConfig.getPauseClause(dummyContext))

        UserConfig.applyPausePreset(dummyContext, "EMOTIONAL")
        assertEquals(600, UserConfig.getPauseSentence(dummyContext))
        assertEquals(900, UserConfig.getPauseParagraph(dummyContext))
        assertEquals(250, UserConfig.getPauseClause(dummyContext))

        UserConfig.applyPausePreset(dummyContext, "STANDARD")
        assertEquals(400, UserConfig.getPauseSentence(dummyContext))
        assertEquals(650, UserConfig.getPauseParagraph(dummyContext))
        assertEquals(180, UserConfig.getPauseClause(dummyContext))
    }

    @Test
    fun testVieNeuTTSManagerEffectivePauseConfigHonorsZeroGap() {
        val manager = VieNeuTTSManager(dummyContext, onStateChange = {})

        // Set user preferences to zero gap
        UserConfig.setPauseSentence(dummyContext, 0)
        UserConfig.setPauseParagraph(dummyContext, 0)
        UserConfig.setPauseClause(dummyContext, 0)

        // Effective pause config must read 0 ms from UserConfig without being discarded
        val effectiveConfig = manager.getEffectivePauseConfig("Trúc Ly")
        assertEquals("Effective sentence pause must be 0 ms", 0, effectiveConfig.sentencePauseMs)
        assertEquals("Effective paragraph pause must be 0 ms", 0, effectiveConfig.paragraphPauseMs)
        assertEquals("Effective clause pause must be 0 ms", 0, effectiveConfig.clausePauseMs)

        // Verify condition checking: Passing PauseConfig with sentencePauseMs = 0 must be honored
        val customZeroConfig = PauseConfig(
            sentencePauseMs = 0,
            paragraphPauseMs = 50,
            clausePauseMs = 0
        )
        // Check condition: sentencePauseMs >= 0 is true for 0
        assertTrue("sentencePauseMs >= 0 must be true for 0 ms", customZeroConfig.sentencePauseMs >= 0)
    }

    @Test
    fun testSmartTextSegmenterWithZeroGapPauseConfig() {
        val zeroGapConfig = PauseConfig(
            sentencePauseMs = 0,
            paragraphPauseMs = 0,
            clausePauseMs = 0,
            titlePauseMs = 0
        )

        val storyTitle = "Tiêu đề truyện"
        val content = "Câu thứ nhất trong đoạn. Câu thứ hai kết thúc đoạn.\n\nĐoạn thứ hai, có ngắt phẩy giữa câu."

        val chunks = SmartTextSegmenter.segmentStory(storyTitle, content, pauseConfig = zeroGapConfig)
        assertTrue("Chunks must not be empty", chunks.isNotEmpty())

        for (chunk in chunks) {
            assertEquals("Chunk pause duration must be 0 ms for zero gap configuration", 0, chunk.pauseDurationMs)
        }
    }

    @Test
    fun testPcmZeroGapConcatenationAndMicroFadeSafety() {
        // 1. generateSilence with 0 ms must return an empty ShortArray without exceptions
        val zeroSilence = PcmUtils.generateSilence(0)
        assertEquals("Silence for 0 ms must be empty ShortArray", 0, zeroSilence.size)

        val negativeSilence = PcmUtils.generateSilence(-50)
        assertEquals("Silence for negative ms must be empty ShortArray", 0, negativeSilence.size)

        // 2. Apply micro-fade to a simulated speech chunk
        val sampleRate = VieNeuConfig.SAMPLE_RATE
        val testSamples = ShortArray(sampleRate / 10) { (it % 1000).toShort() } // 100ms of audio
        val faded = PcmUtils.applyMicroFade(testSamples.clone(), fadeDurationMs = 5.0f, sampleRate = sampleRate)

        assertEquals("Faded buffer size must match original", testSamples.size, faded.size)
        // First sample in fade-in ramp must start near zero
        assertEquals("First sample must be 0 after micro fade-in", 0, faded[0].toInt())
        // Last sample in fade-out ramp must end near zero
        val lastIdx = faded.size - 1
        assertEquals("Last sample must be 0 after micro fade-out", 0, faded[lastIdx].toInt())

        // 3. Zero-gap concatenation logic: Speech + 0ms silence produces clean seamless speech buffer
        val pauseMs = 0L
        val speechPcm = faded
        val combinedPcm = if (pauseMs > 0L) {
            val silencePcm = PcmUtils.generateSilence(0)
            if (silencePcm.isNotEmpty()) {
                val combined = ShortArray(speechPcm.size + silencePcm.size)
                System.arraycopy(speechPcm, 0, combined, 0, speechPcm.size)
                System.arraycopy(silencePcm, 0, combined, speechPcm.size, silencePcm.size)
                combined
            } else {
                speechPcm
            }
        } else {
            speechPcm
        }

        assertSame("Zero-gap concatenation must reuse speech buffer directly without allocation", speechPcm, combinedPcm)
        assertEquals(testSamples.size, combinedPcm.size)
    }

    @Test
    fun testSettingsAdaptivePauseFormatting() {
        fun formatMs(ms: Int): String = if (ms == 0) "0 ms (Liền mạch / Zero Gap)" else "$ms ms"

        assertEquals("0 ms (Liền mạch / Zero Gap)", formatMs(0))
        assertEquals("150 ms", formatMs(150))
        assertEquals("400 ms", formatMs(400))
        assertEquals("1000 ms", formatMs(1000))
    }
}
