package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import com.skul9x.doctruyen.network.RetrofitClient
import com.skul9x.doctruyen.utils.UserConfig
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Phase 04 Comprehensive Verification Test:
 * 1. testSessionOptionsThreadConfiguration: Validates that intra-op thread counts are properly set
 *    on fallback CPU pools when XNNPACK is enabled.
 * 2. testPrecisionConfigurationTruthfulness: Verifies that precision settings and telemetry reporting
 *    accurately match actual on-disk asset availability.
 * 3. testReleaseLoggingInterceptorDisabled: Asserts that HttpLoggingInterceptor level resolves to NONE
 *    when BuildConfig.DEBUG is false.
 * 4. testSampleCodeAcousticProjectionEquivalence: Verifies that optimized matrix-vector projection
 *    produces identical numerical logits and valid sampled codebook tokens, and verifies G2P cache streaming.
 */
class RuntimeOptimizationAndPrecisionVerificationTest {

    private val dummyContext: Context = object : android.content.ContextWrapper(null) {}

    @Before
    fun setUp() {
        OnnxSessionManager.assetListProvider = null
        OnnxSessionManager.assetOpenProvider = null
        SeaG2P.clearCache()
    }

    @After
    fun tearDown() {
        OnnxSessionManager.assetListProvider = null
        OnnxSessionManager.assetOpenProvider = null
        SeaG2P.clearCache()
    }

    /**
     * 1. testSessionOptionsThreadConfiguration:
     * Validates that intra-op thread counts are properly set on fallback CPU pools when XNNPACK is enabled.
     */
    @Test
    fun testSessionOptionsThreadConfiguration() {
        // Explicit thread count = 4
        val result4 = OnnxSessionManager.configureSessionOptions(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            threads = 4
        )
        assertNotNull(result4.sessionOptions)
        assertEquals("Configured intraOpThreads must match 4", 4, result4.intraOpThreads)
        assertEquals("Configured interOpThreads must be 1 for sequential execution", 1, result4.interOpThreads)
        assertEquals("Static lastConfiguredIntraOpThreads must match 4", 4, OnnxSessionManager.lastConfiguredIntraOpThreads)
        assertEquals("Static lastConfiguredInterOpThreads must match 1", 1, OnnxSessionManager.lastConfiguredInterOpThreads)

        // Destructuring compatibility check
        val (options4, ep4, intra4, inter4) = result4
        assertNotNull(options4)
        assertTrue("EP should be XNNPACK or CPU", ep4.startsWith("XNNPACK") || ep4.startsWith("CPU"))
        assertEquals(4, intra4)
        assertEquals(1, inter4)

        // Explicit thread count = 2 on FP32
        val result2 = OnnxSessionManager.configureSessionOptions(
            quantizationType = OnnxSessionManager.QuantizationType.FP32,
            threads = 2
        )
        assertEquals(2, result2.intraOpThreads)
        assertEquals(1, result2.interOpThreads)
        assertEquals(2, OnnxSessionManager.lastConfiguredIntraOpThreads)

        // Auto thread count (threads = 0) resolves to big-core count in range [2, 4]
        val resultAuto = OnnxSessionManager.configureSessionOptions(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            threads = 0
        )
        assertTrue("Auto threads must be within [2, 4]", resultAuto.intraOpThreads in 2..4)
        assertEquals(1, resultAuto.interOpThreads)

        // FP16 fallback configuration
        val resultFp16 = OnnxSessionManager.configureSessionOptions(
            quantizationType = OnnxSessionManager.QuantizationType.FP16,
            threads = 3
        )
        assertEquals(3, resultFp16.intraOpThreads)
        assertEquals(1, resultFp16.interOpThreads)
    }

