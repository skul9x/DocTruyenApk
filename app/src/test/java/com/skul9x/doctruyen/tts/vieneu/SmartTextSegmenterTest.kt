package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Test

class SmartTextSegmenterTest {

    @Test
    fun testParagraphBreakPauseDuration() {
        val text = "Đoạn một có câu chuyện đầu tiên.\n\nĐoạn hai kể về chuyến phiêu lưu tiếp theo."
        val chunks = SmartTextSegmenter.segment(text)

        assertEquals(2, chunks.size)
        assertEquals("Đoạn một có câu chuyện đầu tiên.", chunks[0].text)
        assertEquals(PauseType.PARAGRAPH_BREAK, chunks[0].pauseType)
        assertEquals(650, chunks[0].pauseDurationMs)
        assertTrue(chunks[0].isParagraphEnd)

        assertEquals("Đoạn hai kể về chuyến phiêu lưu tiếp theo.", chunks[1].text)
        assertEquals(PauseType.PARAGRAPH_BREAK, chunks[1].pauseType)
        assertEquals(650, chunks[1].pauseDurationMs)
        assertTrue(chunks[1].isParagraphEnd)
    }

    @Test
    fun testHtmlParagraphTagsPreservation() {
        val html = "<p>Đoạn văn thứ nhất.</p><p>Đoạn văn thứ hai.<br/>Dòng tiếp theo trong đoạn.</p>"
        val chunks = SmartTextSegmenter.segment(html)

        assertTrue(chunks.size >= 2)
        assertEquals("Đoạn văn thứ nhất.", chunks[0].text)
        assertEquals(PauseType.PARAGRAPH_BREAK, chunks[0].pauseType)
        assertTrue(chunks[0].isParagraphEnd)
    }

    @Test
    fun testVietnameseAbbreviationsProtection() {
        val text = "BS. Hùng khám tại TP. Hồ Chí Minh lúc 8.30 sáng."
        val chunks = SmartTextSegmenter.segment(text)

        // Must not split BS. or TP. or 8.30
        assertEquals(1, chunks.size)
        assertEquals("BS. Hùng khám tại TP. Hồ Chí Minh lúc 8.30 sáng.", chunks[0].text)
        assertEquals(PauseType.PARAGRAPH_BREAK, chunks[0].pauseType)

        val text2 = "GS.TS. Nguyễn Văn A và ThS. Trần Thị B cùng đến TP.HCM lúc 10:30."
        val chunks2 = SmartTextSegmenter.segment(text2)
        assertEquals(1, chunks2.size)
        assertEquals("GS.TS. Nguyễn Văn A và ThS. Trần Thị B cùng đến TP.HCM lúc 10:30.", chunks2[0].text)
    }

    @Test
    fun testDialogueFormattingAndLeadPause() {
        val dialogueText = "Gà Rừng lo lắng bảo Chồn:\n\n- Cậu có hàng trăm trí khôn, hãy nghĩ kế ra đi!\n\nNhưng Chồn buồn bã:\n\n- Lúc này, trong đầu mình chẳng còn một trí khôn nào cả."
        val chunks = SmartTextSegmenter.segment(dialogueText)

        assertTrue(chunks.isNotEmpty())

        val dialogueChunks = chunks.filter { it.text.startsWith("- ") }
        assertEquals(2, dialogueChunks.size)
        assertEquals("- Cậu có hàng trăm trí khôn, hãy nghĩ kế ra đi!", dialogueChunks[0].text)
        assertEquals("- Lúc này, trong đầu mình chẳng còn một trí khôn nào cả.", dialogueChunks[1].text)
    }

    @Test
    fun testClauseBreakDetection() {
        // Long sentence with multiple clauses to force splitting
        val longClause = "Ngày xưa, ở một ngôi làng nọ bên cạnh dòng sông xanh mát, có một chú bé rất chăm chỉ học hành và luôn giúp đỡ mọi người xung quanh trong mọi hoàn cảnh khó khăn."
        val chunks = SmartTextSegmenter.segment(longClause, maxCharsPerChunk = 70)

        assertTrue(chunks.size > 1)
        val clauseChunks = chunks.filter { it.pauseType == PauseType.CLAUSE_BREAK }
        assertTrue("Should identify clause breaks for sub-chunks", clauseChunks.isNotEmpty())
        assertEquals(180, PauseType.CLAUSE_BREAK.defaultDurationMs)
    }

