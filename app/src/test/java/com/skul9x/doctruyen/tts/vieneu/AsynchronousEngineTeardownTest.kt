package com.skul9x.doctruyen.tts.vieneu

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.SharedPreferences
import com.skul9x.doctruyen.service.ReadingService
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.utils.UserConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Comprehensive Unit Test for Phase 03: Asynchronous Engine Teardown & Lifecycle ANR Elimination (P3)
 *
 * Verifies:
 * 1. `close()` invoked while `infer()` is holding engine lock does not freeze caller thread (< 100ms).
 * 2. Cancellation flag stops inference loop prematurely and releases resources cleanly without SIGSEGV.
 * 3. Safe concurrent teardown and re-initialization sequence without native deadlock.
 * 4. `ReadingService.onDestroy()` and `onTaskRemoved()` execute asynchronously off the Main thread without blocking.
 * 5. `VieNeuTTSManager.shutdown()` and `reloadEngine()` dispatch heavy teardown to Dispatchers.IO with zero System.gc() pauses.
 */
class AsynchronousEngineTeardownTest {

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

        val baseAssetsDir = getBaseAssetsDir()
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
    fun testCloseWhileInferringDoesNotFreezeCallerThread() {
        val engine = createTestEngine("INT8")
        val inferStartedLatch = CountDownLatch(1)
        val inferCompletedLatch = CountDownLatch(1)
        val inferExceptionRef = AtomicReference<Throwable?>()

        val backgroundThread = Thread {
            try {
                // Use reflection or start inference that holds the engine lock
                inferStartedLatch.countDown()
                engine.inferWithTiming(
                    phonemes = "t ôi1 l a2 m ô1 h i2 nh t r i1 t u e7 n h a n2 t a O4 v i e t7 n a m1",
                    voiceName = "Trúc Ly",
                    maxNewFrames = 100
                )
            } catch (t: Throwable) {
                inferExceptionRef.set(t)
            } finally {
                inferCompletedLatch.countDown()
            }
        }
        backgroundThread.isDaemon = true
        backgroundThread.start()

        // Wait until inference thread starts
        assertTrue("Inference thread should start", inferStartedLatch.await(2, TimeUnit.SECONDS))
        Thread.sleep(50) // Give inference loop time to start and acquire lock

        // Invoking close() while infer is running must return immediately without blocking caller
        val closeStart = System.currentTimeMillis()
        engine.close()
        val closeDuration = System.currentTimeMillis() - closeStart

        assertTrue("Caller thread duration for close() must be < 100ms (was ${closeDuration}ms)", closeDuration < 100)
        assertTrue("Engine must be marked as closed immediately", engine.isClosed)
        assertTrue("cancelRequested flag must be true", engine.cancelRequested)

        // Wait for background inference to conclude cleanly via cancellation flag
        assertTrue("Inference thread should abort early upon cancellation", inferCompletedLatch.await(3, TimeUnit.SECONDS))
        val exc = inferExceptionRef.get()
        assertNotNull("Inference should have thrown InterruptedException or CancellationException upon cancel", exc)
        assertTrue(
            "Exception must be InterruptedException or related cancellation",
            exc is InterruptedException || exc?.cause is InterruptedException
        )

        // Ensure asynchronous teardown completes cleanly without SIGSEGV
        val teardownFinished = engine.awaitClosed(5000)
        assertTrue("Engine background teardown should complete within deadline", teardownFinished)
        assertTrue("Session manager should be closed after teardown", engine.sessionManager.isClosed)
    }

    @Test
    fun testCancellationFlagStopsInferenceLoopPrematurely() {
        val engine = createTestEngine("INT8")
        assertFalse(engine.cancelRequested)

        // Manually request cancel
        engine.cancel()
        assertTrue(engine.cancelRequested)

        // Attempting to infer should abort immediately with InterruptedException
        try {
            engine.inferWithTiming(
                phonemes = "x i n1 ch a w2",
                voiceName = "Trúc Ly",
                maxNewFrames = 50
            )
            fail("Expected InterruptedException when inferring on cancelled engine")
        } catch (e: InterruptedException) {
            assertTrue("Message should indicate cancellation", e.message?.contains("cancelled") == true)
        }

        engine.close()
        assertTrue(engine.isClosed)
    }

