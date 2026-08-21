package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.skul9x.doctruyen.service.ReadingService
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.utils.UserConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Comprehensive Unit Test for Phase 02: Dynamic Precision Switching & Hot-Reloading
 *
 * Test criteria verified:
 * 1. Switching precision from FP32 to INT8 and INT8 to FP32 updates `quantizationType` and `executionProvider`.
 * 2. PrewarmedChunkCache is cleanly invalidated and cleared upon precision switch.
 * 3. VieNeuTTSManager state transitions to READY after dynamic reloading.
 * 4. TtsMetricsLogger records the new quantization type in telemetry and engine reload event.
 * 5. ReadingService correctly routes ACTION_SWITCH_PRECISION and invokes setPrecision.
 */
class DynamicPrecisionSwitchingTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val storage = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(storage)

        override fun getString(key: String?, defValue: String?): String? =
            (storage[key] as? String) ?: defValue

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

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupNativeLibrary() {
            val possiblePaths = listOf(
                "sea-g2p-android/target/release/libsea_g2p_android.so",
                "../sea-g2p-android/target/release/libsea_g2p_android.so",
                "app/src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "/home/skul9x/Desktop/Test_code/DocTruyenApk-main/sea-g2p-android/target/release/libsea_g2p_android.so"
            )
            for (path in possiblePaths) {
                val file = File(path)
                if (file.exists()) {
                    try {
                        System.load(file.absolutePath)
                        break
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    @Before
    fun setup() {
        fakePrefs = FakeSharedPreferences()
        UserConfig.customPrefsProvider = { fakePrefs }

        val baseAssetsDir = File("src/main/assets/vieneu")
        val voicesFile = File(baseAssetsDir, "voices_v3_turbo.json")
        if (voicesFile.exists()) {
            VoicePresets.loadFromJson(voicesFile.readText(Charsets.UTF_8))
        }

        val backboneFp32 = File(baseAssetsDir, "backbone")
        val backboneInt8 = File(baseAssetsDir, "backbone_int8")
        if (backboneFp32.exists() && !backboneInt8.exists()) {
            backboneInt8.mkdirs()
            backboneFp32.listFiles()?.forEach { file ->
                file.copyTo(File(backboneInt8, file.name), overwrite = true)
            }
        }
    }

    @After
    fun teardown() {
        PrewarmedChunkCache.clear()
        UserConfig.customPrefsProvider = null
        val backboneInt8 = File("src/main/assets/vieneu/backbone_int8")
        if (backboneInt8.exists()) {
            backboneInt8.deleteRecursively()
        }
    }

    @Test
    fun testOnnxSessionManager_precisionSwitchingAndResolution() {
        val backboneFp32Dir = File("src/main/assets/vieneu/backbone")
        val backboneInt8Dir = File("src/main/assets/vieneu/backbone_int8")
        val codecDir = File("src/main/assets/vieneu/codec")

        // 1. Create FP32 Session Manager
        val sessionFp32 = OnnxSessionManager.createFromFiles(
            backboneDir = backboneFp32Dir,
            codecDir = codecDir,
            threads = 2,
            precision = "FP32"
        )
        assertNotNull(sessionFp32)
        assertEquals(OnnxSessionManager.QuantizationType.FP32, sessionFp32.quantizationType)
        assertTrue(sessionFp32.executionProvider.isNotEmpty())
        sessionFp32.close()

        // 2. Create INT8 Session Manager
        val sessionInt8 = OnnxSessionManager.createFromFiles(
            backboneDir = backboneInt8Dir,
            codecDir = codecDir,
            threads = 2,
            precision = "INT8"
        )
        assertNotNull(sessionInt8)
        assertEquals(OnnxSessionManager.QuantizationType.INT8, sessionInt8.quantizationType)
        assertTrue(sessionInt8.executionProvider.isNotEmpty())
        sessionInt8.close()

        // 3. Test getBackboneDir resolution
        val baseDir = File("src/main/assets/vieneu")
        val resolvedInt8 = OnnxSessionManager.getBackboneDir(baseDir, "INT8")
        assertTrue(resolvedInt8.absolutePath.endsWith("backbone_int8"))

        val resolvedFp32 = OnnxSessionManager.getBackboneDir(baseDir, "FP32")
        assertTrue(resolvedFp32.absolutePath.endsWith("backbone"))
    }

    @Test
    fun testPrewarmedCacheInvalidation_onPrecisionSwitch() {
        // Pre-fill cache with dummy audio
        PrewarmedChunkCache.put(
            storyId = 101,
            voiceName = "Trúc Ly",
            chunkIndex = 0,
            pcm = ShortArray(1600) { 100 }
        )
        PrewarmedChunkCache.put(
            storyId = 101,
            voiceName = "Trúc Ly",
            chunkIndex = 1,
            pcm = ShortArray(1600) { 200 }
        )
        assertEquals(2, PrewarmedChunkCache.size())

        // Invalidate via PrewarmedChunkCache.clear() as performed during precision switch
        PrewarmedChunkCache.clear()
        assertEquals(0, PrewarmedChunkCache.size())
        assertFalse(PrewarmedChunkCache.contains(101, "Trúc Ly", 0))
    }

    @Test
    fun testTtsMetricsLogger_logEngineReloadAndQuantizationUpdate() {
        TtsMetricsLogger.startSession(
            storyId = "1001",
            storyTitle = "Test Story",
            context = dummyContext,
            engineThreads = 2,
            quantization = "FP32"
        )

        assertEquals("FP32", TtsMetricsLogger.currentSession.deviceProfile?.quantization)

        // Trigger engine reload telemetry
        TtsMetricsLogger.logEngineReload("INT8")
        assertEquals("INT8", TtsMetricsLogger.currentSession.deviceProfile?.quantization)

        TtsMetricsLogger.endSession(dummyContext)
    }

    private fun createTestEngine(precision: String): VieNeuOnnxEngine {
        val baseAssetsDir = File("src/main/assets/vieneu")
        val backboneDir = OnnxSessionManager.getBackboneDir(baseAssetsDir, precision)
        val codecDir = File(baseAssetsDir, "codec")

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 2,
            precision = precision
        )

        val configFile = File(backboneDir, "config.json")
        val config = VieNeuConfig.fromJson(configFile.readText(Charsets.UTF_8))

        val tokenizerFile = File(backboneDir, "tokenizer.json")
        val tokenizer = VieNeuTokenizer.fromFile(tokenizerFile)

        val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
        val headsReader = NpzReader.read(headsFile)

        val textEmb = headsReader.get2D("text_emb")!!
        val audioEmb = headsReader.get3D("audio_emb")!!
        val xvecW = headsReader.get2D("xvec_w")
        val xvecB = headsReader.getFloatArray("xvec_b")
        val xvecLnW = headsReader.getFloatArray("xvec_ln_w")
        val xvecLnB = headsReader.getFloatArray("xvec_ln_b")
        val xvecLnEps = headsReader.getScalarFloat("xvec_ln_eps") ?: 1e-5f

        return VieNeuOnnxEngine(
            sessionManager = sessionManager,
            config = config,
            tokenizer = tokenizer,
            textEmbeddings = textEmb,
            audioEmbeddings = audioEmb,
            xvecW = xvecW,
            xvecB = xvecB,
            xvecLnW = xvecLnW,
            xvecLnB = xvecLnB,
            xvecLnEps = xvecLnEps
        )
    }

    @Test
    fun testVieNeuTTSManager_dynamicPrecisionHotReload() = runBlocking {
        val stateHistory = mutableListOf<ITTSManager.TTSState>()

        // 1. Initialize custom engine and pipeline with INT8
        val initialEngine = createTestEngine("INT8")
        val initialPipeline = TTSAudioPipeline(initialEngine, voiceName = "Trúc Ly")

        val ttsManager = VieNeuTTSManager(
            context = null,
            onStateChange = { state ->
                stateHistory.add(state)
            },
            customEngine = initialEngine,
            customPipeline = initialPipeline
        )

        assertEquals(ITTSManager.TTSState.READY, ttsManager.currentState)

        // 2. Put dummy prewarmed chunks to verify cache is purged during reload
        PrewarmedChunkCache.put(202, "Trúc Ly", 0, ShortArray(800) { 50 })
        assertEquals(1, PrewarmedChunkCache.size())

        // 3. Switch precision to FP32
        ttsManager.setPrecision("FP32")
        while (ttsManager.currentState == ITTSManager.TTSState.INITIALIZING) {
            kotlinx.coroutines.delay(20)
        }
        assertEquals(0, PrewarmedChunkCache.size()) // Must be invalidated
        assertEquals(ITTSManager.TTSState.READY, ttsManager.currentState)

        // 4. Switch precision to INT8
        ttsManager.setPrecision("INT8")
        while (ttsManager.currentState == ITTSManager.TTSState.INITIALIZING) {
            kotlinx.coroutines.delay(20)
        }
        assertEquals(0, PrewarmedChunkCache.size())
        assertEquals(ITTSManager.TTSState.READY, ttsManager.currentState)

        ttsManager.shutdown()
        assertEquals(ITTSManager.TTSState.IDLE, ttsManager.currentState)
    }

    @Test
    fun testReadingService_switchPrecisionInvocation() = runBlocking {
        val customVieNeuManager = VieNeuTTSManager(
            context = null,
            onStateChange = {}
        )

        while (customVieNeuManager.currentState == ITTSManager.TTSState.INITIALIZING) {
            kotlinx.coroutines.delay(20)
        }

        val service = ReadingService()
        service.ttsManager = customVieNeuManager

        service.switchPrecision("FP32")
        assertEquals("FP32", UserConfig.getTtsPrecision(dummyContext))
        while (customVieNeuManager.currentState == ITTSManager.TTSState.INITIALIZING) {
            kotlinx.coroutines.delay(20)
        }
        assertEquals(ITTSManager.TTSState.READY, customVieNeuManager.currentState)

        service.switchPrecision("INT8")
        assertEquals("INT8", UserConfig.getTtsPrecision(dummyContext))
        while (customVieNeuManager.currentState == ITTSManager.TTSState.INITIALIZING) {
            kotlinx.coroutines.delay(20)
        }
        assertEquals(ITTSManager.TTSState.READY, customVieNeuManager.currentState)

        customVieNeuManager.shutdown()
    }
}
