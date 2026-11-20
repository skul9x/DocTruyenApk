package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Test

/**
 * Verification test suite for natural sentence segmentation, fluent prosody,
 * and punctuation preservation in VieNeu-TTS.
 *
 * Verifies that:
 * 1. DEFAULT_MAX_CHARS is 180, preventing artificial chopping of normal Vietnamese prose.
 * 2. Full stories (e.g. Story 118 'Một trí khôn hơn trăm trí khôn') produce natural,
 *    unbroken sentences (19 chunks total: 1 title + 18 sentences) instead of fragmented 26 chunks.
 * 3. Dialogue leads ending in colons (':') and clause markers (';', '—') preserve punctuation
 *    and NEVER mutate into broken terminators like ':.' or ';.'.
 * 4. Compound words like 'sẵn sàng' are never chopped mid-word.
 * 5. Pause configurations properly attach to sentence and paragraph boundaries without spurious pauses.
 */
class NaturalSentenceFlowAndCadenceTest {

    private val story118Title = "Một trí khôn hơn trăm trí khôn"
    private val story118Html = """<p>Ngày xưa, có đôi bạn thân là con Chồn và con Gà Rừng. Chồn thường hay khoe khoang rằng nó có hàng trăm trí khôn, còn Gà Rừng thì khiêm tốn bảo chỉ có một trí khôn mà thôi.</p><p>Một buổi sáng nọ, hai bạn đang dạo chơi trên cánh đồng, bất ngờ thấy một người thợ săn đến gần. Họ vội vàng chạy vào một cái hang để trốn. Người thợ săn thấy dấu chân ở cửa hang thì reo lên: "Có mà trốn đằng trời!" rồi dùng gậy thọc vào hang để bắt.</p><p>Gà Rừng lo lắng bảo Chồn:</p><p>- Cậu có hàng trăm trí khôn, hãy nghĩ kế ra đi!</p><p>Nhưng Chồn buồn bã:</p><p>- Lúc này, trong đầu mình chẳng còn một trí khôn nào cả.</p><p>Sau một lúc đắn đo, Gà Rừng nghĩ ra một kế, thì thầm với Chồn:</p><p>- Mình sẽ giả vờ chết cứng, còn cậu cứ như thế nhé!</p><p>Khi người thợ săn lôi Gà Rừng ra thấy nó nằm bất động tưởng đã chết nên quăng nó xuống đám cỏ rồi thọc gậy vào hang bắt Chồn. Bất ngờ, Gà Rừng vùng chạy, người thợ săn đuổi theo. Đợi thế, Chồn cũng chạy thoát vào rừng an toàn.</p><p>Ngày hôm sau, hai bạn gặp lại nhau, Chồn thừa nhận:</p><p>- Một trí khôn của cậu còn hơn cả trăm trí khôn của mình.</p><p><br></p><p><strong>Bài học cho Minh Khôi:</strong> Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn. Không cần khoe mình giỏi, vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc và luôn sẵn sàng học hỏi từ người khác.</p>"""

    @Test
    fun testDefaultMaxCharsIs180() {
        assertEquals(
            "SmartTextSegmenter.DEFAULT_MAX_CHARS must be 180 to encompass complete natural Vietnamese sentences",
            180,
            SmartTextSegmenter.DEFAULT_MAX_CHARS
        )
    }

