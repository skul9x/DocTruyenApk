package com.skul9x.doctruyen

import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.utils.UserConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SettingsActivityLogicTest {

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
    fun testEngineSwitchPersistence() {
        // Default engine is VIENEU
        assertEquals(ITTSManager.EngineType.VIENEU, UserConfig.getTtsEngine(dummyContext))

        // Switch to GOOGLE_TTS
        UserConfig.setTtsEngine(dummyContext, ITTSManager.EngineType.GOOGLE_TTS)
        assertEquals(ITTSManager.EngineType.GOOGLE_TTS, UserConfig.getTtsEngine(dummyContext))

        // Switch back to VIENEU
        UserConfig.setTtsEngine(dummyContext, ITTSManager.EngineType.VIENEU)
        assertEquals(ITTSManager.EngineType.VIENEU, UserConfig.getTtsEngine(dummyContext))
    }

    @Test
    fun testIndependentVoiceSelectionPersistence() {
        // Test VieNeu voice saving
        assertNull(UserConfig.getTtsVoice(dummyContext))
        UserConfig.setTtsVoice(dummyContext, "Minh Đức")
        assertEquals("Minh Đức", UserConfig.getTtsVoice(dummyContext))

        // Test Google TTS voice saving
        assertNull(UserConfig.getGoogleTtsVoice(dummyContext))
        UserConfig.setGoogleTtsVoice(dummyContext, "vi-vn-x-vic-network")
        assertEquals("vi-vn-x-vic-network", UserConfig.getGoogleTtsVoice(dummyContext))

        // Ensure both persist independently without overriding each other
        assertEquals("Minh Đức", UserConfig.getTtsVoice(dummyContext))
        assertEquals("vi-vn-x-vic-network", UserConfig.getGoogleTtsVoice(dummyContext))
    }

    @Test
    fun testPitchAndSpeedReset() {
        UserConfig.setTtsPitch(dummyContext, 1.6f)
        UserConfig.setTtsSpeed(dummyContext, 0.8f)
        assertEquals(1.6f, UserConfig.getTtsPitch(dummyContext), 0.001f)
        assertEquals(0.8f, UserConfig.getTtsSpeed(dummyContext), 0.001f)

        // Reset to default
        UserConfig.setTtsPitch(dummyContext, 1.0f)
        UserConfig.setTtsSpeed(dummyContext, 1.0f)
        assertEquals(1.0f, UserConfig.getTtsPitch(dummyContext), 0.001f)
        assertEquals(1.0f, UserConfig.getTtsSpeed(dummyContext), 0.001f)
    }

    @Test
    fun testPausePresetsApplication() {
        // Standard preset
        UserConfig.applyPausePreset(dummyContext, "STANDARD")
        assertEquals(400, UserConfig.getPauseSentence(dummyContext))
        assertEquals(650, UserConfig.getPauseParagraph(dummyContext))
        assertEquals(180, UserConfig.getPauseClause(dummyContext))

        // Fast preset
        UserConfig.applyPausePreset(dummyContext, "FAST")
        assertEquals(250, UserConfig.getPauseSentence(dummyContext))
        assertEquals(400, UserConfig.getPauseParagraph(dummyContext))
        assertEquals(100, UserConfig.getPauseClause(dummyContext))

        // Emotional preset
        UserConfig.applyPausePreset(dummyContext, "EMOTIONAL")
        assertEquals(600, UserConfig.getPauseSentence(dummyContext))
        assertEquals(900, UserConfig.getPauseParagraph(dummyContext))
        assertEquals(250, UserConfig.getPauseClause(dummyContext))
    }
}
