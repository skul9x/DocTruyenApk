package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.utils.UserConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 01 Verification Test:
 * 1. Default precision in UserConfig is "INT8".
 * 2. UserConfig.setTtsPrecision stores and retrieves "INT8" and "FP32" correctly.
 * 3. Fallback to "FP32" when invalid/unrecognized precision strings are provided.
 * 4. OnnxSessionManager.detectQuantizationType correctly identifies INT8 for backbone_int8 models and FP32 for backbone models.
 * 5. OnnxSessionManager.getBackboneDir resolves appropriate precision directory with fallback.
 */
class PrecisionAssetAndConfigTest {

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
    fun testDefaultPrecisionIsInt8() {
        assertEquals("INT8", UserConfig.DEFAULT_TTS_PRECISION)
        assertEquals("INT8", UserConfig.getTtsPrecision(dummyContext))
    }

    @Test
    fun testPrecisionPersistenceAndNormalization() {
        // Test setting FP32
        UserConfig.setTtsPrecision(dummyContext, "FP32")
        assertEquals("FP32", UserConfig.getTtsPrecision(dummyContext))

        // Test lowercase fp32 normalized
        UserConfig.setTtsPrecision(dummyContext, "fp32")
        assertEquals("FP32", UserConfig.getTtsPrecision(dummyContext))

        // Test setting INT8
        UserConfig.setTtsPrecision(dummyContext, "INT8")
        assertEquals("INT8", UserConfig.getTtsPrecision(dummyContext))

        // Test lowercase int8 normalized
        UserConfig.setTtsPrecision(dummyContext, "int8")
        assertEquals("INT8", UserConfig.getTtsPrecision(dummyContext))
    }

    @Test
    fun testInvalidPrecisionFallback() {
        // Direct invalid input in setTtsPrecision -> falls back to FP32
        UserConfig.setTtsPrecision(dummyContext, "INVALID_PRECISION")
        assertEquals("FP32", UserConfig.getTtsPrecision(dummyContext))

        UserConfig.setTtsPrecision(dummyContext, "FLOAT64")
        assertEquals("FP32", UserConfig.getTtsPrecision(dummyContext))

        UserConfig.setTtsPrecision(dummyContext, "")
        assertEquals("FP32", UserConfig.getTtsPrecision(dummyContext))

        // Stored raw invalid value in SharedPreferences -> getTtsPrecision falls back to FP32
        fakePrefs.edit().putString(UserConfig.KEY_TTS_PRECISION, "UNKNOWN_VALUE").apply()
        assertEquals("FP32", UserConfig.getTtsPrecision(dummyContext))
    }

    @Test
    fun testModelQuantizationDetectionForBothAssetDirectories() {
        val rootDirs = listOf(
            File("src/main/assets/vieneu"),
            File("app/src/main/assets/vieneu")
        )
        val assetsDir = rootDirs.firstOrNull { it.exists() }
        assertNotNull("VieNeu assets directory must exist", assetsDir)

        val fp32BackboneDir = File(assetsDir, "backbone")
        val int8BackboneDir = File(assetsDir, "backbone_int8")

        assertTrue("FP32 backbone directory must exist", fp32BackboneDir.exists())

        // Verify FP32 models detection
        val fp32Prefill = File(fp32BackboneDir, "vieneu_prefill.onnx")
        val fp32Decode = File(fp32BackboneDir, "vieneu_decode_step.onnx")
        val fp32Acoustic = File(fp32BackboneDir, "vieneu_acoustic_cached.onnx")

        assertTrue("FP32 prefill model exists", fp32Prefill.exists())
        assertTrue("FP32 decode model exists", fp32Decode.exists())
        assertTrue("FP32 acoustic model exists", fp32Acoustic.exists())

        assertEquals(OnnxSessionManager.QuantizationType.FP32, OnnxSessionManager.detectQuantizationType(fp32Prefill.absolutePath))
        assertEquals(OnnxSessionManager.QuantizationType.FP32, OnnxSessionManager.detectQuantizationType(fp32Decode.absolutePath))
        assertEquals(OnnxSessionManager.QuantizationType.FP32, OnnxSessionManager.detectQuantizationType(fp32Acoustic.absolutePath))

        // Verify INT8 models detection (if generated on disk)
        if (int8BackboneDir.exists() && int8BackboneDir.list()?.isNotEmpty() == true) {
            val int8Prefill = File(int8BackboneDir, "vieneu_prefill.onnx")
            val int8Decode = File(int8BackboneDir, "vieneu_decode_step.onnx")
            val int8Acoustic = File(int8BackboneDir, "vieneu_acoustic_cached.onnx")

            assertTrue("INT8 prefill model exists", int8Prefill.exists())
            assertTrue("INT8 decode model exists", int8Decode.exists())
            assertTrue("INT8 acoustic model exists", int8Acoustic.exists())

            assertEquals(OnnxSessionManager.QuantizationType.INT8, OnnxSessionManager.detectQuantizationType(int8Prefill.absolutePath))
            assertEquals(OnnxSessionManager.QuantizationType.INT8, OnnxSessionManager.detectQuantizationType(int8Decode.absolutePath))
            assertEquals(OnnxSessionManager.QuantizationType.INT8, OnnxSessionManager.detectQuantizationType(int8Acoustic.absolutePath))

            // Ancillary files in int8 folder
            assertTrue(File(int8BackboneDir, "config.json").exists())
            assertTrue(File(int8BackboneDir, "tokenizer.json").exists())
            assertTrue(File(int8BackboneDir, "vieneu_v3_heads.npz").exists())
        }
    }

    @Test
    fun testBackboneDirectoryResolution() {
        val rootDirs = listOf(
            File("src/main/assets/vieneu"),
            File("app/src/main/assets/vieneu")
        )
        val baseDir = rootDirs.first { it.exists() }
        val int8Dir = File(baseDir, "backbone_int8")

        // INT8 precision resolves to backbone_int8 if present, otherwise fallback to backbone
        val resolvedInt8 = OnnxSessionManager.getBackboneDir(baseDir, "INT8")
        if (int8Dir.exists() && int8Dir.list()?.isNotEmpty() == true) {
            assertEquals(int8Dir.absolutePath, resolvedInt8.absolutePath)
        } else {
            assertEquals(File(baseDir, "backbone").absolutePath, resolvedInt8.absolutePath)
        }

        // FP32 precision resolves to backbone
        val resolvedFp32 = OnnxSessionManager.getBackboneDir(baseDir, "FP32")
        assertEquals(File(baseDir, "backbone").absolutePath, resolvedFp32.absolutePath)

        // Invalid precision falls back to backbone
        val resolvedFallback = OnnxSessionManager.getBackboneDir(baseDir, "INVALID")
        assertEquals(File(baseDir, "backbone").absolutePath, resolvedFallback.absolutePath)

        // When backbone_int8 doesn't exist, fallback to backbone
        val dummyEmptyDir = File(System.getProperty("java.io.tmpdir"), "dummy_vieneu_test_${System.currentTimeMillis()}")
        dummyEmptyDir.mkdirs()
        try {
            val fallbackWhenMissing = OnnxSessionManager.getBackboneDir(dummyEmptyDir, "INT8")
            assertEquals(File(dummyEmptyDir, "backbone").absolutePath, fallbackWhenMissing.absolutePath)
        } finally {
            dummyEmptyDir.deleteRecursively()
        }
    }
}
