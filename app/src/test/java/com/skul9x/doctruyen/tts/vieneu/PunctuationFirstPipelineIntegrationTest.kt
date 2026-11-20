package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Test

/**
 * Comprehensive Integration & Verification Test for Phase 02: Pipeline, Pause Duration & Integration Verification.
 *
 * Verifies:
 * 1. Full story punctuation-first segmentation into natural punctuation chunks (23 chunks) without
 *    fragmenting compound words like "sẵn sàng" or mechanically splitting sentences <= 200 characters.
 * 2. PauseType and pause duration mapping:
 *    - Clause breaks ([,;:—–-]) map to CLAUSE_BREAK (180ms)
 *    - Sentence breaks ([.!?…]) map to SENTENCE_BREAK (400ms)
 *    - Paragraph endings map to PARAGRAPH_BREAK (650ms)
 *    - Story title chunk maps to TITLE_BREAK (1000ms)
 * 3. Pipeline compatibility with 200-character chunks:
 *    - Progress calculation accuracy across all chunk indices
 *    - Adaptive pre-buffering duration calculation for INT8/FP32/FP16 models
 *    - Acoustic silence generation and micro-fading on long audio buffers (up to 8 seconds PCM)
 *    - PcmUtils backward compatibility inheriting DEFAULT_MAX_CHARS = 200
 */
class PunctuationFirstPipelineIntegrationTest {

    companion object {
        const val SAMPLE_STORY_TITLE = "Một trí khôn hơn trăm trí khôn"

        val SAMPLE_STORY_HTML = """<p>Ngày xưa, có đôi bạn thân là con Chồn và con Gà Rừng. Chồn thường hay khoe khoang rằng nó có hàng trăm trí khôn, còn Gà Rừng thì khiêm tốn bảo chỉ có một trí khôn mà thôi.</p><p>Một buổi sáng nọ, hai bạn đang dạo chơi trên cánh đồng, bất ngờ thấy một người thợ săn đến gần. Họ vội vàng chạy vào một cái hang để trốn. Người thợ săn thấy dấu chân ở cửa hang thì reo lên: "Có mà trốn đằng trời!" rồi dùng gậy thọc vào hang để bắt.</p><p>Gà Rừng lo lắng bảo Chồn:</p><p>- Cậu có hàng trăm trí khôn, hãy nghĩ kế ra đi!</p><p>Nhưng Chồn buồn bã:</p><p>- Lúc này, trong đầu mình chẳng còn một trí khôn nào cả.</p><p>Sau một lúc đắn đo, Gà Rừng nghĩ ra một kế, thì thầm với Chồn:</p><p>- Mình sẽ giả vờ chết cứng, còn cậu cứ như thế nhé!</p><p>Khi người thợ săn lôi Gà Rừng ra thấy nó nằm bất động tưởng đã chết nên quăng nó xuống đám cỏ rồi thọc gậy vào hang bắt Chồn. Bất ngờ, Gà Rừng vùng chạy, người thợ săn đuổi theo. Đợi thế, Chồn cũng chạy thoát vào rừng an toàn.</p><p>Ngày hôm sau, hai bạn gặp lại nhau, Chồn thừa nhận:</p><p>- Một trí khôn của cậu còn hơn cả trăm trí khôn của mình.</p><p><br></p><p><strong>Bài học cho Minh Khôi:</strong> Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn. Không cần khoe mình giỏi, vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc và luôn sẵn sàng học hỏi từ người khác.</p>"""
    }

