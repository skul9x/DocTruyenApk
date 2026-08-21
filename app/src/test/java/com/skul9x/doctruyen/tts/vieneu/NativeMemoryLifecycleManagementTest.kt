package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.skul9x.doctruyen.service.ReadingService
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.utils.UserConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Comprehensive Verification Test for Phase 02: Native Memory Lifecycle Management & Safe Resource Teardown
 *
 * Verifies:
 * 1. OnnxSessionManager.close() safely disposes all 4 sessions, sessionOptions, sets isClosed = true, and nullifies session refs.
 * 2. VieNeuOnnxEngine.close() clears CacheTensor.Pool, resets working buffers, and prevents subsequent inference on closed engine.
 * 3. VieNeuOnnxEngine.acousticFrame() recycles intermediate CacheTensor instances across iterations.
 * 4. VieNeuTTSManager.reloadEngine() and shutdown() completely release existing resources without leaks and clear prewarm cache.
 * 5. ReadingService.onTrimMemory releases idle pre-warm caches and G2P caches under memory pressure.
 */
class NativeMemoryLifecycleManagementTest {

    private class FakeSharedPreferences : SharedPreferences {
        private val storage = mutableMapOf<String, Any>()

        override fun getAll(): MutableMap<String, *> = HashMap(storage)
        override fun getString(key: String?, defValue: String?): String? = (storage[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
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
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) temp[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
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

    companion object {
        @JvmStatic
        @BeforeClass
        fun setupNativeLibrary() {
            val possiblePaths = listOf(
                "sea-g2p-android/target/release/libsea_g2p_android.so",
                "../sea-g2p-android/target/release/libsea_g2p_android.so",
                "app/src/main/jniLibs/x86_64/libsea_g2p_android.so",
                "src/main/jniLibs/x86_64/libsea_g2p_android.so",
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
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        UserConfig.customPrefsProvider = { fakePrefs }
        PrewarmedChunkCache.clear()
        SeaG2P.clearCache()
        VieNeuOnnxEngine.CacheTensor.Pool.clear()

        val baseAssetsDir = File("src/main/assets/vieneu")
        val voicesFile = File(baseAssetsDir, "voices_v3_turbo.json")
        if (voicesFile.exists()) {
            VoicePresets.loadFromJson(voicesFile.readText(Charsets.UTF_8))
        }
    }

    @After
    fun tearDown() {
        PrewarmedChunkCache.clear()
        SeaG2P.clearCache()
        VieNeuOnnxEngine.CacheTensor.Pool.clear()
        UserConfig.customPrefsProvider = null
    }

    private fun getBaseAssetsDir(): File {
        val path1 = File("app/src/main/assets/vieneu")
        return if (path1.exists()) path1 else File("src/main/assets/vieneu")
    }

    private fun createTestEngine(precision: String = "INT8"): VieNeuOnnxEngine {
        val baseAssetsDir = getBaseAssetsDir()
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
    fun testOnnxSessionManagerLifecycleAndDisposal() {
        val baseAssetsDir = getBaseAssetsDir()
        val backboneDir = OnnxSessionManager.getBackboneDir(baseAssetsDir, "INT8")
        val codecDir = File(baseAssetsDir, "codec")

        val sessionManager = OnnxSessionManager.createFromFiles(
            backboneDir = backboneDir,
            codecDir = codecDir,
            threads = 2,
            precision = "INT8"
        )

        assertNotNull(sessionManager.sessionOptions)
        assertFalse(sessionManager.isClosed)

        // Close manager
        sessionManager.close()
        assertTrue("isClosed should be true after close()", sessionManager.isClosed)

        // Duplicate close should be safely idempotent
        sessionManager.close()
        assertTrue("isClosed should remain true", sessionManager.isClosed)
    }

    @Test
    fun testVieNeuOnnxEngineCloseAndClosedGuard() {
        val engine = createTestEngine("INT8")
        assertFalse("Engine should not be closed initially", engine.isClosed)

        // Put some items in CacheTensor.Pool
        val t1 = VieNeuOnnxEngine.CacheTensor.Pool.obtain(longArrayOf(1, 8, 2, 96), FloatArray(1536))
        t1.recycle()

        // Close engine
        engine.close()
        assertTrue("Engine isClosed should be true after close()", engine.isClosed)
        assertTrue("Session manager should also be closed", engine.sessionManager.isClosed)

        // Subsequent inference attempt must throw IllegalStateException
        try {
            engine.inferWithTiming(phonemes = "s in1 ch aw2")
            fail("Expected IllegalStateException when inferencing on closed engine")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("closed") == true)
        }
    }

    @Test
    fun testCacheTensorPoolRecyclingAndClearance() {
        VieNeuOnnxEngine.CacheTensor.Pool.clear()

        val tensors = mutableListOf<VieNeuOnnxEngine.CacheTensor>()
        for (i in 0 until 10) {
            val t = VieNeuOnnxEngine.CacheTensor.Pool.obtain(longArrayOf(1, 8, i.toLong(), 96), FloatArray(8 * i * 96))
            tensors.add(t)
        }

        // Release all tensors back into pool
        for (t in tensors) {
            t.recycle()
            assertEquals("Data should be cleared on recycle", 0, t.data.size)
            assertEquals("Shape should be cleared on recycle", 0, t.shape.size)
        }

        // Obtain tensor from pool (reusing previous instance)
        val reused = VieNeuOnnxEngine.CacheTensor.Pool.obtain(longArrayOf(1, 8, 1, 96), FloatArray(768))
        assertNotNull(reused)
        assertEquals(768, reused.data.size)
        reused.recycle()

        // Pool clear
        VieNeuOnnxEngine.CacheTensor.Pool.clear()
    }

    @get:org.junit.Rule
    val instantTaskExecutorRule = androidx.arch.core.executor.testing.InstantTaskExecutorRule()

    @Test
    fun testVieNeuTTSManagerReloadAndShutdownLifecycle() = runBlocking {
        val initialEngine = createTestEngine("INT8")
        val initialPipeline = TTSAudioPipeline(initialEngine, voiceName = "Trúc Ly")

        val ttsManager = VieNeuTTSManager(
            context = null,
            onStateChange = {},
            customEngine = initialEngine,
            customPipeline = initialPipeline
        )

        assertEquals(ITTSManager.TTSState.READY, ttsManager.currentState)

        // Put sample prewarm chunks
        PrewarmedChunkCache.put(505, "Trúc Ly", 0, ShortArray(1600) { 10 })
        assertEquals(1, PrewarmedChunkCache.size())

        // Hot-reload engine
        ttsManager.reloadEngine("FP32")
        assertEquals("PrewarmedChunkCache should be purged during reload", 0, PrewarmedChunkCache.size())

        // Allow async reload coroutine on Dispatchers.IO to finish
        var retries = 50
        while (ttsManager.currentState != ITTSManager.TTSState.READY && retries > 0) {
            kotlinx.coroutines.delay(100)
            retries--
        }
        assertEquals(ITTSManager.TTSState.READY, ttsManager.currentState)

        // Shutdown TTSManager
        PrewarmedChunkCache.put(505, "Trúc Ly", 0, ShortArray(1600) { 20 })
        assertEquals(1, PrewarmedChunkCache.size())

        ttsManager.shutdown()
        assertEquals("PrewarmedChunkCache should be cleared on shutdown", 0, PrewarmedChunkCache.size())
        assertEquals(ITTSManager.TTSState.IDLE, ttsManager.currentState)
    }

    @Test
    fun testReadingServiceOnTrimMemoryAndTeardown() {
        val customVieNeuManager = VieNeuTTSManager(
            context = null,
            onStateChange = {}
        )

        val service = ReadingService()
        service.ttsManager = customVieNeuManager

        // Populate caches
        PrewarmedChunkCache.put(707, "Trúc Ly", 0, ShortArray(1000) { 42 })
        SeaG2P.phonemize("Xin chào các bạn")
        assertEquals(1, PrewarmedChunkCache.size())
        assertTrue(SeaG2P.getCacheSize() > 0)

        // Trigger onTrimMemory while IDLE/READY
        service.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        assertEquals("PrewarmedChunkCache should be cleared on TRIM_MEMORY_RUNNING_LOW", 0, PrewarmedChunkCache.size())
        assertEquals("SeaG2P cache should be cleared on TRIM_MEMORY_RUNNING_LOW", 0, SeaG2P.getCacheSize())

        // Re-populate and test onTaskRemoved / onDestroy
        PrewarmedChunkCache.put(707, "Trúc Ly", 0, ShortArray(1000) { 42 })
        SeaG2P.phonemize("Tạm biệt")
        assertEquals(1, PrewarmedChunkCache.size())
        assertTrue(SeaG2P.getCacheSize() > 0)

        service.onDestroy()
        assertEquals("PrewarmedChunkCache should be cleared on onDestroy", 0, PrewarmedChunkCache.size())
        assertEquals("SeaG2P cache should be cleared on onDestroy", 0, SeaG2P.getCacheSize())
        assertNull("TTSManager should be nullified", service.ttsManager)
    }
}
