package com.skul9x.doctruyen.tts.vieneu

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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
 * Holds pre-warmed chunks for instant playback, with LRU eviction, memory bounds,
 * and cooperative coordination for in-flight prewarm synthesis passes.
 */
object PrewarmedChunkCache {

    private const val TAG = "PrewarmedChunkCache"
    const val MAX_CAPACITY = 4

    // Map storing key -> PCM short array
    private val cache = ConcurrentHashMap<PrewarmedKey, ShortArray>()
    // Linked list or queue to track order of insertion for LRU / bounded capacity
    private val accessOrder = java.util.concurrent.ConcurrentLinkedDeque<PrewarmedKey>()

    // Map tracking in-flight prewarming requests to allow pipeline consumers to coordinate/await without duplicate inference
    private val inFlight = ConcurrentHashMap<PrewarmedKey, CompletableDeferred<ShortArray?>>()

    @Volatile
    var activeStoryId: Int? = null
        private set

    /**
     * Register an in-flight synthesis pass for a given chunk.
     */
    fun registerInFlight(storyId: Int, voiceName: String, chunkIndex: Int): CompletableDeferred<ShortArray?> {
        val key = PrewarmedKey(storyId, voiceName, chunkIndex)
        return inFlight.computeIfAbsent(key) { CompletableDeferred() }
    }

    /**
     * Check if a chunk is actively being synthesized in background prewarm.
     */
    fun isInFlight(storyId: Int, voiceName: String, chunkIndex: Int): Boolean {
        val deferred = inFlight[PrewarmedKey(storyId, voiceName, chunkIndex)]
        return deferred != null && deferred.isActive
    }

    /**
     * Retrieve the in-flight deferred for a chunk if currently synthesizing.
     */
    fun getInFlight(storyId: Int, voiceName: String, chunkIndex: Int): CompletableDeferred<ShortArray?>? {
        return inFlight[PrewarmedKey(storyId, voiceName, chunkIndex)]
    }

    /**
     * Notify that an in-flight prewarm chunk failed or was cancelled before completion.
     */
    fun notifyInFlightFailed(storyId: Int, voiceName: String, chunkIndex: Int, error: Throwable? = null) {
        val key = PrewarmedKey(storyId, voiceName, chunkIndex)
        val deferred = inFlight.remove(key)
        if (deferred != null && deferred.isActive) {
            if (error != null && error !is kotlinx.coroutines.CancellationException) {
                deferred.completeExceptionally(error)
            } else {
                deferred.complete(null)
            }
        }
    }

    /**
     * Await an in-flight chunk completion, returning the cached PCM or awaiting with a defensive timeout.
     */
    suspend fun awaitInFlightChunk(storyId: Int, voiceName: String, chunkIndex: Int, timeoutMs: Long = 8000L): ShortArray? {
        // Fast path: already completed in cache
        get(storyId, voiceName, chunkIndex)?.let { return it }

        val deferred = getInFlight(storyId, voiceName, chunkIndex) ?: return get(storyId, voiceName, chunkIndex)
        return try {
            withTimeoutOrNull(timeoutMs) {
                deferred.await()
            } ?: get(storyId, voiceName, chunkIndex)
        } catch (e: Throwable) {
            Log.w(TAG, "Error or timeout awaiting in-flight chunk $chunkIndex for story $storyId: ${e.message}")
            get(storyId, voiceName, chunkIndex)
        }
    }

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

            // Complete and resolve any awaiting consumer
            val deferred = inFlight.remove(key)
            deferred?.complete(pcm)

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
            val inFlightToRemove = inFlight.keys.filter { it.storyId != newStoryId }
            for (key in inFlightToRemove) {
                val deferred = inFlight.remove(key)
                deferred?.complete(null)
            }
            activeStoryId = newStoryId
            Log.d(TAG, "Cleared previous stories from cache. Current active story: $newStoryId")
        }
    }

    /**
     * Clear all cached chunks and cancel in-flight deferreds.
     */
    fun clear() {
        synchronized(this) {
            cache.clear()
            accessOrder.clear()
            inFlight.forEach { (_, deferred) ->
                deferred.complete(null)
            }
            inFlight.clear()
            activeStoryId = null
            Log.d(TAG, "PrewarmedChunkCache cleared.")
        }
    }

    /**
     * Returns total number of cached chunks.
     */
    fun size(): Int = cache.size
}