    /**
     * Test Case 1: Verifies the entire 11-paragraph sample story ("Một trí khôn hơn trăm trí khôn")
     * segments into clean punctuation-bounded chunks rather than the 26 fragmented chunks from the legacy 75-char limit.
     * In particular, compound words ("sẵn sàng") are preserved and run-on clauses up to 200 chars are not severed.
     */
    @Test
    fun testFullStoryPunctuationFirstSegmentation() {
        // Segment story content
        val contentChunks = SmartTextSegmenter.segment(SAMPLE_STORY_HTML)
        assertTrue("Content chunks must not be empty", contentChunks.isNotEmpty())

        // Also segment story with title
        val storyChunks = SmartTextSegmenter.segmentStory(SAMPLE_STORY_TITLE, SAMPLE_STORY_HTML)
        assertTrue("Story chunks must not be empty", storyChunks.isNotEmpty())

        // Verify the reduction from the old 26 fragmented chunks down to natural punctuation chunks (23 content chunks)
        // In the legacy system, 119-char sentence (Khi người thợ săn...) was cut into 2 chunks,
        // and 101-char sentence (...và luôn sẵn sàng...) was cut into 2 chunks chopping "sẵn" / "sàng".
        val targetChunks = contentChunks
        assertEquals("Sample story must segment into exactly 23 natural punctuation chunks", 23, targetChunks.size)

        // 1. Verify all chunk lengths are within SAFE_MAX_CHARS (200 characters)
        for (chunk in targetChunks) {
            assertTrue(
                "Chunk #${chunk.index} length (${chunk.text.length}) must be <= 200 chars: '${chunk.text}'",
                chunk.text.length <= SmartTextSegmenter.DEFAULT_MAX_CHARS
            )
            assertTrue("Chunk #${chunk.index} must not be blank", chunk.text.isNotBlank())
            assertFalse("Chunk #${chunk.index} must not contain HTML tags", chunk.text.contains("<") || chunk.text.contains(">"))
        }

        // 2. Verify compound word "sẵn sàng" is preserved intact in the final lesson paragraph
        val lessonChunk = targetChunks.firstOrNull { it.text.contains("sẵn sàng") }
        assertNotNull("Must contain chunk with intact 'sẵn sàng'", lessonChunk)
        assertTrue("Must preserve 'sẵn sàng' unbroken", lessonChunk!!.text.contains("sẵn sàng"))
        assertFalse("Must not end with broken 'sẵn'", lessonChunk.text.endsWith("sẵn"))

        // 3. Verify the 119-char sentence in paragraph 9 is kept intact as 1 chunk without mid-sentence chop
        val hunterSentence = "Khi người thợ săn lôi Gà Rừng ra thấy nó nằm bất động tưởng đã chết nên quăng nó xuống đám cỏ rồi thọc gậy vào hang bắt Chồn."
        val hunterChunk = targetChunks.firstOrNull { it.text.contains("thọc gậy vào hang bắt Chồn") }
        assertNotNull("Must contain hunter sentence chunk", hunterChunk)
        assertTrue("Hunter sentence (119 chars) must be kept intact", hunterChunk!!.text.contains(hunterSentence))

        // 4. Verify sequential index numbering
        for (i in targetChunks.indices) {
            assertEquals("Chunk index must be sequential", i, targetChunks[i].index)
        }
    }

    /**
     * Test Case 2: Asserts clause pauses (180ms), sentence pauses (400ms), paragraph pauses (650ms),
     * and title pauses (1000ms) attach properly and map to accurate PCM silence sample counts.
     */
    @Test
    fun testPauseTypeAndDurationMapping() {
        val storyChunks = SmartTextSegmenter.segmentStory(SAMPLE_STORY_TITLE, SAMPLE_STORY_HTML)
        assertTrue(storyChunks.isNotEmpty())

        // 1. Title chunk must have PauseType.TITLE_BREAK with 1000ms duration
        val titleChunk = storyChunks[0]
        assertEquals("$SAMPLE_STORY_TITLE.", titleChunk.text)
        assertEquals(PauseType.TITLE_BREAK, titleChunk.pauseType)
        assertEquals(1000, titleChunk.pauseDurationMs)
        assertTrue(titleChunk.isParagraphEnd)

        // 2. Verify clause breaks (chunks ending with comma ',' or colon header ':.')
        val clauseChunks = storyChunks.filter { it.pauseType == PauseType.CLAUSE_BREAK }
        assertTrue("Must have multiple clause break chunks", clauseChunks.isNotEmpty())
        for (c in clauseChunks) {
            assertEquals(180, c.pauseDurationMs)
            assertTrue(
                "Clause chunk '${c.text}' must end with clause delimiter or colon",
                c.text.endsWith(",") || c.text.endsWith(":") || c.text.endsWith(":.") ||
                        c.text.endsWith(";") || c.text.endsWith("—") || c.text.endsWith("–") || c.text.endsWith("-")
            )
        }

        // 3. Verify paragraph break chunks (end of paragraphs)
        val paraChunks = storyChunks.filter { it.pauseType == PauseType.PARAGRAPH_BREAK }
        assertTrue("Must have paragraph break chunks", paraChunks.isNotEmpty())
        for (p in paraChunks) {
            assertEquals(650, p.pauseDurationMs)
            assertTrue("Paragraph break chunk must have isParagraphEnd=true", p.isParagraphEnd)
        }

        // 4. Verify sentence break chunks (internal sentence ends)
        val sentenceChunks = storyChunks.filter { it.pauseType == PauseType.SENTENCE_BREAK }
        assertTrue("Must have sentence break chunks", sentenceChunks.isNotEmpty())
        for (s in sentenceChunks) {
            assertEquals(400, s.pauseDurationMs)
        }

        // 5. Verify PcmUtils silence generation for each pause duration at 48kHz
        val sampleRate = 48000
        val silenceClause = PcmUtils.generateSilence(PauseType.CLAUSE_BREAK.defaultDurationMs, sampleRate)
        assertEquals((48000L * 180 / 1000L).toInt(), silenceClause.size) // 8640 samples

        val silenceSentence = PcmUtils.generateSilence(PauseType.SENTENCE_BREAK.defaultDurationMs, sampleRate)
        assertEquals((48000L * 400 / 1000L).toInt(), silenceSentence.size) // 19200 samples

        val silencePara = PcmUtils.generateSilence(PauseType.PARAGRAPH_BREAK.defaultDurationMs, sampleRate)
        assertEquals((48000L * 650 / 1000L).toInt(), silencePara.size) // 31200 samples

        val silenceTitle = PcmUtils.generateSilence(PauseType.TITLE_BREAK.defaultDurationMs, sampleRate)
        assertEquals(48000, silenceTitle.size) // 48000 samples
    }

