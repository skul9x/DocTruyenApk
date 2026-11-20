package com.skul9x.doctruyen.tts.vieneu

/**
 * Categorization of speech pauses based on Vietnamese prosody and structural boundaries.
 */
enum class PauseType(val defaultDurationMs: Int) {
    TITLE_BREAK(1000),       // Pause between story title and main content
    PARAGRAPH_BREAK(650),    // Pause after paragraph transitions (\n\n, </p>, </div>, <br>)
    SENTENCE_BREAK(400),     // Pause after terminal punctuation (. ! ? …)
    CLAUSE_BREAK(180),       // Pause after intra-sentence pauses (, ; : — –)
    DIALOGUE_LEAD(300)       // Pause after introductory dialogue dash (- )
}

/**
 * Dynamic pause configuration supporting customizable silence durations.
 */
data class PauseConfig(
    val sentencePauseMs: Int = PauseType.SENTENCE_BREAK.defaultDurationMs,
    val paragraphPauseMs: Int = PauseType.PARAGRAPH_BREAK.defaultDurationMs,
    val clausePauseMs: Int = PauseType.CLAUSE_BREAK.defaultDurationMs,
    val titlePauseMs: Int = PauseType.TITLE_BREAK.defaultDurationMs,
    val dialogueLeadPauseMs: Int = PauseType.DIALOGUE_LEAD.defaultDurationMs
) {
    fun getDurationFor(type: PauseType): Int = when (type) {
        PauseType.TITLE_BREAK -> titlePauseMs
        PauseType.PARAGRAPH_BREAK -> paragraphPauseMs
        PauseType.SENTENCE_BREAK -> sentencePauseMs
        PauseType.CLAUSE_BREAK -> clausePauseMs
        PauseType.DIALOGUE_LEAD -> dialogueLeadPauseMs
    }

    companion object {
        val UNSPECIFIED = PauseConfig(
            sentencePauseMs = -1,
            paragraphPauseMs = -1,
            clausePauseMs = -1,
            titlePauseMs = -1,
            dialogueLeadPauseMs = -1
        )
    }
}

/**
 * Emotion tag extractor and novel footnote bracket protector for VieNeu-TTS.
 *
 * Maps recognized emotion cues:
 * - [cười], [cuoi], [chuckle] -> <|emotion_1|> (token ID 9)
 * - [thở dài], [tho dai], [sigh] -> <|emotion_2|> (token ID 10)
 * - [hắng giọng], [hang giong], [clear throat] -> <|emotion_3|> (token ID 11)
 *
 * Unrecognized brackets ([1], [CV: ...], [Chương 2]) pass through as regular text.
 * Trailing punctuation is kept attached to avoid synthetic silence gaps.
 */
object EmotionTagParser {
    val EMOTION_MAP: Map<String, String> = mapOf(
        "cười" to "<|emotion_1|>",
        "cuoi" to "<|emotion_1|>",
        "chuckle" to "<|emotion_1|>",
        "thở dài" to "<|emotion_2|>",
        "tho dai" to "<|emotion_2|>",
        "sigh" to "<|emotion_2|>",
        "hắng giọng" to "<|emotion_3|>",
        "hang giong" to "<|emotion_3|>",
        "clear throat" to "<|emotion_3|>"
    )

    val EMOTION_TOKEN_IDS: Map<String, Int> = mapOf(
        "<|emotion_1|>" to 9,
        "<|emotion_2|>" to 10,
        "<|emotion_3|>" to 11
    )

    val TAG_TO_TOKEN_ID: Map<String, Int> = mapOf(
        "cười" to 9,
        "cuoi" to 9,
        "chuckle" to 9,
        "thở dài" to 10,
        "tho dai" to 10,
        "sigh" to 10,
        "hắng giọng" to 11,
        "hang giong" to 11,
        "clear throat" to 11
    )

    private val BRACKET_REGEX = Regex("\\[([^\\[\\]]+)\\]")
    val EMOTION_TOKEN_REGEX = Regex("<\\|emotion_\\d+\\|>")
    private val PUNCT_ATTACH_REGEX = Regex("(<\\|emotion_\\d+\\|>)\\s+([.,!?;:…\"'”’)\\]])")
    val PUNCT_CHARS: Set<Char> = setOf('.', ',', '!', '?', ';', ':', '…', '"', '\'', '”', '’', ')', ']')

    fun isEmotionTag(cue: String): Boolean {
        val clean = cue.trim().removeSurrounding("[", "]").trim().lowercase()
        return EMOTION_MAP.containsKey(clean)
    }

