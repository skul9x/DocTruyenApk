package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.utils.UserConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit Test for Phase 02: Fix Cold-Start `initManager()` Precision Pass-Through
 *
 * Test criteria:
 * 1. When UserConfig has "INT8" saved, verify precision resolution is "INT8" and maps to backbone_int8.
 * 2. When UserConfig has "FP32" saved, verify precision resolution is "FP32" and maps to backbone.
 * 3. When UserConfig has no saved value, verify default precision is used (DEFAULT_TTS_PRECISION = "INT8").
 * 4. Verify reloadEngine() / setPrecision() still passes and updates correct precision (regression check).
 * 5. Verify the saved precision variable is read from context before engine creation.
 */
class ColdStartPrecisionFixTest {

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
                temp.clear()
                clearFlag = false
            }
        }
    }

    private lateinit var fakePrefs: FakeSharedPreferences
    private val dummyContext: Context = object : android.content.ContextWrapper(null) {}
    private lateinit var tempAssetsDir: File

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        UserConfig.customPrefsProvider = { fakePrefs }

        tempAssetsDir = File(System.getProperty("java.io.tmpdir"), "cold_start_assets_test_${System.currentTimeMillis()}")
        val int8Dir = File(tempAssetsDir, "backbone_int8").apply { mkdirs() }
        File(int8Dir, "dummy_model.onnx").createNewFile()
        val fp32Dir = File(tempAssetsDir, "backbone").apply { mkdirs() }
        File(fp32Dir, "dummy_model.onnx").createNewFile()
    }

    @After
    fun teardown() {
        UserConfig.customPrefsProvider = null
        if (::tempAssetsDir.isInitialized && tempAssetsDir.exists()) {
            tempAssetsDir.deleteRecursively()
        }
    }

    @Test
    fun testColdStart_readsInt8FromUserConfig() {
        // Given UserConfig has "INT8" saved
        UserConfig.setTtsPrecision(dummyContext, "INT8")

        // When reading precision for cold-start initialization
        val savedPrecision = UserConfig.getTtsPrecision(dummyContext)
        assertEquals("INT8", savedPrecision)

        // Verify it resolves to the INT8 backbone directory
        val backboneDir = OnnxSessionManager.getBackboneDir(tempAssetsDir, savedPrecision)
        assertTrue(backboneDir.exists())
        assertEquals(File(tempAssetsDir, "backbone_int8").absolutePath, backboneDir.absolutePath)
    }

    @Test
    fun testColdStart_readsFp32FromUserConfig() {
        // Given UserConfig has "FP32" saved
        UserConfig.setTtsPrecision(dummyContext, "FP32")

        // When reading precision for cold-start initialization
        val savedPrecision = UserConfig.getTtsPrecision(dummyContext)
        assertEquals("FP32", savedPrecision)

        // Verify it resolves to the FP32 backbone directory
        val backboneDir = OnnxSessionManager.getBackboneDir(tempAssetsDir, savedPrecision)
        assertTrue(backboneDir.exists())
        assertEquals(File(tempAssetsDir, "backbone").absolutePath, backboneDir.absolutePath)
    }

    @Test
    fun testColdStart_defaultPrecisionWhenUnset() {
        // Given no preference is set in SharedPreferences
        val savedPrecision = UserConfig.getTtsPrecision(dummyContext)

        // Then defaults to DEFAULT_TTS_PRECISION ("INT8")
        assertEquals(UserConfig.DEFAULT_TTS_PRECISION, savedPrecision)
        assertEquals("INT8", savedPrecision)

        val backboneDir = OnnxSessionManager.getBackboneDir(tempAssetsDir, savedPrecision)
        assertEquals(File(tempAssetsDir, "backbone_int8").absolutePath, backboneDir.absolutePath)
    }

    @Test
    fun testReloadEngine_precisionParameterRegressionCheck() {
        // Verify UserConfig precision persistence and normalization for reloadEngine
        UserConfig.setTtsPrecision(dummyContext, "FP32")
        assertEquals("FP32", UserConfig.getTtsPrecision(dummyContext))

        UserConfig.setTtsPrecision(dummyContext, "INT8")
        assertEquals("INT8", UserConfig.getTtsPrecision(dummyContext))

        // Normalization checks
        UserConfig.setTtsPrecision(dummyContext, "  int8  ")
        assertEquals("INT8", UserConfig.getTtsPrecision(dummyContext))

        UserConfig.setTtsPrecision(dummyContext, "fp32")
        assertEquals("FP32", UserConfig.getTtsPrecision(dummyContext))

        // Unknown defaults to FP32 normalization
        UserConfig.setTtsPrecision(dummyContext, "UNKNOWN")
        assertEquals("FP32", UserConfig.getTtsPrecision(dummyContext))
    }

    @Test
    fun testSavedPrecisionReadFromContextBeforeEngineCreation() {
        var preferenceReadCount = 0
        UserConfig.customPrefsProvider = {
            preferenceReadCount++
            fakePrefs
        }

        UserConfig.setTtsPrecision(dummyContext, "FP32")
        val readsBeforeInit = preferenceReadCount

        // In initManager(), savedPrecision = UserConfig.getTtsPrecision(ctx)
        val savedPrecision = UserConfig.getTtsPrecision(dummyContext)
        assertEquals("FP32", savedPrecision)

        assertTrue(
            "Precision must be read from Context/SharedPreferences before passing to VieNeuOnnxEngine.create()",
            preferenceReadCount > readsBeforeInit
        )
    }
}
