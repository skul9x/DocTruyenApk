package com.skul9x.doctruyen.tts.vieneu

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.Normalizer
import java.util.regex.Pattern

/**
 * Byte-level BPE tokenizer matching HuggingFace Tokenizers for VieNeu-TTS.
 */
class VieNeuTokenizer(
    private val vocab: Map<String, Int>,
    private val bpeRanks: Map<Pair<String, String>, Int>,
    private val unkTokenId: Int = 43
) {
    val vocabSize: Int get() = vocab.size

    fun encode(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf()

        // 1. Parse recognized emotion cues ([cười], [thở dài], [hắng giọng]) and normalize punctuation attachment
        val parsed = EmotionTagParser.parse(text)

        // 2. If no special tokens present, encode directly
        if (!parsed.contains("<|")) {
            val tokenIds = ArrayList<Int>()
            encodeChunk(parsed, tokenIds)
            return tokenIds.toIntArray()
        }

        // 3. Find and isolate special tokens (e.g. <|emotion_1|>, <|emotion_2|>, <|emotion_3|>)
        val tokenIds = ArrayList<Int>()
        val matches = SPECIAL_TOKEN_REGEX.findAll(parsed).toList()
        if (matches.isEmpty()) {
            encodeChunk(parsed, tokenIds)
            return tokenIds.toIntArray()
        }

        var lastEnd = 0
        for (match in matches) {
            val preChunk = parsed.substring(lastEnd, match.range.first)
            if (preChunk.isNotEmpty()) {
                encodeChunk(preChunk, tokenIds)
            }

            val specialToken = match.value
            val specialId = vocab[specialToken]
                ?: EmotionTagParser.getEmotionTokenId(specialToken)
                ?: unkTokenId
            tokenIds.add(specialId)

            lastEnd = match.range.last + 1
        }

        if (lastEnd < parsed.length) {
            val postChunk = parsed.substring(lastEnd)
            if (postChunk.isNotEmpty()) {
                encodeChunk(postChunk, tokenIds)
            }
        }

        return tokenIds.toIntArray()
    }

    private fun encodeChunk(text: String, tokenIds: ArrayList<Int>) {
        if (text.isEmpty()) return
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFC)
        val matcher = PRE_TOKENIZER_PATTERN.matcher(normalized)

        while (matcher.find()) {
            val chunk = matcher.group()
            if (chunk.isEmpty()) continue

            val utf8Bytes = chunk.toByteArray(Charsets.UTF_8)
            val charBuf = StringBuilder(utf8Bytes.size)
            for (b in utf8Bytes) {
                val byteVal = b.toInt() and 0xFF
                charBuf.append(BYTE_ENCODER[byteVal] ?: '?')
            }

            val bpeResult = applyBpe(charBuf.toString())
            for (token in bpeResult) {
                val id = vocab[token] ?: unkTokenId
                tokenIds.add(id)
            }
        }
    }

    private fun applyBpe(token: String): List<String> {
        if (token.length <= 1) return listOf(token)

        var word: MutableList<String> = token.map { it.toString() }.toMutableList()
        var pairs = getPairs(word)
        if (pairs.isEmpty()) return listOf(token)

        while (true) {
            var minRank = Int.MAX_VALUE
            var bestPair: Pair<String, String>? = null

            for (pair in pairs) {
                val rank = bpeRanks[pair] ?: Int.MAX_VALUE
                if (rank < minRank) {
                    minRank = rank
                    bestPair = pair
                }
            }

            if (bestPair == null || minRank == Int.MAX_VALUE) {
                break
            }

            val first = bestPair.first
            val second = bestPair.second
            val newWord = ArrayList<String>()
            var i = 0
            while (i < word.size) {
                if (i < word.size - 1 && word[i] == first && word[i + 1] == second) {
                    newWord.add(first + second)
                    i += 2
                } else {
                    newWord.add(word[i])
                    i++
                }
            }

            word = newWord
            if (word.size <= 1) {
                break
            }
            pairs = getPairs(word)
        }

        return word
    }

    private fun getPairs(word: List<String>): Set<Pair<String, String>> {
        val pairs = LinkedHashSet<Pair<String, String>>()
        for (i in 0 until word.size - 1) {
            pairs.add(Pair(word[i], word[i + 1]))
        }
        return pairs
    }

    companion object {
        val EmotionTagParser = com.skul9x.doctruyen.tts.vieneu.EmotionTagParser
        private val SPECIAL_TOKEN_REGEX = Regex("<\\|[^>\\|]+\\|>")

        private val PRE_TOKENIZER_PATTERN = Pattern.compile(
            """(?i:'s|'t|'re|'ve|'m|'ll|'d)|[^\r\n\p{L}\p{N}]?\p{L}+|\p{N}| ?[^\s\p{L}\p{N}]+[\r\n]*|\s*[\r\n]+|\s+(?!\S)|\s+"""
        )

        private val BYTE_ENCODER: Array<Char> = run {
            val bs = ArrayList<Int>()
            for (b in '!'.code..'~'.code) bs.add(b)
            for (b in '¡'.code..'¬'.code) bs.add(b)
            for (b in '®'.code..'ÿ'.code) bs.add(b)
            val cs = ArrayList<Int>(bs)
            var n = 0
            for (b in 0..255) {
                if (!bs.contains(b)) {
                    bs.add(b)
                    cs.add(256 + n)
                    n++
                }
            }
            val arr = Array(256) { ' ' }
            for (i in 0 until bs.size) {
                arr[bs[i]] = cs[i].toChar()
            }
            arr
        }

        fun fromJson(jsonStr: String): VieNeuTokenizer {
            val root = JSONObject(jsonStr)
            val model = root.getJSONObject("model")
            val vocabObj = model.getJSONObject("vocab")
            val vocab = HashMap<String, Int>(vocabObj.length())
            val keys = vocabObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                vocab[k] = vocabObj.getInt(k)
            }

            val mergesArr = model.getJSONArray("merges")
            val bpeRanks = HashMap<Pair<String, String>, Int>(mergesArr.length())
            for (i in 0 until mergesArr.length()) {
                val item = mergesArr.get(i)
                if (item is String) {
                    val parts = item.split(" ")
                    if (parts.size >= 2) {
                        bpeRanks[Pair(parts[0], parts[1])] = i
                    }
                } else if (item is org.json.JSONArray) {
                    if (item.length() >= 2) {
                        bpeRanks[Pair(item.getString(0), item.getString(1))] = i
                    }
                }
            }

            val unkId = vocab["<|unk|>"] ?: 43
            return VieNeuTokenizer(vocab, bpeRanks, unkId)
        }

        fun fromInputStream(inputStream: InputStream): VieNeuTokenizer {
            val jsonStr = inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return fromJson(jsonStr)
        }

        fun fromFile(file: File): VieNeuTokenizer {
            return fromJson(file.readText(Charsets.UTF_8))
        }
    }
}
