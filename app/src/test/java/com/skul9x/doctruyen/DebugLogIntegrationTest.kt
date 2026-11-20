package com.skul9x.doctruyen

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType
import com.skul9x.doctruyen.utils.UserConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DebugLogIntegrationTest {

    private class FakeEditor(private val storage: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        private val toRemove = mutableSetOf<String>()
        private var shouldClear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) temp[key] = value
            return this
        }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) temp[key] = values
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
            if (key != null) toRemove.add(key)
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            shouldClear = true
            return this
        }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (shouldClear) storage.clear()
            for (key in toRemove) storage.remove(key)
            storage.putAll(temp)
            temp.clear()
            toRemove.clear()
            shouldClear = false
        }
    }

    private class FakeSharedPreferences : SharedPreferences {
        val storage = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = storage
        override fun getString(key: String?, defValue: String?): String? = (storage[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (storage[key] as? MutableSet<String>) ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (storage[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (storage[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (storage[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (storage[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = storage.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(storage)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private val fakePrefs = FakeSharedPreferences()
    private val dummyContext: Context = object : ContextWrapper(null) {}
    private var originalDebug = true

    @Before
    fun setUp() {
        originalDebug = DebugLogger.isDebugEnabled
        UserConfig.customPrefsProvider = { fakePrefs }
        DebugLogger.clear()
    }

    @After
    fun tearDown() {
        DebugLogger.isDebugEnabled = originalDebug
        DebugLogger.clear()
        UserConfig.customPrefsProvider = null
    }

    @Test
    fun testDebugLogger_initializationAndUserConfigSync() {
        // Given logging is enabled by default in UserConfig
        UserConfig.setDebugLoggingEnabled(dummyContext, true)
        DebugLogger.init(dummyContext)
        assertTrue("DebugLogger.isDebugEnabled should be true after init with enabled preference", DebugLogger.isDebugEnabled)

        // Log an info message
        DebugLogger.log("TAG_TEST", "Test info message", LogType.INFO)
        assertTrue("Logs should contain the test message", DebugLogger.getFormattedLogs().contains("Test info message"))

        // When user disables debug logging
        UserConfig.setDebugLoggingEnabled(dummyContext, false)
        assertFalse("DebugLogger.isDebugEnabled should immediately sync to false", DebugLogger.isDebugEnabled)

        // Clear and attempt to log while disabled
        DebugLogger.clear()
        DebugLogger.log("TAG_TEST", "Suppressed message", LogType.INFO)
        DebugLogger.logError("TAG_TEST", "Suppressed error")
        assertEquals("Logs should remain empty when logging is disabled", "", DebugLogger.getFormattedLogs())

        // Re-enable via UserConfig
        UserConfig.setDebugLoggingEnabled(dummyContext, true)
        assertTrue("DebugLogger.isDebugEnabled should immediately sync to true", DebugLogger.isDebugEnabled)

        DebugLogger.log("TAG_TEST", "Resumed message", LogType.INFO)
        assertTrue("Logs should record after re-enabling", DebugLogger.getFormattedLogs().contains("Resumed message"))
    }

    @Test
    fun testDebugLogActivity_extraFilterConstant() {
        assertEquals("EXTRA_FILTER", DebugLogActivity.EXTRA_FILTER)
    }

    @Test
    fun testDebugLogger_filterByType() {
        DebugLogger.isDebugEnabled = true
        DebugLogger.log("TTS", "Timing metric 1", LogType.TTS_TIMING)
        DebugLogger.log("NETWORK", "Network error", LogType.ERROR)
        DebugLogger.log("UI", "User clicked", LogType.INFO)

        val timingLogs = DebugLogger.getFormattedLogs(LogType.TTS_TIMING)
        assertTrue(timingLogs.contains("Timing metric 1"))
        assertFalse(timingLogs.contains("Network error"))
        assertFalse(timingLogs.contains("User clicked"))

        val errorLogs = DebugLogger.getFormattedLogs(LogType.ERROR)
        assertTrue(errorLogs.contains("Network error"))
        assertFalse(errorLogs.contains("Timing metric 1"))

        val allLogs = DebugLogger.getFormattedLogs(null)
        assertTrue(allLogs.contains("Timing metric 1"))
        assertTrue(allLogs.contains("Network error"))
        assertTrue(allLogs.contains("User clicked"))
    }
}
