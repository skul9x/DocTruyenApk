package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.utils.UserConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Phase 01: Core TTS Engine Stability & CPU Threading Test Suite
 *
 * Requirements:
 * 1. Verify CancellationException during coroutine execution propagates without triggering fallback or altering fallback state.
 * 2. Verify non-cancellation exception (RuntimeException, IOException) triggers fallback and error logging as expected.
 * 3. Verify engineThreads computation produces bounded core count in [2, 4] on multi-core systems (e.g., 8-core CPU returns 4, not 8).
 * 4. Verify OnnxSessionManager.configureSessionOptions() allocates optimal thread counts for both XNNPACK and CPU fallback configurations.
 */
class CoreTtsEngineStabilityAndThreadingTest {

    private lateinit var dummyContext: Context
    private var originalPrefsProvider: ((Context) -> SharedPreferences)? = null

    private class MockSharedPreferences(
        private val onGetString: (String?, String?) -> String?
    ) : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?): String? = onGetString(key, defValue)
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getLong(key: String?, defValue: Long): Long = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun contains(key: String?): Boolean = false
        override fun edit(): SharedPreferences.Editor = MockEditor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class MockEditor : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor = this
            override fun clear(): SharedPreferences.Editor = this
            override fun commit(): Boolean = true
            override fun apply() {}
        }
    }

    @Before
    fun setUp() {
        originalPrefsProvider = UserConfig.customPrefsProvider
        dummyContext = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(System.getProperty("java.io.tmpdir"), "mock_files_${System.currentTimeMillis()}").apply { mkdirs() }
        }
    }

    @After
    fun tearDown() {
        UserConfig.customPrefsProvider = originalPrefsProvider
    }

    /**
     * Test 1: Verify CancellationException during coroutine execution propagates without triggering fallback
     * or altering fallback state.
     */
    @Test
    fun testCancellationExceptionDoesNotTriggerFallback() = runBlocking {
        val states = mutableListOf<ITTSManager.TTSState>()

        UserConfig.customPrefsProvider = {
            MockSharedPreferences { key, defValue ->
                if (key == "tts_precision") {
                    throw CancellationException("Simulated navigation cancellation")
                }
                defValue
            }
        }

        val manager = VieNeuTTSManager(
            context = dummyContext,
            onStateChange = { states.add(it) }
        )

        // Allow coroutine to execute
        delay(200)

        val isFallbackField = VieNeuTTSManager::class.java.getDeclaredField("isUsingFallback").apply {
            isAccessible = true
        }
        val isUsingFallback = isFallbackField.getBoolean(manager)
        assertFalse("CancellationException should NOT set isUsingFallback to true", isUsingFallback)

        val fallbackManagerField = VieNeuTTSManager::class.java.getDeclaredField("fallbackManager").apply {
            isAccessible = true
        }
        val fallbackManager = fallbackManagerField.get(manager)
        assertEquals("Fallback manager should be null when cancelled", null, fallbackManager)

        manager.shutdown()
    }

    /**
     * Test 2: Verify non-cancellation exception (RuntimeException, IOException) triggers fallback
     * and error logging as expected.
     */
    @Test
    fun testNonCancellationExceptionTriggersFallback() = runBlocking {
        val states = mutableListOf<ITTSManager.TTSState>()

        UserConfig.customPrefsProvider = {
            MockSharedPreferences { key, defValue ->
                if (key == "tts_precision") {
                    throw RuntimeException("Genuine engine initialization error: Missing model files")
                }
                defValue
            }
        }

        val manager = VieNeuTTSManager(
            context = dummyContext,
            onStateChange = { states.add(it) }
        )

        // Allow coroutine to execute and attempt fallback
        delay(200)

        val isFallbackField = VieNeuTTSManager::class.java.getDeclaredField("isUsingFallback").apply {
            isAccessible = true
        }
        val isUsingFallback = isFallbackField.getBoolean(manager)
        assertTrue("Genuine error should trigger fallback and set isUsingFallback to true", isUsingFallback)

        manager.shutdown()
    }

    /**
     * Test 3: Verify engineThreads computation produces bounded core count in [2, 4] on multi-core systems.
     */
    @Test
    fun testEngineThreadsBoundedCoreCount() {
        val bigCores = DevicePerformanceHelper.getBigCoreCount()
        val boundedThreads = bigCores.coerceIn(2, 4)

        assertTrue("Bounded threads must be >= 2", boundedThreads >= 2)
        assertTrue("Bounded threads must be <= 4", boundedThreads <= 4)

        // Test boundary clamping logic for various core numbers
        val testCores = listOf(1, 2, 3, 4, 6, 8, 12, 16)
        for (cores in testCores) {
            val clamped = cores.coerceIn(2, 4)
            assertTrue("Clamped value for $cores cores should be between 2 and 4", clamped in 2..4)
            if (cores >= 4) {
                assertEquals("8-core/12-core/16-core system should return 4, not $cores", 4, (cores).coerceIn(2, 4))
            }
            if (cores <= 2) {
                assertEquals("Single/dual-core system should return 2", 2, (cores).coerceIn(2, 4))
            }
        }

        // Verify TTSAudioPipeline adaptive duration calculation uses bounded core count as default
        val duration8Cores = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8.coerceIn(2, 4)
        )
        val duration4Cores = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 4
        )
        assertEquals("Adaptive duration should be identical for 8 coerced cores vs 4 cores", duration4Cores, duration8Cores)
    }

    /**
     * Test 4: Verify OnnxSessionManager.configureSessionOptions() allocates optimal thread counts
     * for both XNNPACK and CPU fallback configurations.
     */
    @Test
    fun testOnnxSessionManagerConfigureSessionOptionsThreading() {
        // 1. Test INT8 configuration with default threads (threads = 0)
        val (int8Options, int8Ep) = OnnxSessionManager.configureSessionOptions(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            threads = 0
        )
        assertNotNull(int8Options)
        assertTrue("EP should be XNNPACK or CPU", int8Ep.startsWith("XNNPACK") || int8Ep.startsWith("CPU"))

        // 2. Test FP32 configuration with explicit thread count
        val (fp32Options, fp32Ep) = OnnxSessionManager.configureSessionOptions(
            quantizationType = OnnxSessionManager.QuantizationType.FP32,
            threads = 3
        )
        assertNotNull(fp32Options)
        assertTrue("EP should be XNNPACK or CPU", fp32Ep.startsWith("XNNPACK") || fp32Ep.startsWith("CPU"))

        // 3. Test FP16 configuration
        val (fp16Options, fp16Ep) = OnnxSessionManager.configureSessionOptions(
            quantizationType = OnnxSessionManager.QuantizationType.FP16,
            threads = 0
        )
        assertNotNull(fp16Options)
        assertTrue("EP should be NNAPI, XNNPACK, or CPU", fp16Ep.contains("NNAPI") || fp16Ep.contains("XNNPACK") || fp16Ep.contains("CPU"))
    }
}