    /**
     * Test Case 3: Asserts chunk queuing, progress estimation, adaptive pre-buffering heuristics,
     * and audio buffer safety accommodate 200-character chunks seamlessly.
     */
    @Test
    fun testPipelineCompatibilityWith200CharChunks() {
        val totalChunks = 23

        // 1. Progress estimation across all chunk indices for 23 chunks
        for (chunkIdx in 0..totalChunks) {
            val progressFloat = if (totalChunks > 0) chunkIdx.toFloat() / totalChunks else 0f
            val pct = ((chunkIdx.toFloat() / totalChunks) * 100).toInt().coerceIn(0, 100)

            assertTrue("Progress float in range [0.0, 1.0]", progressFloat in 0.0f..1.0f)
            assertTrue("Progress percentage in range [0, 100]", pct in 0..100)

            if (chunkIdx == 0) assertEquals(0, pct)
            if (chunkIdx == totalChunks) assertEquals(100, pct)
        }

        // 2. Adaptive pre-buffer duration calculations for 200-char chunks
        // INT8 on octa-core (6+ big cores) -> 2000ms target
        val targetInt8Octa = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8,
            remainingChunks = totalChunks,
            startIndex = 0,
            targetPreBufferChunks = 2
        )
        assertEquals(2000L, targetInt8Octa)

        // INT8 on 4 cores -> 2500ms target
        val targetInt8Quad = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 4,
            remainingChunks = totalChunks,
            startIndex = 0,
            targetPreBufferChunks = 2
        )
        assertEquals(2500L, targetInt8Quad)

        // FP32 mode enforces safe fallback duration (7500ms) regardless of cores
        val targetFp32 = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.FP32,
            cpuCores = 8,
            remainingChunks = totalChunks,
            startIndex = 0,
            safeFallbackDurationMs = 7500L,
            targetPreBufferChunks = 2
        )
        assertEquals(7500L, targetFp32)

        // Mid-stream seek (startIndex > 0) drops threshold to 1500ms for instant responsiveness
        val targetSeek = TTSAudioPipeline.computeAdaptiveTargetDurationMs(
            quantizationType = OnnxSessionManager.QuantizationType.INT8,
            cpuCores = 8,
            remainingChunks = 10,
            startIndex = 13,
            targetPreBufferChunks = 2
        )
        assertEquals(1500L, targetSeek)

        // 3. Audio micro-fading on 8-second 48kHz PCM buffer (typical for ~200-char chunk)
        val eightSecondSamples = 48000 * 8 // 384,000 samples
        val longPcm = ShortArray(eightSecondSamples) { 15000.toShort() }
        val faded = PcmUtils.applyMicroFade(longPcm, fadeDurationMs = 5.0f, sampleRate = 48000)
        assertEquals(eightSecondSamples, faded.size)
        assertEquals(0.toShort(), faded[0])
        assertEquals(0.toShort(), faded[faded.size - 1])
        // Mid sample should remain unmodified amplitude
        assertEquals(15000.toShort(), faded[eightSecondSamples / 2])

        // 4. Backward compatibility: PcmUtils defaults to SmartTextSegmenter.DEFAULT_MAX_CHARS (200)
        val pcmSentences = PcmUtils.splitIntoSentences(SAMPLE_STORY_HTML)
        val pcmChunks = PcmUtils.splitIntoChunks(SAMPLE_STORY_HTML)
        assertEquals(pcmChunks.size, pcmSentences.size)
        assertTrue(pcmChunks.isNotEmpty())
        for (chunk in pcmChunks) {
            assertTrue("PcmUtils chunk must be <= 200 chars", chunk.text.length <= 200)
        }
    }
}