    /**
     * 2. testPrecisionConfigurationTruthfulness:
     * Verifies that precision settings and telemetry reporting accurately match actual on-disk asset availability.
     */
    @Test
    fun testPrecisionConfigurationTruthfulness() {
        // A. Baseline on-disk check: In default repository assets, only FP32 backbone exists (no backbone_int8)
        val assetsDir = listOf(File("src/main/assets/vieneu"), File("app/src/main/assets/vieneu"))
            .firstOrNull { it.exists() }
        assertNotNull("VieNeu assets directory must exist", assetsDir)

        val int8OnDisk = File(assetsDir, "backbone_int8")
        val int8ExistsOnDisk = int8OnDisk.exists() && int8OnDisk.list()?.isNotEmpty() == true

        // Verify hasInt8Assets reflects truth on disk
        val hasInt8 = OnnxSessionManager.hasInt8Assets(dummyContext)
        assertEquals(int8ExistsOnDisk, hasInt8)

        // If INT8 assets are absent, isPrecisionAvailable("INT8") must be false
        if (!int8ExistsOnDisk) {
            assertFalse(OnnxSessionManager.isPrecisionAvailable(dummyContext, "INT8"))
            assertTrue(OnnxSessionManager.isPrecisionAvailable(dummyContext, "FP32"))

            // resolveEffectivePrecision must gracefully resolve "INT8" to "FP32" without deceptive warnings
            val effective = OnnxSessionManager.resolveEffectivePrecision(dummyContext, "INT8")
            assertEquals("FP32", effective)

            val available = OnnxSessionManager.getAvailablePrecisions(dummyContext)
            assertTrue("FP32 must be in available list", available.contains("FP32"))
            assertFalse("INT8 must NOT be in available list when absent", available.contains("INT8"))
        }

        // B. Simulation of dual-asset availability via assetListProvider
        OnnxSessionManager.assetListProvider = { _, path ->
            when (path) {
                "vieneu/backbone_int8" -> arrayOf("vieneu_prefill.onnx", "vieneu_decode_step.onnx")
                "vieneu/backbone" -> arrayOf("vieneu_prefill.onnx", "vieneu_decode_step.onnx")
                else -> null
            }
        }

        assertTrue("Simulated INT8 assets must be detected", OnnxSessionManager.hasInt8Assets(dummyContext))
        assertTrue("INT8 precision must be available when assets are present", OnnxSessionManager.isPrecisionAvailable(dummyContext, "INT8"))
        assertTrue("FP32 precision must be available", OnnxSessionManager.isPrecisionAvailable(dummyContext, "FP32"))
        assertEquals("INT8", OnnxSessionManager.resolveEffectivePrecision(dummyContext, "INT8"))
        assertEquals("FP32", OnnxSessionManager.resolveEffectivePrecision(dummyContext, "FP32"))

        // C. Truthful Telemetry Reporting in TtsMetricsLogger
        TtsMetricsLogger.startSession(
            storyId = "truth_test_1",
            storyTitle = "Precision Truth",
            context = null,
            engineThreads = 4,
            quantization = "FP32"
        )
        assertEquals("FP32", TtsMetricsLogger.currentSession.deviceProfile?.quantization)

        // Log engine reload with actual engine quantization
        TtsMetricsLogger.logEngineReload("FP32")
        assertEquals("FP32", TtsMetricsLogger.currentSession.deviceProfile?.quantization)

        TtsMetricsLogger.logEngineReload("INT8")
        assertEquals("INT8", TtsMetricsLogger.currentSession.deviceProfile?.quantization)
    }

    /**
     * 3. testReleaseLoggingInterceptorDisabled:
     * Asserts that HttpLoggingInterceptor level resolves to NONE when BuildConfig.DEBUG is false.
     */
    @Test
    fun testReleaseLoggingInterceptorDisabled() {
        // In release builds (isDebug = false), interceptor level must resolve to NONE
        val releaseLevel = RetrofitClient.getLoggingLevel(isDebug = false)
        assertEquals(
            "Release builds must enforce HttpLoggingInterceptor.Level.NONE to eliminate header leaks",
            HttpLoggingInterceptor.Level.NONE,
            releaseLevel
        )

        // In debug builds (isDebug = true), interceptor level must resolve to BODY
        val debugLevel = RetrofitClient.getLoggingLevel(isDebug = true)
        assertEquals(
            "Debug builds must use HttpLoggingInterceptor.Level.BODY for diagnostics",
            HttpLoggingInterceptor.Level.BODY,
            debugLevel
        )

        // Ensure Level.HEADERS is completely eradicated
        assertNotEquals(HttpLoggingInterceptor.Level.HEADERS, releaseLevel)
        assertNotEquals(HttpLoggingInterceptor.Level.HEADERS, debugLevel)
    }

