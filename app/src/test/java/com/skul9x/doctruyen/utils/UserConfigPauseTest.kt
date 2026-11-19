package com.skul9x.doctruyen.utils

import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.tts.vieneu.PauseConfig
import com.skul9x.doctruyen.tts.vieneu.PauseType
import com.skul9x.doctruyen.tts.vieneu.SmartTextSegmenter
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UserConfigPauseTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val storage = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(storage)

        override fun getString(key: String?, defValue: String?): String? {
            return (storage[key] as? String) ?: defValue
        }

        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            @Suppress("UNCHECKED_CAST")
            return (storage[key] as? MutableSet<String>) ?: defValues
        }

        override fun getInt(key: String?, defValue: Int): Int {
            return (storage[key] as? Int) ?: defValue
        }

        override fun getLong(key: String?, defValue: Long): Long {
            return (storage[key] as? Long) ?: defValue
        }

        override fun getFloat(key: String?, defValue: Float): Float {
            return (storage[key] as? Float) ?: defValue
        }

        override fun getBoolean(key: String?, defValue: Boolean): Boolean {
            return (storage[key] as? Boolean) ?: defValue
        }

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
    fun testDefaultPauseValues() {
        assertEquals(400, UserConfig.getPauseSentence(dummyContext))
        assertEquals(650, UserConfig.getPauseParagraph(dummyContext))
        assertEquals(180, UserConfig.getPauseClause(dummyContext))
        assertTrue(UserConfig.isDebugLoggingEnabled(dummyContext))
    }

    @Test
    fun testCustomPauseValuesClamped() {
        // Sentence pause clamping: min 0, max 1000
        UserConfig.setPauseSentence(dummyContext, -50)
        assertEquals(0, UserConfig.getPauseSentence(dummyContext))

        UserConfig.setPauseSentence(dummyContext, 1500)
        assertEquals(1000, UserConfig.getPauseSentence(dummyContext))

        UserConfig.setPauseSentence(dummyContext, 500)
        assertEquals(500, UserConfig.getPauseSentence(dummyContext))

        // Paragraph pause clamping: min 0, max 1500
        UserConfig.setPauseParagraph(dummyContext, -100)
        assertEquals(0, UserConfig.getPauseParagraph(dummyContext))

        UserConfig.setPauseParagraph(dummyContext, 3000)
        assertEquals(1500, UserConfig.getPauseParagraph(dummyContext))

        UserConfig.setPauseParagraph(dummyContext, 800)
        assertEquals(800, UserConfig.getPauseParagraph(dummyContext))

        // Clause pause clamping: min 0, max 500
        UserConfig.setPauseClause(dummyContext, -10)
        assertEquals(0, UserConfig.getPauseClause(dummyContext))

        UserConfig.setPauseClause(dummyContext, 900)
        assertEquals(500, UserConfig.getPauseClause(dummyContext))

        UserConfig.setPauseClause(dummyContext, 220)
        assertEquals(220, UserConfig.getPauseClause(dummyContext))
    }

    @Test
    fun testPausePresets() {
        // FAST preset
        UserConfig.applyPausePreset(dummyContext, "FAST")
        assertEquals(250, UserConfig.getPauseSentence(dummyContext))
        assertEquals(400, UserConfig.getPauseParagraph(dummyContext))
        assertEquals(100, UserConfig.getPauseClause(dummyContext))

        // EMOTIONAL preset
        UserConfig.applyPausePreset(dummyContext, "EMOTIONAL")
        assertEquals(600, UserConfig.getPauseSentence(dummyContext))
        assertEquals(900, UserConfig.getPauseParagraph(dummyContext))
        assertEquals(250, UserConfig.getPauseClause(dummyContext))

        // STANDARD preset
        UserConfig.applyPausePreset(dummyContext, "STANDARD")
        assertEquals(400, UserConfig.getPauseSentence(dummyContext))
        assertEquals(650, UserConfig.getPauseParagraph(dummyContext))
        assertEquals(180, UserConfig.getPauseClause(dummyContext))
    }

    @Test
    fun testDebugLoggingToggle() {
        UserConfig.setDebugLoggingEnabled(dummyContext, false)
        assertFalse(UserConfig.isDebugLoggingEnabled(dummyContext))

        UserConfig.setDebugLoggingEnabled(dummyContext, true)
        assertTrue(UserConfig.isDebugLoggingEnabled(dummyContext))
    }

    @Test
    fun testPauseValuesPropagateToSmartTextSegmenter() {
        // Set custom pause durations
        UserConfig.setPauseSentence(dummyContext, 550)
        UserConfig.setPauseParagraph(dummyContext, 950)
        UserConfig.setPauseClause(dummyContext, 210)

        val pauseConfig = PauseConfig(
            sentencePauseMs = UserConfig.getPauseSentence(dummyContext),
            paragraphPauseMs = UserConfig.getPauseParagraph(dummyContext),
            clausePauseMs = UserConfig.getPauseClause(dummyContext)
        )

        val storyTitle = "Tiêu đề truyện ngắn"
        val content = "Đây là câu thứ nhất của đoạn một. Còn đây là câu thứ hai kết thúc đoạn một.\n\nĐây là câu thuộc đoạn thứ hai, có ngắt dấu phẩy giữa câu."

        val chunks = SmartTextSegmenter.segmentStory(storyTitle, content, pauseConfig = pauseConfig)

        assertTrue(chunks.isNotEmpty())

        // Title chunk
        assertEquals("Tiêu đề truyện ngắn", chunks[0].text)
        assertEquals(PauseType.TITLE_BREAK, chunks[0].pauseType)
        assertEquals(1000, chunks[0].pauseDurationMs) // Default title pause is 1000ms

        // First sentence in paragraph 1
        val sent1 = chunks.find { it.text == "Đây là câu thứ nhất của đoạn một." }
        assertNotNull(sent1)
        assertEquals(PauseType.SENTENCE_BREAK, sent1!!.pauseType)
        assertEquals(550, sent1.pauseDurationMs)

        // End of paragraph 1
        val para1End = chunks.find { it.text == "Còn đây là câu thứ hai kết thúc đoạn một." }
        assertNotNull(para1End)
        assertEquals(PauseType.PARAGRAPH_BREAK, para1End!!.pauseType)
        assertEquals(950, para1End.pauseDurationMs)
    }
}
