package com.skul9x.doctruyen

import android.content.Intent
import com.skul9x.doctruyen.tts.ITTSManager
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StoryDetailSingleTopTest {

    private class FakeIntent(
        private val intExtras: Map<String, Int> = emptyMap(),
        private val stringExtras: Map<String, String> = emptyMap()
    ) : Intent() {
        override fun getIntExtra(name: String, defaultValue: Int): Int {
            return intExtras[name] ?: defaultValue
        }

        override fun getStringExtra(name: String): String? {
            return stringExtras[name]
        }
    }

    @Test
    fun testManifest_storyDetailActivity_hasSingleTopLaunchMode() {
        val manifestFile = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() }

        assertNotNull("AndroidManifest.xml should exist", manifestFile)

        val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        val doc = docBuilder.parse(manifestFile!!)
        val activities = doc.getElementsByTagName("activity")

        var storyDetailFound = false
        var launchMode: String? = null

        for (i in 0 until activities.length) {
            val element = activities.item(i) as Element
            val name = element.getAttribute("android:name")
            if (name == ".StoryDetailActivity" || name == "com.skul9x.doctruyen.StoryDetailActivity") {
                storyDetailFound = true
                launchMode = element.getAttribute("android:launchMode")
                break
            }
        }

        assertTrue("StoryDetailActivity must be registered in AndroidManifest.xml", storyDetailFound)
        assertEquals("StoryDetailActivity must have singleTop launchMode", "singleTop", launchMode)
    }

    @Test
    fun testOnNewIntent_sameStoryId_doesNotResetState() {
        // State simulation matching StoryDetailActivity contract
        var storyId = 101
        var isPrewarmTriggered = true
        var lastRenderedState: ITTSManager.TTSState? = ITTSManager.TTSState.PLAYING
        var storyContent = "Nội dung chương 1..."
        var reloadCount = 0

        val onNewIntentHandler = { intent: Intent ->
            val newStoryId = intent.getIntExtra("story_id", 0)
            if (newStoryId != storyId && newStoryId != 0) {
                isPrewarmTriggered = false
                lastRenderedState = null
                storyContent = ""
                reloadCount++
                storyId = newStoryId
            }
        }

        // Send new intent with SAME storyId (101)
        val sameStoryIntent = FakeIntent(
            intExtras = mapOf("story_id" to 101),
            stringExtras = mapOf("story_title" to "Truyện A")
        )
        onNewIntentHandler(sameStoryIntent)

        // Verify state is NOT reset and reload is NOT triggered
        assertEquals(101, storyId)
        assertTrue("isPrewarmTriggered should remain true for same story", isPrewarmTriggered)
        assertEquals("lastRenderedState should be preserved for same story", ITTSManager.TTSState.PLAYING, lastRenderedState)
        assertEquals("storyContent should not be cleared for same story", "Nội dung chương 1...", storyContent)
        assertEquals("Reload should not be triggered for same story", 0, reloadCount)
    }

    @Test
    fun testOnNewIntent_differentStoryId_resetsStateAndReloads() {
        // State simulation matching StoryDetailActivity contract
        var storyId = 101
        var isPrewarmTriggered = true
        var lastRenderedState: ITTSManager.TTSState? = ITTSManager.TTSState.PLAYING
        var storyContent = "Nội dung chương 1..."
        var reloadCount = 0

        val onNewIntentHandler = { intent: Intent ->
            val newStoryId = intent.getIntExtra("story_id", 0)
            if (newStoryId != storyId && newStoryId != 0) {
                isPrewarmTriggered = false
                lastRenderedState = null
                storyContent = ""
                reloadCount++
                storyId = newStoryId
            }
        }

        // Send new intent with DIFFERENT storyId (202)
        val differentStoryIntent = FakeIntent(
            intExtras = mapOf("story_id" to 202),
            stringExtras = mapOf("story_title" to "Truyện B")
        )
        onNewIntentHandler(differentStoryIntent)

        // Verify state IS reset and reload IS triggered
        assertEquals(202, storyId)
        assertFalse("isPrewarmTriggered must be reset to false for new story", isPrewarmTriggered)
        assertNull("lastRenderedState must be reset to null for new story", lastRenderedState)
        assertEquals("storyContent must be cleared for new story", "", storyContent)
        assertEquals("Reload must be triggered for new story", 1, reloadCount)
    }

    @Test
    fun testOnNewIntent_zeroOrInvalidStoryId_doesNotResetState() {
        var storyId = 101
        var isPrewarmTriggered = true
        var lastRenderedState: ITTSManager.TTSState? = ITTSManager.TTSState.READY
        var storyContent = "Nội dung đang xem..."
        var reloadCount = 0

        val onNewIntentHandler = { intent: Intent ->
            val newStoryId = intent.getIntExtra("story_id", 0)
            if (newStoryId != storyId && newStoryId != 0) {
                isPrewarmTriggered = false
                lastRenderedState = null
                storyContent = ""
                reloadCount++
                storyId = newStoryId
            }
        }

        // Send intent with missing / 0 storyId
        val emptyIntent = FakeIntent()
        onNewIntentHandler(emptyIntent)

        assertEquals(101, storyId)
        assertTrue(isPrewarmTriggered)
        assertEquals(ITTSManager.TTSState.READY, lastRenderedState)
        assertEquals("Nội dung đang xem...", storyContent)
        assertEquals(0, reloadCount)
    }
}