    /**
     * 4. testSampleCodeAcousticProjectionEquivalence:
     * Verifies that optimized matrix-vector projection produces identical numerical logits
     * and valid sampled codebook tokens, and verifies G2P cache streaming.
     */
    @Test
    fun testSampleCodeAcousticProjectionEquivalence() {
        val H = 768
        val vocabSize = 1024
        val rnd = Random(42)

        val vec = FloatArray(H) { (rnd.nextFloat() - 0.5f) * 2.0f }
        val flatEmb = FloatArray(vocabSize * H) { (rnd.nextFloat() - 0.5f) * 2.0f }

        // A. Baseline scalar dot-product loop
        val refLogits = FloatArray(vocabSize)
        for (v in 0 until vocabSize) {
            val offset = v * H
            var dot = 0f
            for (h in 0 until H) {
                dot += vec[h] * flatEmb[offset + h]
            }
            refLogits[v] = dot
        }

        // B. Optimized unrolled vector projection
        val optLogits = FloatArray(vocabSize)
        VieNeuOnnxEngine.computeAcousticProjectionLogits(vec, flatEmb, optLogits, H, vocabSize)

        // Numerical equivalence check within floating-point tolerance
        for (v in 0 until vocabSize) {
            assertEquals(
                "Logit mismatch at index $v",
                refLogits[v],
                optLogits[v],
                1e-4f
            )
        }

        // Edge case: All zeros
        val zeroVec = FloatArray(H)
        val zeroLogits = FloatArray(vocabSize)
        VieNeuOnnxEngine.computeAcousticProjectionLogits(zeroVec, flatEmb, zeroLogits, H, vocabSize)
        for (v in 0 until vocabSize) {
            assertEquals(0f, zeroLogits[v], 1e-6f)
        }

        // C. G2P Cache Streaming and Paragraph Caching Verification
        // Short lexical items must be cacheable
        assertTrue("Short word must be cacheable", SeaG2P.isCacheable("Xin chào"))
        assertTrue("Short phrase must be cacheable", SeaG2P.isCacheable("Khởi động hệ thống"))
        assertTrue("Single sentence within length limit must be cacheable", SeaG2P.isCacheable("Hôm nay trời rất đẹp."))

        // Multi-sentence paragraph or long passages must NOT be cacheable
        val multiSentence = "Ngày xưa có một chàng trai. Anh ta rất chăm chỉ làm việc."
        assertFalse("Multi-sentence paragraph must not be cacheable", SeaG2P.isCacheable(multiSentence))

        val multiline = "Dòng thứ nhất.\nDòng thứ hai."
        assertFalse("Multiline text must not be cacheable", SeaG2P.isCacheable(multiline))

        val longSentence = "A".repeat(101)
        assertFalse("Sentence exceeding MAX_CACHEABLE_LENGTH must not be cacheable", SeaG2P.isCacheable(longSentence))

        // Verify phonemize with multi-sentence does not pollute cache
        assertEquals(0, SeaG2P.getCacheSize())
        SeaG2P.phonemize("Xin chào")
        assertEquals("Cache should contain 1 entry for short phrase", 1, SeaG2P.getCacheSize())

        SeaG2P.phonemize(multiSentence)
        assertEquals("Cache size must remain 1 after phonemizing multi-sentence paragraph", 1, SeaG2P.getCacheSize())
    }
}

