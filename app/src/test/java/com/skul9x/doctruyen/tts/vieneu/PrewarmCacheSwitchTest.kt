package com.skul9x.doctruyen.tts.vieneu

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PrewarmCacheSwitchTest {

    @Before
    fun setup() {
        PrewarmedChunkCache.clear()
    }

    @After
    fun tearDown() {
        PrewarmedChunkCache.clear()
    }

    @Test
    fun testPrewarmCacheSwitch_invalidatesPreviousStory() {
        val dummyPcm = ShortArray(500) { 1 }
        val story101 = 101
        val story102 = 102
        val voice = "Trúc Ly"

        // 1. Pre-warm Story 101
        PrewarmedChunkCache.put(story101, voice, 0, dummyPcm)
        PrewarmedChunkCache.put(story101, voice, 1, dummyPcm)

        val story101Chunks = PrewarmedChunkCache.getChunksForStory(story101, voice)
        assertEquals(2, story101Chunks.size)
        assertEquals(story101, PrewarmedChunkCache.activeStoryId)

        // 2. Switch to Story 102 and pre-warm / put chunk
        PrewarmedChunkCache.put(story102, voice, 0, dummyPcm)

        // 3. Assert PrewarmedChunkCache.getChunksForStory(101) returns empty map
        val remaining101Chunks = PrewarmedChunkCache.getChunksForStory(story101, voice)
        assertTrue("Previous story 101 chunks must be completely evicted", remaining101Chunks.isEmpty())
        assertFalse(PrewarmedChunkCache.contains(story101, voice, 0))
        assertFalse(PrewarmedChunkCache.contains(story101, voice, 1))

        // Assert Story 102 is active and cached
        val story102Chunks = PrewarmedChunkCache.getChunksForStory(story102, voice)
        assertEquals(1, story102Chunks.size)
        assertEquals(story102, PrewarmedChunkCache.activeStoryId)
    }

    @Test
    fun testClearPreviousStoryExplicitCall() {
        val dummyPcm = ShortArray(300) { 2 }
        val storyA = 201
        val storyB = 202
        val voice = "Minh Đức"

        PrewarmedChunkCache.put(storyA, voice, 0, dummyPcm)
        PrewarmedChunkCache.clearPreviousStory(storyB)

        assertTrue(PrewarmedChunkCache.getChunksForStory(storyA, voice).isEmpty())
        assertEquals(storyB, PrewarmedChunkCache.activeStoryId)
    }
}
