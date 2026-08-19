package com.skul9x.doctruyen.tts.vieneu

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PrewarmCacheInvalidationTest {

    @Before
    fun setup() {
        PrewarmedChunkCache.clear()
    }

    @After
    fun tearDown() {
        PrewarmedChunkCache.clear()
    }

    @Test
    fun testCacheInvalidationOnVoiceChange() {
        val dummyPcm = ShortArray(100) { 1 }
        val storyId = 101

        // Pre-warm story with voice A ("Trúc Ly")
        PrewarmedChunkCache.put(storyId, "Trúc Ly", 0, dummyPcm)
        PrewarmedChunkCache.put(storyId, "Trúc Ly", 1, dummyPcm)

        assertEquals(2, PrewarmedChunkCache.size())
        assertTrue(PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0))
        assertTrue(PrewarmedChunkCache.contains(storyId, "Trúc Ly", 1))

        // Query by different voice returns empty
        val chunksMinhDuc = PrewarmedChunkCache.getChunksForStory(storyId, "Minh Đức")
        assertTrue(chunksMinhDuc.isEmpty())

        // Query by Trúc Ly returns 2 chunks
        val chunksTrucLy = PrewarmedChunkCache.getChunksForStory(storyId, "Trúc Ly")
        assertEquals(2, chunksTrucLy.size)

        // Clear on voice change
        PrewarmedChunkCache.clear()
        assertEquals(0, PrewarmedChunkCache.size())

        // Pre-warm again with voice B ("Minh Đức")
        PrewarmedChunkCache.put(storyId, "Minh Đức", 0, dummyPcm)
        assertEquals(1, PrewarmedChunkCache.size())
        assertTrue(PrewarmedChunkCache.contains(storyId, "Minh Đức", 0))
        assertFalse(PrewarmedChunkCache.contains(storyId, "Trúc Ly", 0))
    }
}