    @Test
    fun testSafeConcurrentTeardownAndReinitialization() {
        val engine1 = createTestEngine("INT8")
        val inferStartedLatch = CountDownLatch(1)
        val inferDoneLatch = CountDownLatch(1)

        val t1 = Thread {
            try {
                inferStartedLatch.countDown()
                engine1.inferWithTiming(
                    phonemes = "c a w1 ch u y e n7 n a y2 r a t1 d a j2",
                    voiceName = "Trúc Ly",
                    maxNewFrames = 100
                )
            } catch (_: Throwable) {
                // Expected cancellation
            } finally {
                inferDoneLatch.countDown()
            }
        }
        t1.isDaemon = true
        t1.start()

        assertTrue(inferStartedLatch.await(2, TimeUnit.SECONDS))
        Thread.sleep(30)

        // Trigger teardown of Engine 1
        val teardownStart = System.currentTimeMillis()
        engine1.close()
        val teardownDuration = System.currentTimeMillis() - teardownStart
        assertTrue("Teardown call must return quickly (< 100ms)", teardownDuration < 100)

        // Immediately initialize Engine 2 concurrently
        val engine2 = createTestEngine("INT8")
        assertNotNull("Engine 2 should initialize cleanly concurrently", engine2)
        assertFalse("Engine 2 must not be closed", engine2.isClosed)

        // Engine 2 should be fully functional
        val res = engine2.inferWithTiming(
            phonemes = "m ô1 t1",
            voiceName = "Trúc Ly",
            maxNewFrames = 5
        )
        assertNotNull("Engine 2 should produce PCM output", res.pcm)
        assertTrue("PCM should not be empty", res.pcm.isNotEmpty())

        // Verify Engine 1 finished tearing down safely
        assertTrue(inferDoneLatch.await(3, TimeUnit.SECONDS))
        assertTrue(engine1.awaitClosed(3000))
        assertTrue(engine1.sessionManager.isClosed)

        // Clean up Engine 2
        engine2.close()
        assertTrue(engine2.isClosed)
    }

    @Test
    fun testReadingServiceLifecycleDestructionNonBlocking() {
        val engine = createTestEngine("INT8")
        val manager = VieNeuTTSManager(
            context = null,
            onStateChange = {},
            customEngine = engine
        )

        val service = ReadingService()
        service.ttsManager = manager

        // Verify destruction duration is fast and non-blocking (< 50ms)
        val destroyStart = System.currentTimeMillis()
        service.onDestroy()
        val destroyDuration = System.currentTimeMillis() - destroyStart

        assertTrue("ReadingService.onDestroy() must complete in < 50ms (was ${destroyDuration}ms)", destroyDuration < 50)
        assertNull("ttsManager reference must be cleared", service.ttsManager)
        assertEquals("PrewarmedChunkCache should be cleared", 0, PrewarmedChunkCache.size())

        // Also test onTaskRemoved
        val manager2 = VieNeuTTSManager(
            context = null,
            onStateChange = {}
        )
        service.ttsManager = manager2
        val taskRemovedStart = System.currentTimeMillis()
        service.onTaskRemoved(null)
        val taskRemovedDuration = System.currentTimeMillis() - taskRemovedStart

        assertTrue("ReadingService.onTaskRemoved() must complete in < 50ms (was ${taskRemovedDuration}ms)", taskRemovedDuration < 50)
        assertNull("ttsManager reference must be cleared", service.ttsManager)
    }

    @Test
    fun testVieNeuTTSManagerShutdownAndPrecisionSwitchDecoupled() = runBlocking {
        val engine = createTestEngine("INT8")
        val manager = VieNeuTTSManager(
            context = null,
            onStateChange = {},
            customEngine = engine
        )

        // Test shutdown duration
        val shutdownStart = System.currentTimeMillis()
        manager.shutdown()
        val shutdownDuration = System.currentTimeMillis() - shutdownStart

        assertTrue("VieNeuTTSManager.shutdown() must complete in < 50ms (was ${shutdownDuration}ms)", shutdownDuration < 50)
        assertEquals("State must be IDLE", ITTSManager.TTSState.IDLE, manager.currentState)
        assertNull("Engine reference must be nullified", manager.getEngineForTesting())

        // Wait briefly for IO coroutine to finish engine closing
        delay(100)
        assertTrue("Engine must be closed", engine.isClosed)
    }
}

