package com.skul9x.doctruyen.tts.vieneu

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Key representing a pre-warmed audio chunk in memory.
 */
data class PrewarmedKey(
    val storyId: Int,
    val voiceName: String,
    val chunkIndex: Int
)

/**
 * In-memory thread-safe cache for pre-synthesized PCM audio chunks.
 * Holds pre-warmed chunks for instant playback, with LRU eviction and memory bounds.
 */
object PrewarmedChunkCache {

    private const val TAG = "PrewarmedChunkCache"
    const val MAX_CAPACITY = 4

    // Map storing key -> PCM short array
    private val cache = ConcurrentHashMap<PrewarmedKey, ShortArray>()
    // Linked list or queue to track order of insertion for LRU / bounded capacity
    private val accessOrder = java.util.concurrent.ConcurrentLinkedDeque<PrewarmedKey>()

    @Volatile
    var activeStoryId: Int? = null
        private set

    /**
     * Put a pre-warmed PCM buffer into the cache.
     */
    fun put(storyId: Int, voiceName: String, chunkIndex: Int, pcm: ShortArray) {
        val key = PrewarmedKey(storyId, voiceName, chunkIndex)

        // If story switched, clear old story chunks first
        if (activeStoryId != null && activeStoryId != storyId) {
            clearPreviousStory(storyId)
        }
        activeStoryId = storyId

        synchronized(this) {
            // Remove existing key from order if present
            accessOrder.remove(key)

            // Evict oldest if capacity exceeded
            while (accessOrder.size >= MAX_CAPACITY) {
                val oldestKey = accessOrder.pollFirst()
                if (oldestKey != null) {
                    cache.remove(oldestKey)
                    Log.d(TAG, "Evicted prewarmed chunk: $oldestKey")
                }
            }

            cache[key] = pcm
            accessOrder.addLast(key)
            Log.d(TAG, "Cached prewarmed chunk $chunkIndex for story $storyId (voice: $voiceName, size: ${pcm.size})")
        }
    }

    /**
     * Get a pre-warmed PCM buffer from the cache.
     */
    fun get(storyId: Int, voiceName: String, chunkIndex: Int): ShortArray? {
        val key = PrewarmedKey(storyId, voiceName, chunkIndex)
        val pcm = cache[key]
        if (pcm != null) {
            synchronized(this) {
                accessOrder.remove(key)
                accessOrder.addLast(key)
            }
        }
        return pcm
    }

    /**
     * Check if a specific chunk is cached.
     */
    fun contains(storyId: Int, voiceName: String, chunkIndex: Int): Boolean {
        return cache.containsKey(PrewarmedKey(storyId, voiceName, chunkIndex))
    }

    /**
     * Get all cached chunks for a given story and voice, ordered by chunk index.
     */
    fun getChunksForStory(storyId: Int, voiceName: String): Map<Int, ShortArray> {
        val result = mutableMapOf<Int, ShortArray>()
        cache.forEach { (key, pcm) ->
            if (key.storyId == storyId && key.voiceName == voiceName) {
                result[key.chunkIndex] = pcm
            }
        }
        return result
    }

    /**
     * Clear cache for previous story when switching stories.
     */
    fun clearPreviousStory(newStoryId: Int) {
        synchronized(this) {
            val toRemove = cache.keys.filter { it.storyId != newStoryId }
            for (key in toRemove) {
                cache.remove(key)
                accessOrder.remove(key)
            }
            activeStoryId = newStoryId
            Log.d(TAG, "Cleared previous stories from cache. Current active story: $newStoryId")
        }
    }

    /**
     * Clear all cached chunks.
     */
    fun clear() {
        synchronized(this) {
            cache.clear()
            accessOrder.clear()
            activeStoryId = null
            Log.d(TAG, "PrewarmedChunkCache cleared.")
        }
    }

    /**
     * Returns total number of cached chunks.
     */
    fun size(): Int = cache.size
}
