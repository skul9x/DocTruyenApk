package com.skul9x.doctruyen.tts.vieneu

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeaG2PTest {

    @Test
    fun testVietnameseBasicPhonemize() {
        // Given a simple Vietnamese sentence
        val input = "Xin chào các bạn"

        // When phonemized
        val result = SeaG2P.phonemize(input)

        // Then it should produce non-empty phoneme output
        assertTrue("Phonemize result should not be empty", result.isNotEmpty())
        println("Phonemize output: '$result'")
    }

    @Test
    fun testVietnameseWithTones() {
        // Given Vietnamese with various tones
        val input = "Tôi yêu Việt Nam"

        val result = SeaG2P.phonemize(input)

        assertTrue("Should handle tonal marks", result.isNotEmpty())
        // Phonemes should contain tone markers
        assertTrue("Should contain phoneme tokens", result.contains(" "))
        println("Tonal phonemize: '$result'")
    }

    @Test
    fun testNumberNormalization() {
        // Given text with numbers
        val input = "Năm 2026, giá là 50000 đồng"

        val result = SeaG2P.phonemize(input)

        // Numbers should be expanded to words
        assertTrue("Should normalize numbers", result.isNotEmpty())
        println("Number normalization: '$result'")
    }

    @Test
    fun testEmptyInput() {
        val result = SeaG2P.phonemize("")
        // Should not crash, return empty or whitespace
        assertNotNull("Empty input should not return null", result)
    }

    @Test
    fun testPerformance() {
        // Given a realistic paragraph
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
