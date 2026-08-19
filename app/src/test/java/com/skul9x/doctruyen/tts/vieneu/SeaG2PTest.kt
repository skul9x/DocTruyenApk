package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class SeaG2PTest {

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
                    } catch (e: Throwable) {
                        // ignore and try next
                    }
                }
            }
        }
    }

    @Test
    fun testVietnameseBasicPhonemize() {
        val input = "Xin chào các bạn"
        val result = SeaG2P.phonemize(input)
        assertTrue("Phonemize result should not be empty", result.isNotEmpty())
        println("Phonemize output: '$result'")
    }

    @Test
    fun testVietnameseWithTones() {
        val input = "Tôi yêu Việt Nam"
        val result = SeaG2P.phonemize(input)
        assertTrue("Should handle tonal marks", result.isNotEmpty())
        assertTrue("Should contain phoneme tokens", result.contains(" "))
        println("Tonal phonemize: '$result'")
    }

    @Test
    fun testNumberNormalization() {
        val input = "Năm 2026, giá là 50000 đồng"
        val result = SeaG2P.phonemize(input)
        assertTrue("Should normalize numbers", result.isNotEmpty())
        println("Number normalization: '$result'")
    }

    @Test
    fun testEmptyInput() {
        val result = SeaG2P.phonemize("")
        assertNotNull("Empty input should not return null", result)
    }

    @Test
    fun testPerformance() {
        val paragraph = "Ngày xưa, có một chàng trai nghèo sống trong một ngôi làng nhỏ bên bờ sông. " +
            "Anh ta rất chăm chỉ và luôn mơ ước một cuộc sống tốt đẹp hơn."

        // Warm-up to initialize static caches
        SeaG2P.phonemize("Khởi động")

        val startTime = System.nanoTime()
        val result = SeaG2P.phonemize(paragraph)
        val elapsed = (System.nanoTime() - startTime) / 1_000_000 // ms

        assertTrue("Phonemization should complete within 50ms, took ${elapsed}ms", elapsed < 50)
        assertTrue("Should produce output for paragraph", result.isNotEmpty())
        println("Performance: ${elapsed}ms for ${paragraph.length} chars")
    }
}
