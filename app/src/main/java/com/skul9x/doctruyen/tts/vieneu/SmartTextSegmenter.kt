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

    const val DEFAULT_MAX_CHARS: Int = 180

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

        // 2. Normalize line breaks
        val normalized = cleanText.replace("\r\n", "\n").replace('\r', '\n')

        // 3. Protect abbreviations and numbers with dots/colons from false sentence breaks
        val (protectedText, placeholders) = protectSpecialPatterns(normalized)

        // 4. Split into paragraphs (delimited by one or more blank lines / newlines)
        val rawParagraphs = protectedText.split(NEWLINES_REGEX)
        val chunks = mutableListOf<TextChunk>()
        var chunkIndex = 0

        for (paragraph in rawParagraphs) {
            val trimmedPara = paragraph.trim()
            if (trimmedPara.isEmpty()) continue

            // Split paragraph into sentences by [.!?…] followed by space or end of string
            val rawSentences = trimmedPara.split(SENTENCE_SPLIT_REGEX)

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
                val subParts = splitSentenceIntoSubClauses(trimmedSent, maxCharsPerChunk)

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
                    chunks.add(
                        TextChunk(
                            index = chunkIndex++,
                            text = restoredText,
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

    private val BR_REGEX = Regex("(?i)<br\\s*/?>")
    private val P_CLOSE_REGEX = Regex("(?i)</p>")
    private val DIV_CLOSE_REGEX = Regex("(?i)</div>")
    private val LI_CLOSE_REGEX = Regex("(?i)</li>")
    private val TR_CLOSE_REGEX = Regex("(?i)</tr>")
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val NEWLINES_REGEX = Regex("\n+")
    private val SENTENCE_SPLIT_REGEX = Regex("(?<=[.!?…])\\s+")
    private val CLAUSE_SPLIT_REGEX = Regex("(?<=[,;:—–-])\\s+")
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

    private fun restoreSpecialPatterns(text: String, placeholders: Map<String, String>): String {
        if (!text.contains(PLACEHOLDER_PREFIX)) return text
        var restored = text
        for ((key, original) in placeholders) {
            if (restored.contains(key)) {
                restored = restored.replace(key, original)
            }
        }
        return restored
    }

    /**
     * Split a long sentence into sub-clauses respecting punctuation and word boundaries.
     */
    private fun splitSentenceIntoSubClauses(sentence: String, maxChars: Int): List<String> {
        if (sentence.length <= maxChars) {
            return listOf(sentence)
        }

        val subChunks = mutableListOf<String>()
        // Split clauses at comma, semicolon, colon, dashes
        val clauses = sentence.split(CLAUSE_SPLIT_REGEX)
        var current = StringBuilder()

        for (clause in clauses) {
            val trimmedClause = clause.trim()
            if (trimmedClause.isEmpty()) continue

            if (current.isEmpty()) {
                current.append(trimmedClause)
            } else if (current.length + 1 + trimmedClause.length <= maxChars) {
                current.append(" ").append(trimmedClause)
            } else {
                if (current.isNotEmpty()) {
                    subChunks.add(current.toString())
                    current = StringBuilder()
                }
                if (trimmedClause.length <= maxChars) {
                    current.append(trimmedClause)
                } else {
                    // Split clause by words
                    val words = trimmedClause.split(WHITESPACE_REGEX)
                    var wordBuf = StringBuilder()
                    for (word in words) {
                        if (wordBuf.isEmpty()) {
                            wordBuf.append(word)
                        } else if (wordBuf.length + 1 + word.length <= maxChars) {
                            wordBuf.append(" ").append(word)
                        } else {
                            subChunks.add(wordBuf.toString())
                            wordBuf = StringBuilder(word)
                        }
                    }
                    if (wordBuf.isNotEmpty()) {
                        current = wordBuf
                    }
                }
            }
        }
        if (current.isNotEmpty()) {
            subChunks.add(current.toString())
        }
        return subChunks
    }
}
