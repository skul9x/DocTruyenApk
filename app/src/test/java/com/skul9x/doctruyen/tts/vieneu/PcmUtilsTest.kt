package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Test

class PcmUtilsTest {

    @Test
    fun testPcmFloatToShortConversion() {
        val floatPcm = floatArrayOf(0.0f, 0.5f, -0.5f, 1.0f, -1.0f, 1.5f, -2.0f)
        val shortPcm = PcmUtils.floatToShort(floatPcm)

        assertEquals(7, shortPcm.size)
        assertEquals("0.0 -> 0", 0, shortPcm[0].toInt())
        assertTrue("0.5 -> ~16383", shortPcm[1] in 16380..16385)
        assertTrue("-0.5 -> ~-16383", shortPcm[2] in -16385..-16380)
        assertEquals("1.0 -> 32767", 32767, shortPcm[3].toInt())
        assertEquals("-1.0 -> -32767", -32767, shortPcm[4].toInt())
        assertEquals("Clamped upper -> 32767", 32767, shortPcm[5].toInt())
        assertEquals("Clamped lower -> -32767", -32767, shortPcm[6].toInt())
    }

    @Test
    fun testPcmShortToFloatConversion() {
        val shortPcm = shortArrayOf(0, 16383, -16383, 32767, -32767)
        val floatPcm = PcmUtils.shortToFloat(shortPcm)

        assertEquals(5, floatPcm.size)
        assertEquals(0.0f, floatPcm[0], 0.0001f)
        assertEquals(0.5f, floatPcm[1], 0.01f)
        assertEquals(-0.5f, floatPcm[2], 0.01f)
        assertEquals(1.0f, floatPcm[3], 0.0001f)
        assertEquals(-1.0f, floatPcm[4], 0.0001f)
    }

    @Test
    fun testTextSplittingStandardSentences() {
        val text = "Câu một. Câu hai! Câu ba? Câu bốn.\nCâu năm."
        val chunks = PcmUtils.splitIntoSentences(text)

        assertTrue("Should split into at least 4 chunks", chunks.size >= 4)
        assertEquals(5, chunks.size)
        assertEquals("Câu một.", chunks[0])
        assertEquals("Câu hai!", chunks[1])
        assertEquals("Câu ba?", chunks[2])
        assertEquals("Câu bốn.", chunks[3])
        assertEquals("Câu năm.", chunks[4])

        chunks.forEachIndexed { i, chunk ->
            assertTrue("Chunk $i should not be blank", chunk.isNotBlank())
        }
    }

    @Test
    fun testTextSplittingWithEllipsisAndNewlines() {
        val text = "Một ngày nọ… Trời bỗng đổ mưa!\n\nTôi chạy nhanh về nhà."
        val chunks = PcmUtils.splitIntoSentences(text)

        assertEquals(3, chunks.size)
        assertEquals("Một ngày nọ…", chunks[0])
        assertEquals("Trời bỗng đổ mưa!", chunks[1])
        assertEquals("Tôi chạy nhanh về nhà.", chunks[2])
    }

    @Test
    fun testTextSplittingLongSentences() {
        val longSentence = "Đây là một câu rất dài, có nhiều mệnh đề liên kết với nhau bằng dấu phẩy, " +
                "nhằm mục đích kiểm tra khả năng phân tách câu dài của bộ tiền xử lý âm thanh."
        val chunks = PcmUtils.splitIntoSentences(longSentence, maxCharsPerChunk = 60)

        assertTrue("Should split long sentence into smaller sub-chunks", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue("Sub-chunk length should be <= 60", chunk.length <= 60)
            assertTrue("Sub-chunk should not be blank", chunk.isNotBlank())
        }
    }

    @Test
    fun testTextSplittingEmptyAndBlank() {
        assertTrue(PcmUtils.splitIntoSentences("").isEmpty())
        assertTrue(PcmUtils.splitIntoSentences("   \n\n  \t ").isEmpty())
    }

    @Test
    fun testMicroFadeInPlace() {
        val original = ShortArray(1000) { 10000.toShort() }
        val faded = PcmUtils.applyMicroFade(original, fadeDurationMs = 5.0f, sampleRate = 48000)

        // Verify returns same instance
        assertSame(original, faded)
        // Verify start has fade in (first sample is 0)
        assertEquals(0.toShort(), faded[0])
        assertTrue("Fade in ramping", faded[10] > faded[0] && faded[10] < 10000)
        // Verify middle is unaffected
        assertEquals(10000.toShort(), faded[500])
        // Verify end has fade out (last sample is ramped down)
        assertTrue("Fade out ramping", faded[999] < 1000)
    }
}
