package com.skul9x.doctruyen.tts.vieneu

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Verification test for Phase 03: Emotion Tags Extraction & Novel Bracket Separation.
 *
 * Verifies:
 * 1. Emotion tag token encoding ([cười], [thở dài], [hắng giọng] -> tokens 9, 10, 11).
 * 2. Novel footnote brackets ([1], [CV: ...], [Chương 2]) remain uncorrupted as regular text.
 * 3. Pre-G2P bypass integrity (SeaG2P normalizer bypass preserves <|emotion_k|> intact).
 * 4. Punctuation attachment rules ([thở dài].) without spurious whitespace or silence gaps.
 */
class EmotionTagsAndBracketSeparationTest {

    private lateinit var tokenizer: VieNeuTokenizer

    @Before
    fun setup() {
        val candidatePaths = listOf(
            "src/main/assets/vieneu/backbone/tokenizer.json",
            "app/src/main/assets/vieneu/backbone/tokenizer.json"
        )
        val file = candidatePaths.map { File(it) }.firstOrNull { it.exists() }
        assertNotNull("tokenizer.json must exist in assets", file)
        tokenizer = VieNeuTokenizer.fromFile(file!!)
    }

    /**
     * Test Scenario 1: Emotion Tag Token Encoding
     * Verify that [cười], [thở dài], and [hắng giọng] (and their aliases and case variants)
     * correctly resolve to tokens 9, 10, and 11.
     */
    @Test
    fun testEmotionTagTokenEncoding() {
        // Direct special tokens
        assertArrayEquals(intArrayOf(9), tokenizer.encode("<|emotion_1|>"))
        assertArrayEquals(intArrayOf(10), tokenizer.encode("<|emotion_2|>"))
        assertArrayEquals(intArrayOf(11), tokenizer.encode("<|emotion_3|>"))

        // Recognized cue: cười / cuoi / chuckle -> token 9
        assertArrayEquals(intArrayOf(9), tokenizer.encode("[cười]"))
        assertArrayEquals(intArrayOf(9), tokenizer.encode("[cuoi]"))
        assertArrayEquals(intArrayOf(9), tokenizer.encode("[chuckle]"))
        assertArrayEquals(intArrayOf(9), tokenizer.encode("[CƯỜI]"))
        assertArrayEquals(intArrayOf(9), tokenizer.encode("[Cười]"))
        assertEquals(9, EmotionTagParser.getEmotionTokenId("cười"))
        assertEquals(9, EmotionTagParser.getEmotionTokenId("[cười]"))
        assertEquals("<|emotion_1|>", EmotionTagParser.getEmotionToken("cười"))

        // Recognized cue: thở dài / tho dai / sigh -> token 10
        assertArrayEquals(intArrayOf(10), tokenizer.encode("[thở dài]"))
        assertArrayEquals(intArrayOf(10), tokenizer.encode("[tho dai]"))
        assertArrayEquals(intArrayOf(10), tokenizer.encode("[sigh]"))
        assertArrayEquals(intArrayOf(10), tokenizer.encode("[THỞ DÀI]"))
        assertArrayEquals(intArrayOf(10), tokenizer.encode("[Thở dài]"))
        assertEquals(10, EmotionTagParser.getEmotionTokenId("thở dài"))
        assertEquals(10, EmotionTagParser.getEmotionTokenId("[thở dài]"))
        assertEquals("<|emotion_2|>", EmotionTagParser.getEmotionToken("thở dài"))

        // Recognized cue: hắng giọng / hang giong / clear throat -> token 11
        assertArrayEquals(intArrayOf(11), tokenizer.encode("[hắng giọng]"))
        assertArrayEquals(intArrayOf(11), tokenizer.encode("[hang giong]"))
        assertArrayEquals(intArrayOf(11), tokenizer.encode("[clear throat]"))
        assertArrayEquals(intArrayOf(11), tokenizer.encode("[HẮNG GIỌNG]"))
        assertArrayEquals(intArrayOf(11), tokenizer.encode("[Hắng giọng]"))
        assertEquals(11, EmotionTagParser.getEmotionTokenId("hắng giọng"))
        assertEquals(11, EmotionTagParser.getEmotionTokenId("[hắng giọng]"))
        assertEquals("<|emotion_3|>", EmotionTagParser.getEmotionToken("hắng giọng"))
    }

