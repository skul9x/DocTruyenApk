package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartDialogueSegmentationTest {

    @Test
    fun testDialogueQuoteIntegrityNotSplit() {
        // Dialogue quote with question mark inside should not be broken
        val text = "Lan hỏi: \"Anh đi đâu đấy?\", rồi quay đi."
        val sentences = SmartTextSegmenter.splitIntoSentences(text)

        assertEquals(1, sentences.size)
        assertEquals("Lan hỏi: \"Anh đi đâu đấy?\", rồi quay đi.", sentences[0])
    }

    @Test
    fun testBracketPairsAwareness() {
        val text = "Cái đó (tôi nghĩ vậy. chắc thế) là đúng. Xong."
        val sentences = SmartTextSegmenter.splitIntoSentences(text)

        assertEquals(2, sentences.size)
        assertEquals("Cái đó (tôi nghĩ vậy. chắc thế) là đúng.", sentences[0])
        assertEquals("Xong.", sentences[1])

        val guillemets = "Họ nói «điều này. thật kỳ lạ» trước khi rời đi. Hết."
        val guillemetSentences = SmartTextSegmenter.splitIntoSentences(guillemets)
        assertEquals(2, guillemetSentences.size)
        assertEquals("Họ nói «điều này. thật kỳ lạ» trước khi rời đi.", guillemetSentences[0])
        assertEquals("Hết.", guillemetSentences[1])

        val cjkQuotes = "Tác giả viết 「đây là ví dụ. rất hay」 trong sách. Kết thúc."
        val cjkSentences = SmartTextSegmenter.splitIntoSentences(cjkQuotes)
        assertEquals(2, cjkSentences.size)
        assertEquals("Tác giả viết 「đây là ví dụ. rất hay」 trong sách.", cjkSentences[0])
        assertEquals("Kết thúc.", cjkSentences[1])
    }

    @Test
    fun testUnbalancedQuoteFallback() {
        // Stray quotation mark must not swallow subsequent sentences
        val text = "Câu một. Câu hai lệch \" ở đây. Câu ba."
        val (scanned, balanced) = SmartTextSegmenter.scanSentences(text, quoteAware = true)
        assertFalse("Unbalanced quote should report balanced = false", balanced)

        val sentences = SmartTextSegmenter.splitIntoSentences(text)
        assertEquals(3, sentences.size)
        assertEquals("Câu một.", sentences[0])
        assertEquals("Câu hai lệch \" ở đây.", sentences[1])
        assertEquals("Câu ba.", sentences[2])
    }

    @Test
    fun testDecimalAndNumberNotSplit() {
        val text = "Giá 3.5 triệu. Lúc 8.30 sáng."
        val sentences = SmartTextSegmenter.splitIntoSentences(text)

        assertEquals(2, sentences.size)
        assertEquals("Giá 3.5 triệu.", sentences[0])
        assertEquals("Lúc 8.30 sáng.", sentences[1])
    }

    @Test
    fun testApostropheIsNotAQuote() {
        val text = "He said don't worry. Then he left."
        val sentences = SmartTextSegmenter.splitIntoSentences(text)

        assertEquals(2, sentences.size)
        assertEquals("He said don't worry.", sentences[0])
        assertEquals("Then he left.", sentences[1])
    }

    @Test
    fun testRepeatedPunctuationAndTrailingClose() {
        val text = "Câu một... Câu hai?! Câu ba."
        val sentences = SmartTextSegmenter.splitIntoSentences(text)

        assertEquals(3, sentences.size)
        assertEquals("Câu một...", sentences[0])
        assertEquals("Câu hai?!", sentences[1])
        assertEquals("Câu ba.", sentences[2])
    }

    @Test
    fun testPuncNormShortAndLongChunks() {
        // Short chunk (< 5 words) gets forced '.'
        assertEquals("Xin chào.", SmartTextSegmenter.puncNorm("Xin chào"))
        assertEquals("Xin chào!", SmartTextSegmenter.puncNorm("Xin chào!"))
        assertEquals("Xin chào?", SmartTextSegmenter.puncNorm("Xin chào?"))
        assertEquals("Xin chào.", SmartTextSegmenter.puncNorm("Xin chào,"))
        assertEquals("Xin chào...", SmartTextSegmenter.puncNorm("Xin chào..."))

        // Short chunk with trailing quote
        assertEquals("\"Xin chào\".", SmartTextSegmenter.puncNorm("\"Xin chào\""))
        assertEquals("\"Xin chào!\".", SmartTextSegmenter.puncNorm("\"Xin chào!\""))

        // Long chunk (>= 5 words)
        val longWithoutPunct = "Một hai ba bốn năm sáu"
        assertEquals("Một hai ba bốn năm sáu.", SmartTextSegmenter.puncNorm(longWithoutPunct))

        val longWithPunct = "Một hai ba bốn năm sáu!"
        assertEquals("Một hai ba bốn năm sáu!", SmartTextSegmenter.puncNorm(longWithPunct))

        val longWithQuestion = "Một hai ba bốn năm sáu?"
        assertEquals("Một hai ba bốn năm sáu?", SmartTextSegmenter.puncNorm(longWithQuestion))

        val longWithComma = "Một hai ba bốn năm sáu,"
        assertEquals("Một hai ba bốn năm sáu,", SmartTextSegmenter.puncNorm(longWithComma))
    }

    @Test
    fun testEndToEndSegmentWithDialogueQuotesAndPuncNorm() {
        val story = """
            Lan hỏi: "Anh đi đâu đấy?", rồi quay đi.
            
            - Tôi đi về làng!
            
            Cậu bé đáp không chút do dự.
        """.trimIndent()

        val chunks = SmartTextSegmenter.segment(story)
        assertTrue(chunks.isNotEmpty())

        // First paragraph should have dialogue inside quote preserved
        assertEquals("Lan hỏi: \"Anh đi đâu đấy?\", rồi quay đi.", chunks[0].text)
        assertEquals(PauseType.PARAGRAPH_BREAK, chunks[0].pauseType)

        // Second paragraph dialogue line
        assertEquals("- Tôi đi về làng!", chunks[1].text)
        assertEquals(PauseType.PARAGRAPH_BREAK, chunks[1].pauseType)

        // Third paragraph
        assertEquals("Cậu bé đáp không chút do dự.", chunks[2].text)
        assertEquals(PauseType.PARAGRAPH_BREAK, chunks[2].pauseType)

        // Ensure sequential indices
        for (i in chunks.indices) {
            assertEquals(i, chunks[i].index)
        }
    }
}