    @Test
    fun testStory118ProducesExactly19NaturalChunks() {
        val customPause = PauseConfig(
            sentencePauseMs = 250,
            paragraphPauseMs = 400,
            clausePauseMs = 100,
            titlePauseMs = 1000
        )

        val chunks = SmartTextSegmenter.segmentStory(
            title = story118Title,
            content = story118Html,
            pauseConfig = customPause
        )

        // Verifies exactly 19 chunks: 1 title + 18 natural sentences (NOT 26 chopped chunks)
        assertEquals("Story 118 must produce exactly 19 natural chunks", 19, chunks.size)

        // Chunk 0: Title
        assertEquals("Một trí khôn hơn trăm trí khôn.", chunks[0].text)
        assertEquals(PauseType.TITLE_BREAK, chunks[0].pauseType)
        assertEquals(1000, chunks[0].pauseDurationMs)
        assertTrue("Title chunk must mark paragraph end", chunks[0].isParagraphEnd)

        // Chunk 1: Opening sentence
        assertEquals("Ngày xưa, có đôi bạn thân là con Chồn và con Gà Rừng.", chunks[1].text)

        // Chunk 2: Sentence 2 must NOT be chopped at comma into 2 chunks
        assertEquals(
            "Chồn thường hay khoe khoang rằng nó có hàng trăm trí khôn, còn Gà Rừng thì khiêm tốn bảo chỉ có một trí khôn mà thôi.",
            chunks[2].text
        )
        assertTrue(chunks[2].isParagraphEnd)
        assertEquals(PauseType.PARAGRAPH_BREAK, chunks[2].pauseType)
        assertEquals(400, chunks[2].pauseDurationMs)

        // Chunk 3: Sentence 3
        assertEquals(
            "Một buổi sáng nọ, hai bạn đang dạo chơi trên cánh đồng, bất ngờ thấy một người thợ săn đến gần.",
            chunks[3].text
        )

        // Chunk 4: Sentence 4
        assertEquals("Họ vội vàng chạy vào một cái hang để trốn.", chunks[4].text)

        // Chunk 5: Sentence 5 with embedded dialogue quotes
        assertEquals(
            "Người thợ săn thấy dấu chân ở cửa hang thì reo lên: \"Có mà trốn đằng trời!\" rồi dùng gậy thọc vào hang để bắt.",
            chunks[5].text
        )

        // Chunk 6: Dialogue lead ending in ':' must NOT have appended '.' (never ':.')
        assertEquals("Gà Rừng lo lắng bảo Chồn:", chunks[6].text)
        assertFalse("Must not contain ':.'", chunks[6].text.contains(":."))

        // Chunk 7: Dialogue line
        assertEquals("- Cậu có hàng trăm trí khôn, hãy nghĩ kế ra đi!", chunks[7].text)

        // Chunk 8: Dialogue lead ending in ':' must NOT have appended '.'
        assertEquals("Nhưng Chồn buồn bã:", chunks[8].text)
        assertFalse("Must not contain ':.'", chunks[8].text.contains(":."))

        // Chunk 9: Dialogue response
        assertEquals("- Lúc này, trong đầu mình chẳng còn một trí khôn nào cả.", chunks[9].text)

        // Chunk 10: Dialogue lead
        assertEquals("Sau một lúc đắn đo, Gà Rừng nghĩ ra một kế, thì thầm với Chồn:", chunks[10].text)

        // Chunk 11: Dialogue line
        assertEquals("- Mình sẽ giả vờ chết cứng, còn cậu cứ như thế nhé!", chunks[11].text)

        // Chunk 12: Long action sentence (125 chars) must NOT be chopped in the middle
        assertEquals(
            "Khi người thợ săn lôi Gà Rừng ra thấy nó nằm bất động tưởng đã chết nên quăng nó xuống đám cỏ rồi thọc gậy vào hang bắt Chồn.",
            chunks[12].text
        )

        // Chunk 13 & 14: Action climax
        assertEquals("Bất ngờ, Gà Rừng vùng chạy, người thợ săn đuổi theo.", chunks[13].text)
        assertEquals("Đợi thế, Chồn cũng chạy thoát vào rừng an toàn.", chunks[14].text)

        // Chunk 15: Concluding dialogue lead
        assertEquals("Ngày hôm sau, hai bạn gặp lại nhau, Chồn thừa nhận:", chunks[15].text)
        assertFalse("Must not contain ':.'", chunks[15].text.contains(":."))

        // Chunk 16: Concluding dialogue
        assertEquals("- Một trí khôn của cậu còn hơn cả trăm trí khôn của mình.", chunks[16].text)

        // Chunk 17: Moral lesson header and first sentence (must NOT chop across ':')
        assertEquals("Bài học cho Minh Khôi: Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn.", chunks[17].text)

        // Chunk 18: Final moral lesson sentence - must NOT chop 'sẵn sàng' in half
        assertEquals(
            "Không cần khoe mình giỏi, vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc và luôn sẵn sàng học hỏi từ người khác.",
            chunks[18].text
        )
        assertTrue("Must contain intact 'sẵn sàng'", chunks[18].text.contains("sẵn sàng"))
        assertFalse("Must not contain trailing chopped 'sẵn.'", chunks[18].text.contains("sẵn."))
    }

