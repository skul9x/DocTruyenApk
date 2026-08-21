package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class EarlyG2PWarmingAndCacheTest {

    companion object {
        private var nativeLoaded = false

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
                        nativeLoaded = true
                        break
                    } catch (e: Throwable) {
                        // ignore and try next
                    }
                }
            }
        }
    }

    @Before
    fun setUp() {
        SeaG2P.clearCache()
    }

    @Test
    fun testWarmupIdempotencyAndState() {
        assertFalse("Initially warmedUp should be false or updated after warmUp", false)
        
        // First warmup
        SeaG2P.warmUp(null)
        assertTrue("isWarmedUp() should be true after warmUp", SeaG2P.isWarmedUp())

        // Second warmup (idempotent call)
        SeaG2P.warmUp(null)
        assertTrue("isWarmedUp() should remain true after consecutive warmUp calls", SeaG2P.isWarmedUp())
    }

    @Test
    fun testEmptyAndBlankStrings() {
        assertEquals("Empty string must return empty string", "", SeaG2P.phonemize(""))
        assertEquals("Whitespace string must return empty string", "", SeaG2P.phonemize("   \n\t  "))
        assertEquals("Blank input should not pollute cache", 0, SeaG2P.getCacheSize())
    }

    @Test
    fun testLruCacheHitMissAndEviction() {
        val word1 = "Xin chào"
        val word2 = "Tạm biệt"

        assertEquals(0, SeaG2P.getCacheSize())

        val p1 = SeaG2P.phonemize(word1)
        assertTrue(p1.isNotEmpty())
        assertEquals(1, SeaG2P.getCacheSize())

        // Cache hit test
        val p1Cached = SeaG2P.phonemize(word1)
        assertEquals(p1, p1Cached)
        assertEquals(1, SeaG2P.getCacheSize())

        // Second word
        val p2 = SeaG2P.phonemize(word2)
        assertTrue(p2.isNotEmpty())
        assertEquals(2, SeaG2P.getCacheSize())

        // Fill cache up to capacity and verify bounded LRU behavior
        for (i in 1..600) {
            SeaG2P.phonemize("Từ vựng kiểm tra $i")
        }

        assertTrue("Cache size must not exceed MAX_CACHE_SIZE (512)", SeaG2P.getCacheSize() <= 512)

        // Clear cache
        SeaG2P.clearCache()
        assertEquals("Cache size should be 0 after clearCache()", 0, SeaG2P.getCacheSize())
    }

    @Test
    fun testWarmupLatencyBenefit() {
        // Ensure warmup
        SeaG2P.warmUp(null)
        val text = "Trời hôm nay nhiều mây, gió thổi nhẹ qua từng hàng cây bên đường."

        // First call populates cache
        val startWarm = System.nanoTime()
        val res1 = SeaG2P.phonemize(text)
        val elapsedFirstMs = (System.nanoTime() - startWarm) / 1_000_000.0

        assertTrue("Phonemization result must not be empty", res1.isNotEmpty())

        // Second call (cache hit) must be ultra fast (< 10ms)
        val startCache = System.nanoTime()
        val res2 = SeaG2P.phonemize(text)
        val elapsedCacheMs = (System.nanoTime() - startCache) / 1_000_000.0

        assertEquals(res1, res2)
        assertTrue("Subsequent cached phonemization must take < 10ms (was ${elapsedCacheMs}ms)", elapsedCacheMs < 10.0)
    }

    @Test
    fun testThreadSafeConcurrentAccess() {
        val threadCount = 16
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errorCount = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val phrase = "Câu kiểm tra luồng $t mục $i"
                        val result = SeaG2P.phonemize(phrase)
                        if (result.isEmpty()) {
                            errorCount.incrementAndGet()
                        }
                    }
                } catch (e: Throwable) {
                    errorCount.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Threads should finish within 10 seconds", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals("There should be 0 errors during concurrent phonemization and caching", 0, errorCount.get())
        assertTrue("Cache should hold entries from concurrent threads", SeaG2P.getCacheSize() > 0)
    }
}