    /**
     * Test Scenario 2: Novel Footnote Brackets Not Corrupted
     * Assert that "Chương 1 [1] hắn cười [cười]" treats [1] as text and [cười] as emotion token 9.
     * Also verifies arbitrary novel annotations and malformed brackets do not crash or corrupt.
     */
    @Test
    fun testNovelFootnoteBracketsNotCorrupted() {
        val input = "Chương 1 [1] hắn cười [cười]"

        // 1. Parser verification: [1] is kept as text, [cười] becomes <|emotion_1|>
        val parsed = EmotionTagParser.parse(input)
        assertEquals("Chương 1 [1] hắn cười <|emotion_1|>", parsed)
        assertTrue("[1] footnote must be preserved in parsed text", parsed.contains("[1]"))
        assertTrue("[cười] must be mapped to <|emotion_1|>", parsed.contains("<|emotion_1|>"))
        assertFalse("Raw [cười] tag should no longer be present", parsed.contains("[cười]"))

        // 2. Tokenizer verification: [cười] maps to 9, [1] does NOT map to emotion tokens 9, 10, or 11
        val tokens = tokenizer.encode(input)
        assertTrue("Tokens must contain emotion token 9", tokens.contains(9))

        // Ensure [1] does not map to emotion token 9, 10, or 11
        val footnoteOnlyTokens = tokenizer.encode("[1]")
        assertFalse("Footnote [1] must not contain token 9", footnoteOnlyTokens.contains(9))
        assertFalse("Footnote [1] must not contain token 10", footnoteOnlyTokens.contains(10))
        assertFalse("Footnote [1] must not contain token 11", footnoteOnlyTokens.contains(11))

        // 3. Verify arbitrary novel annotations pass through unharmed
        val novelText = "Nguồn: [CV: Bác Ba Phi] [Xem chú thích] [Chương 2] hắn cười [cười]"
        val parsedNovel = EmotionTagParser.parse(novelText)
        assertTrue(parsedNovel.contains("[CV: Bác Ba Phi]"))
        assertTrue(parsedNovel.contains("[Xem chú thích]"))
        assertTrue(parsedNovel.contains("[Chương 2]"))
        assertTrue(parsedNovel.contains("<|emotion_1|>"))

        // 4. Safe processing with malformed brackets (unclosed, nested, empty)
        assertFalse("Unrecognized bracket [1] is not an emotion tag", EmotionTagParser.isEmotionTag("[1]"))
        assertFalse("Unrecognized bracket [CV: ...] is not an emotion tag", EmotionTagParser.isEmotionTag("[CV: ...]"))
        assertNull(EmotionTagParser.getEmotionTokenId("[1]"))

        // Unclosed '[' or unmatched ']'
        assertEquals("Hắn cười [cười dở", EmotionTagParser.parse("Hắn cười [cười dở"))
        assertEquals("Hắn cười cười]", EmotionTagParser.parse("Hắn cười cười]"))
        // Empty brackets
        assertEquals("Hắn cười []", EmotionTagParser.parse("Hắn cười []"))
        // Nested brackets
        val nested = EmotionTagParser.parse("Hắn cười [[cười]]")
        assertTrue("Nested brackets safely handled", nested.contains("<|emotion_1|>"))
    }

