package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive Unit Test for Phase 01: Punctuation-First Segmenter Core Refactor.
 *
 * Verifies:
 * 1. Exact 4-chunk output for user prompt text without splitting compound words like "sẵn sàng".
 * 2. Protection of compound words and clauses between 75 and 180 characters.
 * 3. Preservation of colon normalization ':' -> ':.' behavior.
 * 4. Emergency fallback for run-on clauses > 200 characters without mid-token chopping.
 */
class PunctuationFirstSegmenterCoreTest {

    /**
     * Test Case 1: Verifies the 4 exact chunks from the user prompt:
     * "Bài học cho Minh Khôi: Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn.
     * Không cần khoe mình giỏi, vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc
     * và luôn sẵn sàng học hỏi từ người khác."
     *
     * Expected 4 chunks:
     * 1. "Bài học cho Minh Khôi:."
     * 2. "Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn."
     * 3. "Không cần khoe mình giỏi,"
     * 4. "vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc và luôn sẵn sàng học hỏi từ người khác."
     */
    @Test
    fun testLessonDialogueSegmentationIntegrity() {
        val text = "Bài học cho Minh Khôi: Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn. Không cần khoe mình giỏi, vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc và luôn sẵn sàng học hỏi từ người khác."

        val chunks = SmartTextSegmenter.segment(text)

        assertEquals("Must produce exactly 4 chunks", 4, chunks.size)

        // Chunk 1: Colon header ending with normalized ':.'
        assertEquals("Bài học cho Minh Khôi:.", chunks[0].text)
        assertEquals(PauseType.CLAUSE_BREAK, chunks[0].pauseType)
        assertEquals(180, chunks[0].pauseDurationMs)

        // Chunk 2: Sentence 1 advice
        assertEquals("Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn.", chunks[1].text)
        assertEquals(PauseType.SENTENCE_BREAK, chunks[1].pauseType)
        assertEquals(400, chunks[1].pauseDurationMs)

        // Chunk 3: Sub-clause ending with comma ','
        assertEquals("Không cần khoe mình giỏi,", chunks[2].text)
        assertEquals(PauseType.CLAUSE_BREAK, chunks[2].pauseType)
        assertEquals(180, chunks[2].pauseDurationMs)

        // Chunk 4: Long sub-clause (101 characters) kept completely intact without breaking "sẵn sàng"
        val expectedChunk4 = "vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc và luôn sẵn sàng học hỏi từ người khác."
        assertEquals(expectedChunk4, chunks[3].text)
        assertEquals(101, chunks[3].text.length)
        assertTrue("Chunk 4 must contain intact 'sẵn sàng'", chunks[3].text.contains("sẵn sàng"))
        assertFalse("Chunk 4 must not end with 'sẵn'", chunks[3].text.endsWith("sẵn"))
        assertEquals(PauseType.PARAGRAPH_BREAK, chunks[3].pauseType)
        assertEquals(650, chunks[3].pauseDurationMs)
        assertTrue(chunks[3].isParagraphEnd)
    }

    /**
     * Test Case 2: Verifies long clauses between 75 and 180 characters with words like
     * "sẵn sàng", "khiêm tốn" remain completely intact as single chunks.
     */
    @Test
    fun testCompoundWordNeverSplit() {
        val longClauseSentence = "Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn trong cuộc sống hằng ngày và luôn sẵn sàng học hỏi kinh nghiệm từ người khác."
        assertTrue("Test sentence length must be between 75 and 180 chars", longClauseSentence.length in 75..180)

        val chunks = SmartTextSegmenter.segment(longClauseSentence)

        assertEquals("Sentence without internal punctuation delimiters must remain 1 chunk", 1, chunks.size)
        assertEquals(longClauseSentence, chunks[0].text)
        assertTrue("Must preserve 'sẵn sàng' intact", chunks[0].text.contains("sẵn sàng"))
        assertTrue("Must preserve 'khiêm tốn' intact", chunks[0].text.contains("khiêm tốn"))
        assertTrue("Must preserve 'bình tĩnh' intact", chunks[0].text.contains("bình tĩnh"))
    }

    /**
     * Test Case 3: Verifies colon punctuation normalization ':' yields ':.'
     */
    @Test
    fun testColonPunctuationNormalization() {
        assertEquals("Bài học cho Minh Khôi:.", SmartTextSegmenter.puncNorm("Bài học cho Minh Khôi:"))
        assertEquals("Lời dặn:.", SmartTextSegmenter.puncNorm("Lời dặn:"))
        assertEquals("Lưu ý quan trọng:.", SmartTextSegmenter.puncNorm("Lưu ý quan trọng:"))

        // Already normalized should remain ':.' without double periods
        assertEquals("Bài học cho Minh Khôi:.", SmartTextSegmenter.puncNorm("Bài học cho Minh Khôi:."))

        // Colon segmentation test
        val text = "Ghi chú: Luôn luôn cẩn thận khi lái xe ban đêm."
        val chunks = SmartTextSegmenter.segment(text)
        assertEquals(2, chunks.size)
        assertEquals("Ghi chú:.", chunks[0].text)
        assertEquals("Luôn luôn cẩn thận khi lái xe ban đêm.", chunks[1].text)
    }

    /**
     * Test Case 4: Verifies emergency fallback for run-on clauses > 200 characters without punctuation.
     * Chunks split cleanly at whitespace boundaries without breaking tokens mid-word.
     */
    @Test
    fun testEmergencyFallbackOver200Chars() {
        // Construct a 260-character run-on sentence without any punctuation marks
        val words = listOf(
            "Đây", "là", "một", "đoạn", "văn", "bản", "rất", "dài", "không", "hề",
            "chứa", "bất", "kỳ", "dấu", "chấm", "phẩy", "nào", "được", "tạo", "ra",
            "để", "kiểm", "tra", "tính", "năng", "tự", "động", "cắt", "tại", "khoảng",
            "trắng", "khi", "vượt", "quá", "ngưỡng", "hai", "trăm", "ký", "tự", "mà",
            "vẫn", "đảm", "bảo", "không", "bao", "giờ", "làm", "rách", "từ", "vựng"
        )
        val runOnText = words.joinToString(" ")
        assertTrue("Text must exceed 200 chars (${runOnText.length})", runOnText.length > 200)

        val chunks = SmartTextSegmenter.segment(runOnText)

        assertTrue("Must split into multiple chunks when exceeding 200 chars", chunks.size > 1)
        for (chunk in chunks) {
            val text = chunk.text.removeSuffix(".")
            assertTrue("Chunk length (${text.length}) must be <= 200 chars", text.length <= 200)
            assertTrue("Chunk must not be blank", text.isNotBlank())
            // Verify all tokens in the chunk are whole words from the original word list
            val chunkWords = text.split(" ")
            for (w in chunkWords) {
                assertTrue("Token '$w' must be an unbroken original word", words.contains(w))
            }
        }
    }
}

