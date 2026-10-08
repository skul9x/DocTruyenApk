package com.skul9x.doctruyen.tts.vieneu

import kotlin.math.abs

/**
 * High-performance, zero-allocation sliding-window repetition history buffer.
 *
 * Maintains a circular FIFO buffer of capacity [window] per codebook channel (e.g., 16 codebooks).
 * Tracks token frequencies so that tokens older than [window] frames (~2.5s of audio)
 * are evicted and no longer penalized, eliminating voice drift, tonal distortion,
 * and unnatural whisper artifacts on long sentences.
 */
class RepetitionHistory(
    val nChannels: Int = 16,
    val window: Int = 64,
    val vocabSize: Int = 1024
) {
    // Per-channel circular FIFO buffers (capacity = window)
    private val buffers: Array<IntArray> = Array(nChannels) { IntArray(window) }
    // Per-channel write heads (0 until window)
    private val heads: IntArray = IntArray(nChannels)
    // Per-channel current element counts (0 until window)
    private val sizes: IntArray = IntArray(nChannels)
    // Per-channel token frequency tracking array for O(1) lookup
    private val frequencies: Array<IntArray> = Array(nChannels) { IntArray(vocabSize) }
    // Optional fallback map for tokens outside [0 until vocabSize]
    private val overflowFrequencies: Array<HashMap<Int, Int>?> = arrayOfNulls(nChannels)
    // Number of distinct tokens currently in each channel
    private val uniqueCounts: IntArray = IntArray(nChannels)

    // Timestamp-based marker array for zero-allocation deduplication during penalty application
    private var markerEpoch = 1
    private var marker = IntArray(vocabSize)

    // Cached Channel wrapper instances for zero-allocation channel access
    private val channelViews: Array<Channel> = Array(nChannels) { Channel(it) }

    /**
     * Appends a newly sampled [token] to the sliding window of [ch].
     * If the window is full, the oldest token is evicted and its frequency decremented.
     */
    fun add(ch: Int, token: Int) {
        if (ch !in 0 until nChannels || window <= 0) return

        val curHead = heads[ch]
        val curSize = sizes[ch]

        if (curSize == window) {
            // Evict oldest token at curHead
            val evictedToken = buffers[ch][curHead]
            decrementFrequency(ch, evictedToken)
        } else {
            sizes[ch] = curSize + 1
        }

        buffers[ch][curHead] = token
        incrementFrequency(ch, token)
        heads[ch] = (curHead + 1) % window
    }

    /**
     * Checks if [token] is currently present within the sliding window of [ch].
     */
    fun contains(ch: Int, token: Int): Boolean {
        if (ch !in 0 until nChannels) return false
        return count(ch, token) > 0
    }

    /**
     * Returns the frequency count of [token] in the sliding window of [ch].
     */
    fun count(ch: Int, token: Int): Int {
        if (ch !in 0 until nChannels) return 0
        return if (token in 0 until vocabSize) {
            frequencies[ch][token]
        } else {
            overflowFrequencies[ch]?.get(token) ?: 0
        }
    }

    /**
     * Returns the number of tokens currently in the sliding window of [ch] (0 to [window]).
     */
    fun size(ch: Int): Int {
        if (ch !in 0 until nChannels) return 0
        return sizes[ch]
    }

    /**
     * Returns the number of distinct (unique) tokens currently in the sliding window of [ch].
     */
    fun uniqueSize(ch: Int): Int {
        if (ch !in 0 until nChannels) return 0
        return uniqueCounts[ch]
    }

    /**
     * Returns true if channel [ch]'s sliding window contains no tokens.
     */
    fun isEmpty(ch: Int): Boolean {
        return size(ch) == 0
    }

    /**
     * Clears all tokens from channel [ch].
     */
    fun clear(ch: Int) {
        if (ch !in 0 until nChannels) return
        buffers[ch].fill(0)
        frequencies[ch].fill(0)
        overflowFrequencies[ch]?.clear()
        heads[ch] = 0
        sizes[ch] = 0
        uniqueCounts[ch] = 0
    }

    /**
     * Clears all tokens across all channels.
     */
    fun clear() {
        for (ch in 0 until nChannels) {
            clear(ch)
        }
    }

    /**
     * Applies the repetition penalty directly to [logits] in-place for channel [ch].
     * Executes in O(window) steps without heap allocation.
     * Each unique token in the window is penalized exactly once.
     */
    fun applyPenalty(ch: Int, logits: FloatArray, penalty: Float) {
        if (ch !in 0 until nChannels || abs(penalty - 1.0f) <= 1e-4f) return
        val sz = sizes[ch]
        if (sz == 0) return

        ensureMarkerCapacity(logits.size)
        nextMarker()

        val buf = buffers[ch]
        val v = logits.size
        for (i in 0 until sz) {
            val token = buf[i]
            if (token in 0 until v) {
                if (isUnseenAndMark(token)) {
                    val sel = logits[token]
                    logits[token] = if (sel < 0f) sel * penalty else sel / penalty
                }
            }
        }
    }

    /**
     * Returns a copy of the tokens currently held in channel [ch]'s circular buffer in FIFO order.
     */
    fun getTokens(ch: Int): IntArray {
        if (ch !in 0 until nChannels) return IntArray(0)
        val sz = sizes[ch]
        val result = IntArray(sz)
        val curHead = heads[ch]
        val buf = buffers[ch]
        val oldestIdx = if (sz == window) curHead else 0
        for (i in 0 until sz) {
            result[i] = buf[(oldestIdx + i) % window]
        }
        return result
    }

    /**
     * Returns a list of tokens currently held in channel [ch]'s circular buffer in FIFO order.
     */
    fun toList(ch: Int): List<Int> {
        return getTokens(ch).toList()
    }

    /**
     * Returns a set view of the unique tokens currently held in channel [ch].
     */
    fun getUniqueTokens(ch: Int): Set<Int> {
        if (ch !in 0 until nChannels) return emptySet()
        val result = LinkedHashSet<Int>(sizes[ch])
        val tokens = getTokens(ch)
        for (t in tokens) {
            result.add(t)
        }
        return result
    }

    /**
     * Indexed access to the [Channel] view of channel [ch].
     */
    operator fun get(ch: Int): Channel {
        require(ch in 0 until nChannels) { "Channel index $ch out of bounds (nChannels=$nChannels)" }
        return channelViews[ch]
    }

    private fun incrementFrequency(ch: Int, token: Int) {
        if (token in 0 until vocabSize) {
            val prev = frequencies[ch][token]
            frequencies[ch][token] = prev + 1
            if (prev == 0) {
                uniqueCounts[ch]++
            }
        } else {
            var map = overflowFrequencies[ch]
            if (map == null) {
                map = HashMap()
                overflowFrequencies[ch] = map
            }
            val prev = map[token] ?: 0
            map[token] = prev + 1
            if (prev == 0) {
                uniqueCounts[ch]++
            }
        }
    }

    private fun decrementFrequency(ch: Int, token: Int) {
        if (token in 0 until vocabSize) {
            val prev = frequencies[ch][token]
            if (prev > 0) {
                val next = prev - 1
                frequencies[ch][token] = next
                if (next == 0) {
                    uniqueCounts[ch]--
                }
            }
        } else {
            val map = overflowFrequencies[ch]
            if (map != null) {
                val prev = map[token] ?: 0
                if (prev > 0) {
                    val next = prev - 1
                    if (next == 0) {
                        map.remove(token)
                        uniqueCounts[ch]--
                    } else {
                        map[token] = next
                    }
                }
            }
        }
    }

    private fun ensureMarkerCapacity(size: Int) {
        if (marker.size < size) {
            marker = IntArray(size)
            markerEpoch = 1
        }
    }

    private fun nextMarker() {
        markerEpoch++
        if (markerEpoch == 0) {
            marker.fill(0)
            markerEpoch = 1
        }
    }

    private fun isUnseenAndMark(token: Int): Boolean {
        if (token in marker.indices) {
            if (marker[token] != markerEpoch) {
                marker[token] = markerEpoch
                return true
            }
            return false
        }
        return true
    }

    /**
     * Channel-specific wrapper implementing [Set<Int>] for zero-allocation backward compatibility.
     */
    inner class Channel internal constructor(val channelIndex: Int) : Set<Int> {
        override val size: Int get() = this@RepetitionHistory.uniqueSize(channelIndex)
        val totalCount: Int get() = this@RepetitionHistory.size(channelIndex)

        override fun isEmpty(): Boolean = this@RepetitionHistory.isEmpty(channelIndex)
        override fun contains(element: Int): Boolean = this@RepetitionHistory.contains(channelIndex, element)
        override fun containsAll(elements: Collection<Int>): Boolean = elements.all { contains(it) }
        override fun iterator(): Iterator<Int> = this@RepetitionHistory.getUniqueTokens(channelIndex).iterator()

        fun add(token: Int) = this@RepetitionHistory.add(channelIndex, token)
        fun count(token: Int): Int = this@RepetitionHistory.count(channelIndex, token)
        fun clear() = this@RepetitionHistory.clear(channelIndex)
        fun applyPenalty(logits: FloatArray, penalty: Float) = this@RepetitionHistory.applyPenalty(channelIndex, logits, penalty)
        fun toList(): List<Int> = this@RepetitionHistory.toList(channelIndex)
        fun getTokens(): IntArray = this@RepetitionHistory.getTokens(channelIndex)
    }
}