    @Test
    fun testPuncNormPreservesColonsAndClauseDelimiters() {
        // Colons
        assertEquals("Gà Rừng lo lắng bảo Chồn:", SmartTextSegmenter.puncNorm("Gà Rừng lo lắng bảo Chồn:"))
        assertEquals("Nhưng Chồn buồn bã:", SmartTextSegmenter.puncNorm("Nhưng Chồn buồn bã:"))
        assertEquals("Bài học cho Minh Khôi:", SmartTextSegmenter.puncNorm("Bài học cho Minh Khôi:"))

        // Semicolons and dashes
        assertEquals("Nước chảy êm đềm;", SmartTextSegmenter.puncNorm("Nước chảy êm đềm;"))
        assertEquals("Này người anh em—", SmartTextSegmenter.puncNorm("Này người anh em—"))
        assertEquals("Đến lúc chia tay–", SmartTextSegmenter.puncNorm("Đến lúc chia tay–"))

        // Terminal sentences
        assertEquals("Xin chào.", SmartTextSegmenter.puncNorm("Xin chào"))
        assertEquals("Một hai ba bốn năm sáu.", SmartTextSegmenter.puncNorm("Một hai ba bốn năm sáu"))
        assertEquals("Một hai ba bốn năm sáu!", SmartTextSegmenter.puncNorm("Một hai ba bốn năm sáu!"))
        assertEquals("Một hai ba bốn năm sáu?", SmartTextSegmenter.puncNorm("Một hai ba bốn năm sáu?"))
        assertEquals("Một hai ba bốn năm sáu,", SmartTextSegmenter.puncNorm("Một hai ba bốn năm sáu,"))
    }

    @Test
    fun testUltraLongSentenceSplittingAtClauseMarkers() {
        // A single sentence exceeding 180 chars (approx 220 chars) with natural clause semicolons and commas
        val longSentence = "Ngày xưa, ở một ngôi làng nọ bên cạnh dòng sông xanh biếc quanh năm nước chảy êm đềm; có một gia đình tiều phu nghèo khó nhưng luôn luôn sống lương thiện, hòa đồng và hết lòng giúp đỡ tất cả bà con lối xóm xung quanh."
        val chunks = SmartTextSegmenter.segment(longSentence)

        assertTrue("Long sentence > 180 chars should split into sub-clauses", chunks.size >= 2)
        for (chunk in chunks) {
            assertTrue("Each sub-clause must be <= 185 chars: '${chunk.text}'", chunk.text.length <= 185)
        }

        // Intermediate clause ending with ',' or ';' should preserve clause boundary and assign CLAUSE_BREAK
        val firstChunk = chunks[0]
        assertTrue("First sub-clause should preserve clause boundary", firstChunk.text.endsWith(",") || firstChunk.text.endsWith(";"))
        assertEquals(PauseType.CLAUSE_BREAK, firstChunk.pauseType)
    }

    @Test
    fun testZeroGapPauseCadenceOnUnbrokenSentences() {
        val zeroGap = PauseConfig(
            sentencePauseMs = 0,
            paragraphPauseMs = 0,
            clausePauseMs = 0,
            titlePauseMs = 0
        )

        val chunks = SmartTextSegmenter.segmentStory(
            title = "Tiêu đề",
            content = "Câu thứ nhất rất mượt mà. Câu thứ hai liền mạch không bị ngắt chữ.",
            pauseConfig = zeroGap
        )

        assertEquals(3, chunks.size) // Title + 2 sentences
        assertEquals(0, chunks[0].pauseDurationMs)
        assertEquals(0, chunks[1].pauseDurationMs)
        assertEquals(0, chunks[2].pauseDurationMs)
    }
}
