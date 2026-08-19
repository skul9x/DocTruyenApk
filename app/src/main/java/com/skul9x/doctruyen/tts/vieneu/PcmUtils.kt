package com.skul9x.doctruyen.tts.vieneu

/**
 * Utility functions for PCM audio data conversion and text processing.
 */
object PcmUtils {

    /**
     * Convert float32 [-1.0, 1.0] PCM samples to int16 [-32768, 32767] PCM samples.
     */
    fun floatToShort(floatPcm: FloatArray): ShortArray {
        return ShortArray(floatPcm.size) { i ->
            (floatPcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
    }

    /**
     * Convert int16 [-32768, 32767] PCM samples to float32 [-1.0, 1.0] PCM samples.
     */
    fun shortToFloat(shortPcm: ShortArray): FloatArray {
        return FloatArray(shortPcm.size) { i ->
            shortPcm[i].toFloat() / 32767f
        }
    }

    /**
     * Split text into sentence chunks at ". ", "! ", "? ", "\n", "…" boundaries.
     * Long sentences exceeding [maxCharsPerChunk] are further split at commas,
     * semicolons, colons, dashes or word boundaries.
     */
    fun splitIntoSentences(text: String, maxCharsPerChunk: Int = 200): List<String> {
        if (text.isBlank()) return emptyList()

        val rawChunks = mutableListOf<String>()
        // Normalize line breaks
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')

        // Split by lines first
        val lines = normalized.split('\n')
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            // Split line by sentence terminators (. ! ? …) followed by whitespace or end of line
            val regex = Regex("(?<=[.!?…])\\s+")
            val sentenceParts = trimmedLine.split(regex)
            for (part in sentenceParts) {
                val trimmedPart = part.trim()
                if (trimmedPart.isNotEmpty()) {
                    rawChunks.add(trimmedPart)
                }
            }
        }

        // Sub-split chunks exceeding maxCharsPerChunk
        val result = mutableListOf<String>()
        for (chunk in rawChunks) {
            if (chunk.length <= maxCharsPerChunk) {
                result.add(chunk)
            } else {
                result.addAll(splitLongChunk(chunk, maxCharsPerChunk))
            }
        }
        return result
    }

    private fun splitLongChunk(chunk: String, maxChars: Int): List<String> {
        val subChunks = mutableListOf<String>()
        val clauseRegex = Regex("(?<=[,;:—–-])\\s+")
        val clauses = chunk.split(clauseRegex)
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
                    // Split by words
                    val words = trimmedClause.split(Regex("\\s+"))
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
