package com.skul9x.doctruyen

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StoryDetailContentCacheGuardTest {

    /**
     * Simulates the StoryDetailActivity content loading and caching lifecycle
     * matching the exact implementation in StoryDetailActivity.kt.
     */
    class StoryDetailLoaderSimulator(
        private val networkFetch: suspend (Int) -> String
    ) {
        var storyId: Int = 0
        var storyContent: String = ""
        var loadedStoryId: Int = 0
        var isLoadingContent: Boolean = false

        var networkCallCount: Int = 0
        var skippedCacheCount: Int = 0
        var skippedInFlightCount: Int = 0
        var prewarmTriggeredCount: Int = 0

        suspend fun loadStoryContent() {
            // Guard 1: Skip API call if we already have content for this exact story
            if (storyContent.isNotEmpty() && loadedStoryId == storyId) {
                skippedCacheCount++
                triggerPrewarmIfReady()
                return
            }

            // Guard 2: Prevent overlapping requests
            if (isLoadingContent) {
                skippedInFlightCount++
                return
            }
            isLoadingContent = true

            try {
                networkCallCount++
                val content = networkFetch(storyId)
                storyContent = content
                loadedStoryId = storyId
                triggerPrewarmIfReady()
            } finally {
                isLoadingContent = false
            }
        }

        fun onNewIntent(newStoryId: Int) {
            if (newStoryId != storyId && newStoryId != 0) {
                storyContent = ""
                loadedStoryId = 0
                storyId = newStoryId
            }
        }

        private fun triggerPrewarmIfReady() {
            if (storyContent.isNotEmpty()) {
                prewarmTriggeredCount++
            }
        }
    }

    @Test
    fun testFirstCall_triggersNetworkRequest() = runBlocking {
        val loader = StoryDetailLoaderSimulator { id ->
            "Nội dung truyện $id"
        }
        loader.storyId = 119

        loader.loadStoryContent()

        assertEquals(1, loader.networkCallCount)
        assertEquals("Nội dung truyện 119", loader.storyContent)
        assertEquals(119, loader.loadedStoryId)
        assertEquals(0, loader.skippedCacheCount)
        assertEquals(1, loader.prewarmTriggeredCount)
    }

    @Test
    fun testSecondCall_sameStoryId_isSkippedByCacheGuard() = runBlocking {
        val loader = StoryDetailLoaderSimulator { id ->
            "Nội dung truyện $id"
        }
        loader.storyId = 119

        // First call loads content
        loader.loadStoryContent()
        assertEquals(1, loader.networkCallCount)

        // Second call for the SAME storyId
        loader.loadStoryContent()

        // Network call count must NOT increase
        assertEquals(1, loader.networkCallCount)
        assertEquals(1, loader.skippedCacheCount)
        assertEquals(0, loader.skippedInFlightCount)
        assertEquals("Nội dung truyện 119", loader.storyContent)
        assertEquals(119, loader.loadedStoryId)
        // Prewarm should still be triggered on cache hit
        assertEquals(2, loader.prewarmTriggeredCount)
    }

    @Test
    fun testDifferentStoryId_triggersNewNetworkRequest() = runBlocking {
        val loader = StoryDetailLoaderSimulator { id ->
            "Nội dung truyện $id"
        }
        loader.storyId = 119
        loader.loadStoryContent()
        assertEquals(1, loader.networkCallCount)

        // Switch story via onNewIntent
        loader.onNewIntent(120)
        assertEquals(0, loader.loadedStoryId)
        assertEquals("", loader.storyContent)

        // Load new story
        loader.loadStoryContent()

        assertEquals(2, loader.networkCallCount)
        assertEquals("Nội dung truyện 120", loader.storyContent)
        assertEquals(120, loader.loadedStoryId)
    }

    @Test
    fun testConcurrentCalls_inFlightGuardSkipsDuplicateRequest() = runBlocking {
        val requestGate = CompletableDeferred<String>()
        val loader = StoryDetailLoaderSimulator { id ->
            requestGate.await()
        }
        loader.storyId = 119

        // Launch first request in background
        val scope = CoroutineScope(Dispatchers.Default)
        val job1 = scope.launch {
            loader.loadStoryContent()
        }

        // Wait a moment for job1 to acquire isLoadingContent
        while (!loader.isLoadingContent) {
            kotlinx.coroutines.delay(10)
        }

        // Attempt second concurrent request while first is in-flight
        loader.loadStoryContent()

        // Second request must be skipped by in-flight guard
        assertEquals(1, loader.skippedInFlightCount)
        assertEquals(1, loader.networkCallCount)

        // Complete the first request
        requestGate.complete("Nội dung truyện 119")
        job1.join()

        // Verify first request completed successfully
        assertFalse(loader.isLoadingContent)
        assertEquals(119, loader.loadedStoryId)
        assertEquals("Nội dung truyện 119", loader.storyContent)
    }
}