    @Test
    fun testSegmentStoryWithTitlePause() {
        val title = "Một trí khôn hơn trăm trí khôn"
        val content = "Ngày xưa, có đôi bạn thân là con Chồn và con Gà Rừng. Chồn thường hay khoe khoang rằng nó có hàng trăm trí khôn."
        val chunks = SmartTextSegmenter.segmentStory(title, content)

        assertTrue(chunks.isNotEmpty())
        assertEquals("Một trí khôn hơn trăm trí khôn.", chunks[0].text)
        assertEquals(PauseType.TITLE_BREAK, chunks[0].pauseType)
        assertEquals(1000, chunks[0].pauseDurationMs)
        assertTrue(chunks[0].isParagraphEnd)

        assertEquals("Ngày xưa, có đôi bạn thân là con Chồn và con Gà Rừng.", chunks[1].text)
        assertEquals(PauseType.SENTENCE_BREAK, chunks[1].pauseType)
        assertEquals(400, chunks[1].pauseDurationMs)
    }

    @Test
    fun testFullStoryFromLogTxt() {
        val fullHtmlContent = """<p>Ngày xưa, có đôi bạn thân là con Chồn và con Gà Rừng. Chồn thường hay khoe khoang rằng nó có hàng trăm trí khôn, còn Gà Rừng thì khiêm tốn bảo chỉ có một trí khôn mà thôi.</p><p>Một buổi sáng nọ, hai bạn đang dạo chơi trên cánh đồng, bất ngờ thấy một người thợ săn đến gần. Họ vội vàng chạy vào một cái hang để trốn. Người thợ săn thấy dấu chân ở cửa hang thì reo lên: "Có mà trốn đằng trời!" rồi dùng gậy thọc vào hang để bắt.</p><p>Gà Rừng lo lắng bảo Chồn:</p><p>- Cậu có hàng trăm trí khôn, hãy nghĩ kế ra đi!</p><p>Nhưng Chồn buồn bã:</p><p>- Lúc này, trong đầu mình chẳng còn một trí khôn nào cả.</p><p>Sau một lúc đắn đo, Gà Rừng nghĩ ra một kế, thì thầm với Chồn:</p><p>- Mình sẽ giả vờ chết cứng, còn cậu cứ như thế nhé!</p><p>Khi người thợ săn lôi Gà Rừng ra thấy nó nằm bất động tưởng đã chết nên quăng nó xuống đám cỏ rồi thọc gậy vào hang bắt Chồn. Bất ngờ, Gà Rừng vùng chạy, người thợ săn đuổi theo. Đợi thế, Chồn cũng chạy thoát vào rừng an toàn.</p><p>Ngày hôm sau, hai bạn gặp lại nhau, Chồn thừa nhận:</p><p>- Một trí khôn của cậu còn hơn cả trăm trí khôn của mình.</p><p><br></p><p><strong>Bài học cho Minh Khôi:</strong> Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn. Không cần khoe mình giỏi, vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc và luôn sẵn sàng học hỏi từ người khác.</p>"""

        val chunks = SmartTextSegmenter.segment(fullHtmlContent)

        assertTrue("Story should produce several chunks", chunks.isNotEmpty())
        for (chunk in chunks) {
            assertTrue("Chunk text cannot be blank", chunk.text.isNotBlank())
            assertFalse("HTML tags must be cleaned", chunk.text.contains("<p>") || chunk.text.contains("</p>"))
            assertTrue("Chunk max length constraint", chunk.text.length <= 180)
        }

        // Verify that indices are sequentially ordered
        for (i in chunks.indices) {
            assertEquals(i, chunks[i].index)
        }
    }

    @Test
    fun testSegmentationPerformanceBenchmark() {
        val sample = "BS. Hùng và TS. Tuấn cùng đi dạo tại TP.HCM.\n"
        // JVM Warm-up
        repeat(100) {
            SmartTextSegmenter.segment(sample)
        }

        val sb = StringBuilder()
        repeat(500) {
            sb.append("Đây là câu kiểm tra hiệu năng xử lý văn bản tiếng Việt của SmartTextSegmenter. BS. Hùng và TS. Tuấn cùng đi dạo tại TP.HCM.\n")
        }
        val text = sb.toString()
        assertTrue(text.length >= 50000)

        val startTime = System.nanoTime()
        val chunks = SmartTextSegmenter.segment(text)
        val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

        assertTrue(chunks.isNotEmpty())
        val charsPerMs = text.length / (if (elapsedMs == 0.0) 0.001 else elapsedMs)
        // High throughput check (> 500 chars/ms in unit test runner)
        assertTrue("Segmentation throughput: $charsPerMs chars/ms (elapsed: ${elapsedMs}ms)", charsPerMs > 500.0)
    }
}
