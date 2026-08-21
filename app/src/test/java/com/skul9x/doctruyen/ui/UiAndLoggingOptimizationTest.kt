package com.skul9x.doctruyen.ui

import androidx.arch.core.executor.ArchTaskExecutor
import androidx.arch.core.executor.TaskExecutor
import androidx.lifecycle.Observer
import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogEntry
import com.skul9x.doctruyen.utils.LogType
import com.skul9x.doctruyen.utils.MAX_LOG_ENTRIES
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UiAndLoggingOptimizationTest {

    @Before
    fun setUp() {
        ArchTaskExecutor.getInstance().setDelegate(object : TaskExecutor() {
            override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
            override fun postToMainThread(runnable: Runnable) = runnable.run()
            override fun isMainThread(): Boolean = true
        })
        DebugLogger.clear()
    }

    @After
    fun tearDown() {
        DebugLogger.clear()
        ArchTaskExecutor.getInstance().setDelegate(null)
    }

    @Test
    fun testDebugLogger_observerGuardedPosting() {
        val observedValues = mutableListOf<List<LogEntry>>()
        val observer = Observer<List<LogEntry>> { list ->
            observedValues.add(list)
        }

        // 1. Without active observers
        DebugLogger.log("TAG1", "Message without observer 1")
        DebugLogger.log("TAG2", "Message without observer 2")

        // Formatted logs and internal buffer contain the messages
        val formatted = DebugLogger.getFormattedLogs()
        assertTrue(formatted.contains("Message without observer 1"))
        assertTrue(formatted.contains("Message without observer 2"))

        // Observer hasn't received anything yet
        assertEquals(0, observedValues.size)

        // 2. Attach active observer
        DebugLogger.logs.observeForever(observer)
        try {
            // LiveData initially dispatches its current value upon observation
            observedValues.clear()

            // Log with active observer
            DebugLogger.log("TAG3", "Message with active observer", LogType.REQUEST)
            assertEquals(1, observedValues.size)
            assertEquals(3, observedValues[0].size) // 2 previous + 1 new
            assertEquals("Message with active observer", observedValues[0].last().message)
            assertEquals(LogType.REQUEST, observedValues[0].last().type)

            // Log error
            DebugLogger.logError("TAG_ERR", "Error occurred")
            assertEquals(2, observedValues.size)
            assertEquals(4, observedValues[1].size)
            assertEquals(LogType.ERROR, observedValues[1].last().type)
        } finally {
            // 3. Detach observer
            DebugLogger.logs.removeObserver(observer)
        }

        // 4. Log after observer removed -> should not trigger postValue
        val countBefore = observedValues.size
        DebugLogger.log("TAG4", "Message after observer removed")
        assertEquals(countBefore, observedValues.size)

        val updatedFormatted = DebugLogger.getFormattedLogs()
        assertTrue(updatedFormatted.contains("Message after observer removed"))
    }

    @Test
    fun testDebugLogger_clearFlushesBuffer() {
        val observedValues = mutableListOf<List<LogEntry>>()
        val observer = Observer<List<LogEntry>> { list ->
            observedValues.add(list)
        }

        DebugLogger.logs.observeForever(observer)
        try {
            DebugLogger.log("TAG1", "Item 1")
            DebugLogger.log("TAG2", "Item 2")
            assertTrue(DebugLogger.getFormattedLogs().contains("Item 1"))

            // Clear with active observer
            DebugLogger.clear()
            assertEquals("", DebugLogger.getFormattedLogs())
            assertEquals(emptyList<LogEntry>(), observedValues.last())
        } finally {
            DebugLogger.logs.removeObserver(observer)
        }

        // Clear without active observer
        DebugLogger.log("TAG3", "Item 3")
        assertTrue(DebugLogger.getFormattedLogs().contains("Item 3"))
        DebugLogger.clear()
        assertEquals("", DebugLogger.getFormattedLogs())
    }

    @Test
    fun testDebugLogger_maxBufferRollingLimit() {
        for (i in 1..(MAX_LOG_ENTRIES + 50)) {
            DebugLogger.log("BURST", "Entry $i")
        }

        val formatted = DebugLogger.getFormattedLogs()
        // Oldest entries (1 to 50) should be dropped
        assertFalse(formatted.contains("Entry 1\n"))
        assertFalse(formatted.contains("Entry 50\n"))
        // Latest entry should be present
        assertTrue(formatted.contains("Entry ${MAX_LOG_ENTRIES + 50}"))
    }

    @Test
    fun testSinglePassContentProcessing() {
        // Simulates the single-pass conversion in StoryDetailActivity:
        // Parse once into an intermediate object, extract plain string, and preserve reference
        val rawHtml = "<p>Chương 1: <b>Khởi đầu</b></p><br/>Nội dung chính..."
        
        // Custom single-pass simulation representing Spanned processing
        data class MockSpanned(val raw: String) : CharSequence {
            override val length: Int get() = raw.replace(Regex("<[^>]*>"), "").length
            override fun get(index: Int): Char = raw.replace(Regex("<[^>]*>"), "")[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
                raw.replace(Regex("<[^>]*>"), "").subSequence(startIndex, endIndex)
            override fun toString(): String = raw.replace(Regex("<[^>]*>"), "")
        }

        val spannedContent = MockSpanned(rawHtml)
        val storyContent = spannedContent.toString()
        val tvContentText = spannedContent // Set to tvContent.text directly

        assertEquals("Chương 1: Khởi đầuNội dung chính...", storyContent)
        assertSame(spannedContent, tvContentText)
        assertEquals(storyContent, tvContentText.toString())
    }
}