    /**
     * Test Scenario 3: G2P Bypass Integrity
     * Verify that sea-g2p normalizer phonemizes only the natural text portions
     * and preserves <|emotion_k|> special tokens intact.
     */
    @Test
    fun testG2PBypassIntegrity() {
        val capturedCalls = mutableListOf<String>()
        val mockPhonemizer: (String) -> String = { text ->
            capturedCalls.add(text)
            "PHONE($text)"
        }

        val input = "Chương 1 [1] hắn cười [cười]"
        val result = EmotionTagParser.phonemizeWithBypass(input, mockPhonemizer)

        // 1. Phonemizer was called ONLY for the text part, never for <|emotion_1|>
        assertEquals(1, capturedCalls.size)
        assertEquals("Chương 1 [1] hắn cười", capturedCalls[0])
        assertFalse("Phonemizer must never receive <|emotion_1|>", capturedCalls.any { it.contains("emotion") })

        // 2. Result has spliced intact special token
        assertEquals("PHONE(Chương 1 [1] hắn cười) <|emotion_1|>", result)
        assertTrue(result.contains("<|emotion_1|>"))

        // 3. Multi-tag test with SeaG2P.phonemize integration
        val multiInput = "Hắn im lặng [thở dài]. Sau đó hắng giọng [hắng giọng] và cười [cười]."
        val seaG2PResult = VieNeuTTSManager.phonemizeWithEmotionBypass(multiInput)

        assertTrue("SeaG2P bypass must retain <|emotion_2|>", seaG2PResult.contains("<|emotion_2|>"))
        assertTrue("SeaG2P bypass must retain <|emotion_3|>", seaG2PResult.contains("<|emotion_3|>"))
        assertTrue("SeaG2P bypass must retain <|emotion_1|>", seaG2PResult.contains("<|emotion_1|>"))
    }

    /**
     * Test Scenario 4: Punctuation Attachment
     * Verify that trailing punctuation ([thở dài].) remains attached without synthetic silence gaps.
     */
    @Test
    fun testPunctuationAttachment() {
        // 1. Parsing preserves trailing punctuation directly attached
        val textWithPeriod = "[thở dài]."
        val parsedPeriod = EmotionTagParser.parse(textWithPeriod)
        assertEquals("<|emotion_2|>.", parsedPeriod)

        // Trailing punctuation with spurious whitespace is normalized
        val textWithSpace = "[thở dài] ."
        val parsedSpace = EmotionTagParser.parse(textWithSpace)
        assertEquals("<|emotion_2|>.", parsedSpace)

        // Comma, exclamation, question mark, ellipsis
        assertEquals("<|emotion_1|>,", EmotionTagParser.parse("[cười],"))
        assertEquals("<|emotion_3|>!", EmotionTagParser.parse("[hắng giọng]!"))
        assertEquals("<|emotion_2|>…", EmotionTagParser.parse("[thở dài]…"))

        // 2. Tokenizer encoding: Token 10 is immediately followed by '.' (token 90)
        // without an intervening space token (which would introduce synthetic silence)
        val tokens = tokenizer.encode("[thở dài].")
        assertArrayEquals(
            "Trailing period must attach immediately as token 90 following emotion token 10",
            intArrayOf(10, 90),
            tokens
        )

        val tokensWithSpaceInput = tokenizer.encode("[thở dài] .")
        assertArrayEquals(
            "Input with space before period must also resolve to attached [10, 90]",
            intArrayOf(10, 90),
            tokensWithSpaceInput
        )

        // 3. Phonemization bypass maintains attached trailing punctuation
        val bypassResult = EmotionTagParser.phonemizeWithBypass("[thở dài].")
        assertEquals("<|emotion_2|>.", bypassResult)

        // Trailing punctuation between sentences remains attached to token with space to next sentence
        val mockPhonemizer: (String) -> String = { text -> "P($text)" }
        val sentenceResult = EmotionTagParser.phonemizeWithBypass("Hắn im lặng [thở dài]. Thật mệt mỏi.", mockPhonemizer)
        assertEquals("P(Hắn im lặng) <|emotion_2|>. P(Thật mệt mỏi.)", sentenceResult)

        // 4. SmartTextSegmenter.puncNorm preserves punctuation attachment
        val normed = SmartTextSegmenter.puncNorm("<|emotion_2|> .")
        assertEquals("<|emotion_2|>.", normed)
    }
}
