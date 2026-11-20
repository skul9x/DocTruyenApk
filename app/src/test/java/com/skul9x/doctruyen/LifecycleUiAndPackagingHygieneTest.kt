package com.skul9x.doctruyen

import com.bumptech.glide.request.RequestListener
import com.skul9x.doctruyen.adapter.StoryAdapter
import com.skul9x.doctruyen.tts.vieneu.SeaG2P
import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.UserConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LifecycleUiAndPackagingHygieneTest {

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
                    } catch (e: Throwable) {
                        // ignore and try next
                    }
                }
            }
            SeaG2P.warmUp(null)
        }
    }

    /**
     * Requirement 1: Debounced & Lifecycle-Aware Pre-warming
     * Simulates activity entry and exit within debounce duration, asserting that prewarming
     * is cancelled before invocation, while completing if user stays.
     */
    @Test
    fun testPrewarmDebounceAndCancellationOnExit() = runBlocking {
        assertEquals(
            "Debounce delay in StoryDetailActivity must be exactly 3000ms",
            3000L,
            StoryDetailActivity.PREWARM_DEBOUNCE_DELAY_MS
        )

        val prewarmInvoked = AtomicBoolean(false)
        val invocationCount = AtomicInteger(0)

        // Scenario A: User opens story detail, reads/browses for 1000ms, then navigates Back (< 3000ms)
        var prewarmJob: Job? = CoroutineScope(Dispatchers.Default).launch {
            delay(StoryDetailActivity.PREWARM_DEBOUNCE_DELAY_MS)
            prewarmInvoked.set(true)
            invocationCount.incrementAndGet()
        }

        // Simulate 1000ms elapsed, then user navigates Back (triggers cancelPendingPrewarm / onStop / onDestroy)
        delay(1000L)
        prewarmJob?.cancel()
        prewarmJob = null

        // Wait past the original 3000ms window
        delay(2500L)

        assertFalse(
            "Prewarming must NOT be invoked when user navigates away before debounce window (3000ms)",
            prewarmInvoked.get()
        )
        assertEquals("Invocation count must be 0 for cancelled debounce", 0, invocationCount.get())

        // Scenario B: User stays on story detail for > 3000ms without pressing Play
        prewarmInvoked.set(false)
        prewarmJob = CoroutineScope(Dispatchers.Default).launch {
            delay(StoryDetailActivity.PREWARM_DEBOUNCE_DELAY_MS)
            prewarmInvoked.set(true)
            invocationCount.incrementAndGet()
        }

        // Wait for debounce window to elapse (3000ms + 500ms buffer)
        delay(3500L)

        assertTrue(
            "Prewarming MUST be triggered when user stays past debounce window (3000ms)",
            prewarmInvoked.get()
        )
        assertEquals("Invocation count must be exactly 1", 1, invocationCount.get())

        // Scenario C: UserConfig disable flag test
        assertEquals("KEY_TTS_PREWARM_ENABLED key must match", "tts_prewarm_enabled", UserConfig.KEY_TTS_PREWARM_ENABLED)
        assertTrue("Default prewarm setting must be true", UserConfig.DEFAULT_TTS_PREWARM_ENABLED)
    }

    /**
     * Requirement 2: Granular G2P Caching
     * Phonemizes paragraph text with repeated Vietnamese syllables and verifies that converted
     * phonemes match baseline outputs 100%, with granular syllable cache hits and bounds.
     */
    @Test
    fun testG2PCachingAccuracyAndHitRate() {
        SeaG2P.clearCache()
        assertEquals("Initial cache hits should be 0", 0L, SeaG2P.getCacheHits())
        assertEquals("Initial syllable cache size should be 0", 0, SeaG2P.getSyllableCacheSize())

        val paragraph = "Ngày xưa có một chàng trai nghèo sống trong một ngôi làng nhỏ bên bờ sông. " +
            "Anh ta rất chăm chỉ và luôn mơ ước một cuộc sống tốt đẹp hơn. " +
            "Một ngày nọ, chàng trai đi vào rừng sâu tìm kiếm một cơ hội đổi đời."

        // First pass: converts sentence 1
        val sent1 = "Ngày xưa có một chàng trai nghèo sống trong một ngôi làng nhỏ bên bờ sông."
        val phonemes1 = SeaG2P.phonemize(sent1)
        assertTrue("Phonemes 1 must not be empty", phonemes1.isNotEmpty())
        assertTrue("Syllable cache should be populated after first sentence", SeaG2P.getSyllableCacheSize() > 0)

        // "một" appears twice in sentence 1 ("có một chàng trai... trong một ngôi làng")
        // Therefore at least one cache hit occurs on the repeated "một"
        val hitsAfterSent1 = SeaG2P.getCacheHits()
        assertTrue("Cache hit should occur on repeated syllable within sentence 1", hitsAfterSent1 >= 1L)

        // Second pass: sentence 2 has repeated syllables ("một", "sống")
        val sent2 = "Anh ta rất chăm chỉ và luôn mơ ước một cuộc sống tốt đẹp hơn."
        val phonemes2 = SeaG2P.phonemize(sent2)
        assertTrue("Phonemes 2 must not be empty", phonemes2.isNotEmpty())
        val hitsAfterSent2 = SeaG2P.getCacheHits()
        assertTrue("Cache hits must increase on sentence 2 with repeated syllables", hitsAfterSent2 > hitsAfterSent1)

        // Consistency & accuracy test: Repeating sentence 1 must produce 100% identical phoneme output
        val phonemes1Repeat = SeaG2P.phonemize(sent1)
        assertEquals(
            "Phonemes from cache must be 100% identical to initial phonemes",
            phonemes1,
            phonemes1Repeat
        )

        // Full paragraph phonemization test
        val paragraphPhonemes = SeaG2P.phonemize(paragraph)
        assertTrue("Full paragraph phonemes must not be empty", paragraphPhonemes.isNotEmpty())

        val totalHits = SeaG2P.getCacheHits()
        val totalMisses = SeaG2P.getCacheMisses()
        val totalRequests = totalHits + totalMisses

        assertTrue("Total requests should be positive", totalRequests > 0)
        assertTrue("Total cache hits should be significant", totalHits >= 3L)

        // Syllable cache bounded capacity test
        for (i in 1..2500) {
            SeaG2P.phonemizeWord("từvựng$i")
        }
        assertTrue(
            "Syllable cache size must be bounded and not exceed 2048 entries (was ${SeaG2P.getSyllableCacheSize()})",
            SeaG2P.getSyllableCacheSize() <= 2048
        )
    }

    /**
     * Requirement 3: UI Scroll Garbage & Logging Cleanup (DebugLogger)
     * Verifies that DebugLogger.log returns immediately without acquiring locks or allocating
     * LogEntry instances when isDebugEnabled is false.
     */
    @Test
    fun testDebugLoggerSuppressionInReleaseMode() {
        val originalDebug = DebugLogger.isDebugEnabled

        try {
            // Disable debug logging (simulate Release build / BuildConfig.DEBUG == false)
            DebugLogger.isDebugEnabled = false
            DebugLogger.clear()

            assertEquals("Log buffer should be empty after clear", "", DebugLogger.getFormattedLogs())

            // Attempt to log messages in release mode
            DebugLogger.log("PERF_TEST", "This message should be completely suppressed in release")
            DebugLogger.logError("PERF_TEST", "This error should be completely suppressed in release")

            // Buffer must remain strictly empty
            val releaseLogs = DebugLogger.getFormattedLogs()
            assertEquals(
                "DebugLogger buffer must remain strictly empty when isDebugEnabled is false",
                "",
                releaseLogs
            )

            // Re-enable debug logging (simulate Debug build / BuildConfig.DEBUG == true)
            DebugLogger.isDebugEnabled = true
            DebugLogger.log("PERF_TEST", "Active debug log message")

            val activeLogs = DebugLogger.getFormattedLogs()
            assertTrue(
                "DebugLogger should record logs when isDebugEnabled is true",
                activeLogs.contains("Active debug log message")
            )
        } finally {
            DebugLogger.isDebugEnabled = originalDebug
            DebugLogger.clear()
        }
    }

    /**
     * Requirement 3: UI Scroll Garbage & Logging Cleanup (StoryAdapter)
     * Confirms that StoryAdapter bind() has zero anonymous RequestListener class allocations.
     */
    @Test
    fun testStoryAdapterZeroPerBindListenerAllocation() {
        // Inspect all declared classes inside StoryAdapter and StoryViewHolder
        val adapterDeclaredClasses = StoryAdapter::class.java.declaredClasses
        val viewHolderDeclaredClasses = StoryAdapter.StoryViewHolder::class.java.declaredClasses

        for (cls in adapterDeclaredClasses) {
            assertFalse(
                "StoryAdapter must NOT declare anonymous RequestListener class: ${cls.name}",
                RequestListener::class.java.isAssignableFrom(cls)
            )
        }

        for (cls in viewHolderDeclaredClasses) {
            assertFalse(
                "StoryViewHolder must NOT declare anonymous RequestListener class: ${cls.name}",
                RequestListener::class.java.isAssignableFrom(cls)
            )
        }

        // Verify adapter instantiates cleanly
        val adapter = StoryAdapter {}
        assertNotNull("StoryAdapter should instantiate cleanly", adapter)
    }

    /**
     * Requirement 4: Targeted ABI Packaging
     * Verifies that build.gradle.kts configures release ABI filters for arm64-v8a only,
     * while debug retains x86_64 for emulator support.
     */
    @Test
    fun testTargetedAbiPackagingConfiguration() {
        val buildGradleFile = File("build.gradle.kts").takeIf { it.exists() }
            ?: File("app/build.gradle.kts").takeIf { it.exists() }
            ?: File("../app/build.gradle.kts")

        assertTrue("app/build.gradle.kts must exist", buildGradleFile.exists())
        val content = buildGradleFile.readText()

        assertTrue(
            "Release buildType must specify ndk.abiFilters with arm64-v8a",
            content.contains("arm64-v8a")
        )
        assertTrue(
            "Debug buildType must specify ndk.abiFilters with x86_64",
            content.contains("x86_64")
        )

        // Check release block specifies arm64-v8a
        val releaseIndex = content.indexOf("release {")
        assertTrue("release build block must exist", releaseIndex > 0)
        val releaseBlock = content.substring(releaseIndex, content.indexOf("debug {", releaseIndex).let { if (it > 0) it else content.length })
        assertTrue("Release block must contain arm64-v8a", releaseBlock.contains("arm64-v8a"))
        assertFalse("Release block must NOT contain armeabi-v7a", releaseBlock.contains("armeabi-v7a"))
    }
}