    fun getEmotionToken(cue: String): String? {
        val clean = cue.trim().removeSurrounding("[", "]").trim().lowercase()
        return EMOTION_MAP[clean]
    }

    fun getEmotionTokenId(tagOrToken: String): Int? {
        val trimmed = tagOrToken.trim()
        EMOTION_TOKEN_IDS[trimmed]?.let { return it }
        val cue = trimmed.removeSurrounding("[", "]").trim().lowercase()
        return TAG_TO_TOKEN_ID[cue]
    }

    /**
     * Parses and replaces recognized emotion tags with special tokens.
     * Preserves arbitrary novel brackets ([1], [CV: ...], [Chương 2]) as regular text.
     * Keeps trailing punctuation attached without synthetic silence gaps.
     */
    fun parse(text: String): String {
        if (text.isEmpty() || !text.contains('[')) {
            return if (text.contains("<|emotion_")) normalizePunctuationAttachment(text) else text
        }

        val replaced = BRACKET_REGEX.replace(text) { matchResult ->
            val inner = matchResult.groupValues[1].trim().lowercase()
            val emotionToken = EMOTION_MAP[inner]
            emotionToken ?: matchResult.value
        }

        return normalizePunctuationAttachment(replaced)
    }

    /**
     * Normalizes punctuation attachment to emotion tokens.
     */
    fun normalizePunctuationAttachment(text: String): String {
        if (!text.contains("<|emotion_")) return text
        return PUNCT_ATTACH_REGEX.replace(text) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}"
        }
    }

    /**
     * Isolates emotion tokens prior to G2P normalization.
     * Splits text around emotion tokens, phonemizes only the natural text segments,
     * and re-splices emotion tokens intact.
     */
    fun phonemizeWithBypass(
        text: String,
        phonemizer: (String) -> String = { SeaG2P.phonemize(it) }
    ): String {
        val parsed = parse(text)
        if (!parsed.contains("<|emotion_")) {
            return phonemizer(parsed)
        }

        val matches = EMOTION_TOKEN_REGEX.findAll(parsed).toList()
        if (matches.isEmpty()) {
            return phonemizer(parsed)
        }

        val sb = StringBuilder()
        var lastEnd = 0

        for (match in matches) {
            val preText = parsed.substring(lastEnd, match.range.first)
            if (lastEnd > 0) {
                // If previous token had trailing punctuation, attach it directly
                var pIdx = 0
                while (pIdx < preText.length && preText[pIdx] in PUNCT_CHARS) {
                    pIdx++
                }
                if (pIdx > 0) {
                    val punct = preText.substring(0, pIdx)
                    sb.append(punct)
                    val rest = preText.substring(pIdx).trim()
                    if (rest.isNotEmpty()) {
                        val ph = phonemizer(rest)
                        if (ph.isNotEmpty()) {
                            if (sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(" ")
                            sb.append(ph)
                        }
                    }
                } else {
                    val trimmed = preText.trim()
                    if (trimmed.isNotEmpty()) {
                        val ph = phonemizer(trimmed)
                        if (ph.isNotEmpty()) {
                            if (sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(" ")
                            sb.append(ph)
                        }
                    }
                }
            } else {
                val trimmed = preText.trim()
                if (trimmed.isNotEmpty()) {
                    val ph = phonemizer(trimmed)
                    if (ph.isNotEmpty()) {
                        sb.append(ph)
                    }
                }
            }

            if (sb.isNotEmpty() && !sb.endsWith(" ")) {
                sb.append(" ")
            }
            sb.append(match.value)
            lastEnd = match.range.last + 1
        }

        if (lastEnd < parsed.length) {
            val postText = parsed.substring(lastEnd)
            var pIdx = 0
            while (pIdx < postText.length && postText[pIdx] in PUNCT_CHARS) {
                pIdx++
            }
            if (pIdx > 0) {
                val punct = postText.substring(0, pIdx)
                sb.append(punct)
                val rest = postText.substring(pIdx).trim()
                if (rest.isNotEmpty()) {
                    val ph = phonemizer(rest)
                    if (ph.isNotEmpty()) {
                        if (sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(" ")
                        sb.append(ph)
                    }
                }
            } else {
                val trimmed = postText.trim()
                if (trimmed.isNotEmpty()) {
                    val ph = phonemizer(trimmed)
                    if (ph.isNotEmpty()) {
                        if (sb.isNotEmpty() && !sb.endsWith(" ")) sb.append(" ")
                        sb.append(ph)
                    }
                }
            }
        }

        return sb.toString()
    }
}

/**
 * Structured text chunk with attached pause metadata for natural audio generation.
 */
data class TextChunk(
    val index: Int,
    val text: String,
    val pauseType: PauseType,
    val pauseDurationMs: Int = pauseType.defaultDurationMs,
    val isParagraphEnd: Boolean = false
)

/**
 * Linguistic and context-aware Vietnamese text segmenter for VieNeu-TTS.
 *
 * Handles:
 * - HTML markup cleaning while preserving paragraph structure.
 * - Protection of Vietnamese honorifics, titles, abbreviations, and numerals from false sentence breaks.
 * - Dialogue demarcation with specialized lead pauses.
 * - Sub-clause splitting for lengthy lines (<= maxCharsPerChunk) without breaking mid-word.
 */
object SmartTextSegmenter {

    val EmotionTagParser = com.skul9x.doctruyen.tts.vieneu.EmotionTagParser

    fun phonemizeWithEmotionBypass(text: String, phonemizer: (String) -> String = { SeaG2P.phonemize(it) }): String {
        return EmotionTagParser.phonemizeWithBypass(text, phonemizer)
    }

    const val DEFAULT_MAX_CHARS: Int = 75

    // Common Vietnamese abbreviations, honorifics, titles, and acronyms
    private val PROTECTED_ABBREVIATIONS = listOf(
        "TP.HCM", "TP. Hồ Chí Minh", "TP. HN", "TP. Hà Nội", "TP. Đà Nẵng", "TP.",
        "BS.", "Bác sĩ", "ThS.", "TS.", "GS.", "PGS.", "PGS.TS.", "GS.TS.",
        "KTS.", "DS.", "CN.", "Th.S", "T.S", "G.S",
        "Mr.", "Mrs.", "Ms.", "Dr.", "Prof.",
        "v.v.", "v.v...", "etc.", "tr.", "đ.", "VNĐ", "VND",
        "Q.", "H.", "TX.", "TT.", "P."
    )

    private const val PLACEHOLDER_PREFIX = "___ABBR_PH_"
    private const val PLACEHOLDER_SUFFIX = "___"

    /**
     * Segment story text (plain text or HTML) into an ordered list of [TextChunk].
     *
     * @param text Raw story text or HTML content.
     * @param maxCharsPerChunk Max character count per speech chunk (default 180).
     * @param pauseConfig Custom pause configuration durations.
     * @return List of [TextChunk] ready for TTS synthesis.
     */
    fun segment(
        text: String,
        maxCharsPerChunk: Int = DEFAULT_MAX_CHARS,
        pauseConfig: PauseConfig = PauseConfig()
    ): List<TextChunk> {
        if (text.isBlank()) return emptyList()

        // 1. Strip HTML tags while converting structural breaks into newlines
        val cleanText = htmlToStructuredText(text)
        if (cleanText.isBlank()) return emptyList()

        // 2. Parse recognized emotion cues into special tokens
        val parsedEmotion = EmotionTagParser.parse(cleanText)

        // 3. Normalize line breaks
        val normalized = parsedEmotion.replace("\r\n", "\n").replace('\r', '\n')

        // 3. Protect abbreviations and numbers with dots/colons from false sentence breaks
        val (protectedText, placeholders) = protectSpecialPatterns(normalized)

        // 4. Split into paragraphs (delimited by one or more blank lines / newlines)
        val rawParagraphs = protectedText.split(NEWLINES_REGEX)
        val chunks = mutableListOf<TextChunk>()
        var chunkIndex = 0

        for (paragraph in rawParagraphs) {
            val trimmedPara = paragraph.trim()
            if (trimmedPara.isEmpty()) continue

            // Split paragraph into sentences with quote/bracket depth awareness
            val rawSentences = splitIntoSentences(trimmedPara)

            val paraChunks = mutableListOf<Pair<String, PauseType>>()

            for ((sIdx, rawSentence) in rawSentences.withIndex()) {
                val trimmedSent = rawSentence.trim()
                if (trimmedSent.isEmpty()) continue

                val isLastSentenceInPara = (sIdx == rawSentences.size - 1)

                // Check if this sentence is a dialogue line
                val isDialogue = trimmedSent.startsWith("- ") ||
                        trimmedSent.startsWith("– ") ||
                        trimmedSent.startsWith("— ")

                // Sub-split sentence if length exceeds maxCharsPerChunk
                val subParts = splitSentenceIntoSubClauses(trimmedSent, maxCharsPerChunk, placeholders)

                for ((pIdx, part) in subParts.withIndex()) {
                    val isLastSubPart = (pIdx == subParts.size - 1)
                    val pauseType = when {
                        isLastSubPart && isLastSentenceInPara -> PauseType.PARAGRAPH_BREAK
                        isLastSubPart -> PauseType.SENTENCE_BREAK
                        isDialogue && pIdx == 0 && (part.startsWith("- ") || part.startsWith("– ") || part.startsWith("— ")) -> PauseType.DIALOGUE_LEAD
                        part.endsWith(",") || part.endsWith(";") || part.endsWith(":") ||
                                part.endsWith("—") || part.endsWith("–") || part.endsWith("-") -> PauseType.CLAUSE_BREAK
                        else -> PauseType.CLAUSE_BREAK
                    }
                    paraChunks.add(Pair(part, pauseType))
                }
            }

            // Convert to TextChunk and restore abbreviations
            for ((pIdx, item) in paraChunks.withIndex()) {
                val isParaEnd = (pIdx == paraChunks.size - 1)
                val restoredText = restoreSpecialPatterns(item.first, placeholders).trim()
                if (restoredText.isNotEmpty()) {
                    val normalizedText = puncNorm(restoredText)
                    chunks.add(
                        TextChunk(
                            index = chunkIndex++,
                            text = normalizedText,
                            pauseType = item.second,
                            pauseDurationMs = pauseConfig.getDurationFor(item.second),
                            isParagraphEnd = isParaEnd
                        )
                    )
                }
            }
        }

        return chunks
    }

    /**
     * Segment title and content with explicit [PauseType.TITLE_BREAK] after the title.
     */
    fun segmentStory(
        title: String,
        content: String,
        maxCharsPerChunk: Int = DEFAULT_MAX_CHARS,
        pauseConfig: PauseConfig = PauseConfig()
    ): List<TextChunk> {
        val result = mutableListOf<TextChunk>()
        var chunkIndex = 0

        val titleChunks = segment(title, maxCharsPerChunk, pauseConfig)
        if (titleChunks.isNotEmpty()) {
            for ((i, chunk) in titleChunks.withIndex()) {
                val isLastTitleChunk = (i == titleChunks.size - 1)
                val pauseType = if (isLastTitleChunk) PauseType.TITLE_BREAK else chunk.pauseType
                val duration = if (isLastTitleChunk) pauseConfig.titlePauseMs else pauseConfig.getDurationFor(pauseType)
                result.add(
                    chunk.copy(
                        index = chunkIndex++,
                        pauseType = pauseType,
                        pauseDurationMs = duration,
                        isParagraphEnd = true
                    )
                )
            }
        }

        val contentChunks = segment(content, maxCharsPerChunk, pauseConfig)
        for (chunk in contentChunks) {
            result.add(chunk.copy(index = chunkIndex++))
        }

        return result
    }

    private val OPEN_TO_CLOSE: Map<Char, Char> = mapOf(
        '(' to ')',
        '[' to ']',
        '{' to '}',
        '“' to '”',
        '‘' to '’',
        '«' to '»',
        '‹' to '›',
        '「' to '」',
        '『' to '』'
    )
    private val OPENERS: Set<Char> = OPEN_TO_CLOSE.keys
    private val CLOSERS: Set<Char> = OPEN_TO_CLOSE.values.toSet()
    private const val SYMMETRIC_QUOTE: Char = '"'
    private val SENT_END_CHARS: Set<Char> = setOf('.', '!', '?', '…')
    private val TRAILING_CLOSE: Set<Char> = CLOSERS + setOf('"', '\'', '’', '”')

    /**
     * Scan [text] character by character to split sentences at terminal punctuation
     * (. ! ? …) that does NOT occur within quotes or brackets.
     *
     * @return Pair of (sentences, isBalanced). When isBalanced is false, brackets/quotes were unbalanced.
     */
    fun scanSentences(text: String, quoteAware: Boolean = true): Pair<List<String>, Boolean> {
        if (text.isBlank()) return Pair(emptyList(), true)
        val sentences = mutableListOf<String>()
        val n = text.length
        var start = 0
        var i = 0
        var depth = 0
        var inQuote = false

        while (i < n) {
            val ch = text[i]
            if (quoteAware && ch == SYMMETRIC_QUOTE) {
                inQuote = !inQuote
            } else if (quoteAware && ch in OPENERS) {
                depth++
            } else if (quoteAware && ch in CLOSERS) {
                if (depth > 0) {
                    depth--
                }
            } else if (ch in SENT_END_CHARS && depth == 0 && !inQuote) {
                var j = i + 1
                while (j < n && text[j] in SENT_END_CHARS) {
                    j++
                }
                while (j < n && text[j] in TRAILING_CLOSE) {
                    j++
                }
                // Sentence boundary only if followed by whitespace or end of string (protects decimals like 3.5)
                if (j >= n || text[j].isWhitespace()) {
                    val sent = text.substring(start, j).trim()
                    if (sent.isNotEmpty()) {
                        sentences.add(sent)
                    }
                    start = j
                    i = j
                    continue
                }
                i = j
                continue
            }
            i++
        }

        if (start < n) {
            val sent = text.substring(start).trim()
            if (sent.isNotEmpty()) {
                sentences.add(sent)
            }
        }

        val isBalanced = (depth == 0 && !inQuote)
        return Pair(sentences, isBalanced)
    }

    /**
     * Split text into sentences with quote awareness and fallback on unbalanced quotes.
     */
    fun splitIntoSentences(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val (sentences, balanced) = scanSentences(text, quoteAware = true)
        if (!balanced) {
            return scanSentences(text, quoteAware = false).first
        }
        return sentences
    }

    /**
     * Normalize chunk terminal punctuation according to VieNeu rules:
     * - Short chunks (< 5 words) are guaranteed to end with a terminal period '.'.
     * - Chunks missing terminal punctuation (., !, ?, …, ,) automatically append '.'.
     * - Attached closing quotes or brackets (e.g. bao giờ?", thế à!)) stay bound with the preceding sentence.
     */
    fun puncNorm(chunk: String): String {
        val trimmed = chunk.trim()
        if (trimmed.isEmpty()) return ""

        val words = trimmed.split(WHITESPACE_REGEX).filter { it.isNotEmpty() }
        val lastChar = trimmed.last()

        val normalized = if (words.size < 5) {
            if (lastChar in SENT_END_CHARS || lastChar == ',') {
                var i = trimmed.length - 1
                while (i >= 0 && (trimmed[i] in SENT_END_CHARS || trimmed[i] == ',')) {
                    i--
                }
                trimmed.substring(0, i + 1) + "."
            } else if (lastChar in TRAILING_CLOSE) {
                trimmed + "."
            } else {
                trimmed + "."
            }
        } else {
            if (lastChar in SENT_END_CHARS || lastChar == ',') {
                trimmed
            } else {
                trimmed + "."
            }
        }
        return EmotionTagParser.normalizePunctuationAttachment(normalized)
    }

    private val BR_REGEX = Regex("(?i)<br\\s*/?>")
    private val P_CLOSE_REGEX = Regex("(?i)</p>")
    private val DIV_CLOSE_REGEX = Regex("(?i)</div>")
    private val LI_CLOSE_REGEX = Regex("(?i)</li>")
    private val TR_CLOSE_REGEX = Regex("(?i)</tr>")
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val NEWLINES_REGEX = Regex("\n+")
    private val CLAUSE_SPLIT_REGEX = Regex("(?<=[,;:—–-])\\s+|(?<=[—–])(?=[^\\s])")
    private val WHITESPACE_REGEX = Regex("\\s+")

    private val NUM_DOT_REGEX = Regex("(\\b\\d+)\\.(\\d+\\b)")
    private val TIME_REGEX = Regex("(\\b\\d{1,2}):(\\d{2}\\b)")

    // Precompiled regex for abbreviations: \b(TP\.HCM|BS\.|ThS\.|...)\b or exact matching
    private val ABBR_REGEX = Regex(
        "(?i)\\b(" + PROTECTED_ABBREVIATIONS.sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) } + ")"
    )

    /**
     * Convert HTML content to plain text with structural newlines.
     */
    private fun htmlToStructuredText(html: String): String {
        if (!html.contains('<') && !html.contains('&')) {
            return html
        }

        return html
            .replace(BR_REGEX, "\n")
            .replace(P_CLOSE_REGEX, "\n\n")
            .replace(DIV_CLOSE_REGEX, "\n\n")
            .replace(LI_CLOSE_REGEX, "\n")
            .replace(TR_CLOSE_REGEX, "\n")
            .replace(HTML_TAG_REGEX, " ") // Strip remaining HTML tags
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    /**
     * Replace abbreviations and dot-separated numerals with placeholders.
     */
    private fun protectSpecialPatterns(text: String): Pair<String, Map<String, String>> {
        val placeholders = HashMap<String, String>()
        var count = 0

        // 1. Single pass regex match for abbreviations
        var protected = ABBR_REGEX.replace(text) { matchResult ->
            val key = "${PLACEHOLDER_PREFIX}A_${count++}$PLACEHOLDER_SUFFIX"
            placeholders[key] = matchResult.value
            key
        }

        // 2. Protect numbers with decimals/thousands separators (e.g. 1.000, 3.14, 8.30)
        protected = NUM_DOT_REGEX.replace(protected) { matchResult ->
            val key = "${PLACEHOLDER_PREFIX}N_${count++}$PLACEHOLDER_SUFFIX"
            placeholders[key] = matchResult.value
            key
        }

        // 3. Protect time formats (e.g. 08:30, 12:45)
        protected = TIME_REGEX.replace(protected) { matchResult ->
            val key = "${PLACEHOLDER_PREFIX}T_${count++}$PLACEHOLDER_SUFFIX"
            placeholders[key] = matchResult.value
            key
        }

        return Pair(protected, placeholders)
    }

    private val PLACEHOLDER_REGEX = Regex("___ABBR_PH_[ANT]_\\d+___")

    private fun restoreSpecialPatterns(text: String, placeholders: Map<String, String>): String {
        if (!text.contains(PLACEHOLDER_PREFIX)) return text
        return PLACEHOLDER_REGEX.replace(text) { match ->
            placeholders[match.value] ?: match.value
        }
    }

    private fun getRestoredLength(text: String, placeholders: Map<String, String>): Int {
        if (!text.contains(PLACEHOLDER_PREFIX)) return text.length
        var len = text.length
        for (match in PLACEHOLDER_REGEX.findAll(text)) {
            val orig = placeholders[match.value]
            if (orig != null) {
                len = len - match.value.length + orig.length
            }
        }
        return len
    }

    /**
     * Split a long sentence into sub-clauses respecting punctuation and word boundaries.
     */
    private fun splitSentenceIntoSubClauses(
        sentence: String,
        maxChars: Int,
        placeholders: Map<String, String> = emptyMap()
    ): List<String> {
        if (getRestoredLength(sentence, placeholders) <= maxChars) {
            return listOf(sentence)
        }

        val subChunks = mutableListOf<String>()
        val clauses = sentence.split(CLAUSE_SPLIT_REGEX)
        var current = StringBuilder()

        for (clause in clauses) {
            val trimmedClause = clause.trim()
            if (trimmedClause.isEmpty()) continue

            val clauseLen = getRestoredLength(trimmedClause, placeholders)

            if (clauseLen > maxChars) {
                // Clause itself exceeds maxChars -> flush current if any, then split clause by words
                if (current.isNotEmpty()) {
                    subChunks.add(current.toString())
                    current = StringBuilder()
                }

                val words = trimmedClause.split(WHITESPACE_REGEX)
                var wordBuf = StringBuilder()
                for (word in words) {
                    val wordLen = getRestoredLength(word, placeholders)
                    if (wordLen > maxChars) {
                        if (wordBuf.isNotEmpty()) {
                            subChunks.add(wordBuf.toString())
                            wordBuf = StringBuilder()
                        }
                        val parts = word.chunked(maxChars)
                        for (i in 0 until parts.size - 1) {
                            subChunks.add(parts[i])
                        }
                        wordBuf.append(parts.last())
                    } else if (wordBuf.isEmpty()) {
                        wordBuf.append(word)
                    } else if (getRestoredLength(wordBuf.toString(), placeholders) + 1 + wordLen <= maxChars) {
                        wordBuf.append(" ").append(word)
                    } else {
                        subChunks.add(wordBuf.toString())
                        wordBuf = StringBuilder(word)
                    }
                }
                if (wordBuf.isNotEmpty()) {
                    current = wordBuf
                }
            } else {
                // Clause fits within maxChars
                if (current.isEmpty()) {
                    current.append(trimmedClause)
                } else if (getRestoredLength(current.toString(), placeholders) + 1 + clauseLen <= maxChars) {
                    current.append(" ").append(trimmedClause)
                } else {
                    subChunks.add(current.toString())
                    current = StringBuilder(trimmedClause)
                }
            }
        }

        if (current.isNotEmpty()) {
            subChunks.add(current.toString())
        }

        return subChunks
    }
}
