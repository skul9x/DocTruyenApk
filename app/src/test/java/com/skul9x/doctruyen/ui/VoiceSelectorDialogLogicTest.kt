package com.skul9x.doctruyen.ui

import com.skul9x.doctruyen.tts.AudioPreviewCache
import com.skul9x.doctruyen.tts.AudioPreviewEngine
import com.skul9x.doctruyen.tts.AudioPreviewState
import com.skul9x.doctruyen.tts.IAudioPreviewPlayer
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.tts.ITtsEngineWrapper
import com.skul9x.doctruyen.tts.VoiceFilterCriteria
import com.skul9x.doctruyen.tts.VoiceGender
import com.skul9x.doctruyen.tts.VoiceInfo
import com.skul9x.doctruyen.tts.VoiceRegion
import com.skul9x.doctruyen.tts.VoiceStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification for Phase 04: Traditional Dialog Voice Selector UI.
 * Verifies:
 * 1. VoiceSelectorItem data model & properties.
 * 2. VoiceSelectorDiffCallback item equality, content equality, and granular change payloads.
 * 3. 3-Tier ChipGroup filtering: Gender, Region, Style, and multi-faceted combinations.
 * 4. Empty state detection and Reset Filters action.
 * 5. Voice selection state tracking and confirmation callback.
 * 6. Audio preview engine integration with real-time state changes and single-active preview semantics.
 * 7. Dialog dismissal stop & activity onDestroy release lifecycle safety.
 * 8. Dual-engine compatibility: VieNeu neural voice presets and Google TTS system voices.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSelectorDialogLogicTest {

    private class TestAudioPlayer : IAudioPreviewPlayer {
        var isPlayingAudio = false
        var lastPcm: ShortArray? = null
        var completionCallback: (() -> Unit)? = null
        var stopCount = 0
        var releaseCount = 0

        override val isPlaying: Boolean
            get() = isPlayingAudio

        override fun play(pcm: ShortArray, onComplete: () -> Unit) {
            isPlayingAudio = true
            lastPcm = pcm
            completionCallback = onComplete
        }

        override fun stop() {
            isPlayingAudio = false
            stopCount++
        }

        override fun release() {
            isPlayingAudio = false
            releaseCount++
        }

        fun triggerCompletion() {
            isPlayingAudio = false
            completionCallback?.invoke()
            completionCallback = null
        }
    }

    private class TestGoogleTtsWrapper : ITtsEngineWrapper {
        var lastVoice: String? = null
        var lastSpokenText: String? = null
        var stopCallCount = 0
        var listener: android.speech.tts.UtteranceProgressListener? = null

        override fun setSpeechRate(rate: Float): Int = 0
        override fun setPitch(pitch: Float): Int = 0
        override fun setLanguage(locale: java.util.Locale): Int = 0
        override fun setVoice(voice: android.speech.tts.Voice): Int = 0
        override fun setVoiceByName(voiceName: String): Int {
            lastVoice = voiceName
            return 0
        }
        override fun getVoices(): Set<android.speech.tts.Voice>? = null
        override fun getVoiceInfoList(): List<VoiceInfo>? = null
        override fun speak(text: String, queueMode: Int, params: android.os.Bundle?, utteranceId: String): Int {
            lastSpokenText = text
            listener?.onStart(utteranceId)
            return 0
        }
        override fun speakLegacy(text: String, queueMode: Int, params: HashMap<String, String>): Int = 0
        override fun playSilentUtterance(durationMs: Long, queueMode: Int, utteranceId: String): Int = 0
        override fun setOnUtteranceProgressListener(listener: android.speech.tts.UtteranceProgressListener) {
            this.listener = listener
        }
        override fun stop(): Int {
            stopCallCount++
            return 0
        }
        override fun shutdown() {}
    }

    private lateinit var sampleVoices: List<VoiceInfo>
    private lateinit var testPlayer: TestAudioPlayer
    private lateinit var previewEngine: AudioPreviewEngine

    @Before
    fun setUp() {
        testPlayer = TestAudioPlayer()
        previewEngine = AudioPreviewEngine(
            context = null,
            cache = AudioPreviewCache(maxEntries = 16, maxSizeBytes = 1024 * 1024),
            audioPlayer = testPlayer,
            customSynthesizer = { voice, text ->
                ShortArray(480) { 100 }
            },
            dispatcher = Dispatchers.Unconfined
        )

        sampleVoices = listOf(
            VoiceInfo(
                id = "hai_dang",
                displayName = "Hải Đăng",
                description = "Giọng nam miền Nam trầm ấm, kể chuyện kiếm hiệp",
                gender = "Nam",
                region = "Miền Nam",
                style = "Kể chuyện"
            ),
            VoiceInfo(
                id = "truc_ly",
                displayName = "Trúc Ly",
                description = "Giọng nữ miền Bắc nhẹ nhàng, đọc truyện tình cảm",
                gender = "Nữ",
                region = "Miền Bắc",
                style = "Kể chuyện"
            ),
            VoiceInfo(
                id = "thien_minh",
                displayName = "Thiện Minh",
                description = "Giọng nam miền Trung tự nhiên, đọc báo",
                gender = "Nam",
                region = "Miền Trung",
                style = "Tự nhiên"
            ),
            VoiceInfo(
                id = "ngoc_linh",
                displayName = "Ngọc Linh",
                description = "Giọng nữ miền Nam tin tức thời sự",
                gender = "Nữ",
                region = "Miền Nam",
                style = "Tin tức"
            ),
            VoiceInfo(
                id = "quoc_tuan",
                displayName = "Quốc Tuấn",
                description = "Giọng nam miền Bắc tự nhiên, linh hoạt",
                gender = "Nam",
                region = "Miền Bắc",
                style = "Tự nhiên"
            )
        )
    }

    @Test
    fun testVoiceSelectorItemProperties() {
        val voice = sampleVoices[0]
        val item = VoiceSelectorItem(
            voiceInfo = voice,
            isSelected = true,
            previewState = AudioPreviewState.Playing(voice.id)
        )

        assertEquals("hai_dang", item.id)
        assertEquals("Hải Đăng", item.displayName)
        assertEquals(voice.description, item.description)
        assertTrue(item.isSelected)
        assertTrue(item.previewState is AudioPreviewState.Playing)
    }

    @Test
    fun testVoiceSelectorDiffCallbackEqualityAndPayloads() {
        val diff = VoiceSelectorDiffCallback()
        val voice1 = sampleVoices[0]
        val voice2 = sampleVoices[1]

        val item1 = VoiceSelectorItem(voice1, isSelected = false, previewState = AudioPreviewState.Idle)
        val item1Selected = VoiceSelectorItem(voice1, isSelected = true, previewState = AudioPreviewState.Idle)
        val item1Playing = VoiceSelectorItem(voice1, isSelected = false, previewState = AudioPreviewState.Playing(voice1.id))
        val item1SelectedAndPlaying = VoiceSelectorItem(voice1, isSelected = true, previewState = AudioPreviewState.Playing(voice1.id))
        val item2 = VoiceSelectorItem(voice2, isSelected = false, previewState = AudioPreviewState.Idle)

        // areItemsTheSame
        assertTrue(diff.areItemsTheSame(item1, item1Selected))
        assertFalse(diff.areItemsTheSame(item1, item2))

        // areContentsTheSame
        assertTrue(diff.areContentsTheSame(item1, item1.copy()))
        assertFalse(diff.areContentsTheSame(item1, item1Selected))
        assertFalse(diff.areContentsTheSame(item1, item1Playing))

        // getChangePayload
        assertEquals(VoiceSelectorDiffCallback.ChangePayload.SELECTION, diff.getChangePayload(item1, item1Selected))
        assertEquals(VoiceSelectorDiffCallback.ChangePayload.PREVIEW_STATE, diff.getChangePayload(item1, item1Playing))
        assertEquals(VoiceSelectorDiffCallback.ChangePayload.FULL, diff.getChangePayload(item1, item1SelectedAndPlaying))
        assertNull(diff.getChangePayload(item1, item1.copy()))
    }

    @Test
    fun testInitialControllerStateLoadsAllVoices() {
        val controller = VoiceSelectorController(
            allVoices = sampleVoices,
            initialSelectedId = "hai_dang",
            previewEngine = previewEngine
        )

        assertEquals(5, controller.currentItems.size)
        assertEquals("hai_dang", controller.selectedVoiceId)
        assertFalse(controller.isEmptyState)

        // Verify initial selection flag
        val haiDangItem = controller.currentItems.find { it.id == "hai_dang" }
        assertNotNull(haiDangItem)
        assertTrue(haiDangItem!!.isSelected)

        val trucLyItem = controller.currentItems.find { it.id == "truc_ly" }
        assertNotNull(trucLyItem)
        assertFalse(trucLyItem!!.isSelected)
    }

    @Test
    fun testGenderFiltering() {
        val controller = VoiceSelectorController(
            allVoices = sampleVoices,
            initialSelectedId = "hai_dang",
            previewEngine = previewEngine
        )

        // Filter Male
        controller.setGenderFilter(VoiceGender.MALE)
        assertEquals(3, controller.currentItems.size)
        assertTrue(controller.currentItems.all { it.voiceInfo.canonicalGender == VoiceGender.MALE })

        // Filter Female
        controller.setGenderFilter(VoiceGender.FEMALE)
        assertEquals(2, controller.currentItems.size)
        assertTrue(controller.currentItems.all { it.voiceInfo.canonicalGender == VoiceGender.FEMALE })

        // Reset to ALL
        controller.setGenderFilter(VoiceGender.ALL)
        assertEquals(5, controller.currentItems.size)
    }

    @Test
    fun testRegionFiltering() {
        val controller = VoiceSelectorController(
            allVoices = sampleVoices,
            initialSelectedId = "hai_dang",
            previewEngine = previewEngine
        )

        // Filter North (Miền Bắc: Trúc Ly, Quốc Tuấn)
        controller.setRegionFilter(VoiceRegion.NORTH)
        assertEquals(2, controller.currentItems.size)
        assertTrue(controller.currentItems.all { it.voiceInfo.canonicalRegion == VoiceRegion.NORTH })

        // Filter Central (Miền Trung: Thiện Minh)
        controller.setRegionFilter(VoiceRegion.CENTRAL)
        assertEquals(1, controller.currentItems.size)
        assertEquals("thien_minh", controller.currentItems[0].id)

        // Filter South (Miền Nam: Hải Đăng, Ngọc Linh)
        controller.setRegionFilter(VoiceRegion.SOUTH)
        assertEquals(2, controller.currentItems.size)
        assertTrue(controller.currentItems.all { it.voiceInfo.canonicalRegion == VoiceRegion.SOUTH })
    }

    @Test
    fun testStyleFiltering() {
        val controller = VoiceSelectorController(
            allVoices = sampleVoices,
            initialSelectedId = "hai_dang",
            previewEngine = previewEngine
        )

        // Storytelling (Hải Đăng, Trúc Ly)
        controller.setStyleFilter(VoiceStyle.STORYTELLING)
        assertEquals(2, controller.currentItems.size)
        assertTrue(controller.currentItems.all { it.voiceInfo.canonicalStyle == VoiceStyle.STORYTELLING })

        // Natural (Thiện Minh, Quốc Tuấn)
        controller.setStyleFilter(VoiceStyle.NATURAL)
        assertEquals(2, controller.currentItems.size)
        assertTrue(controller.currentItems.all { it.voiceInfo.canonicalStyle == VoiceStyle.NATURAL })

        // News (Ngọc Linh)
        controller.setStyleFilter(VoiceStyle.NEWS)
        assertEquals(1, controller.currentItems.size)
        assertEquals("ngoc_linh", controller.currentItems[0].id)
    }

    @Test
    fun testMultiFacetedCombinedFiltering() {
        val controller = VoiceSelectorController(
            allVoices = sampleVoices,
            initialSelectedId = "hai_dang",
            previewEngine = previewEngine
        )

        // Male + South + Storytelling -> Only Hải Đăng
        controller.setGenderFilter(VoiceGender.MALE)
        controller.setRegionFilter(VoiceRegion.SOUTH)
        controller.setStyleFilter(VoiceStyle.STORYTELLING)

        assertEquals(1, controller.currentItems.size)
        assertEquals("hai_dang", controller.currentItems[0].id)
    }

    @Test
    fun testEmptyStateAndResetFiltersAction() {
        val controller = VoiceSelectorController(
            allVoices = sampleVoices,
            initialSelectedId = "hai_dang",
            previewEngine = previewEngine
        )

        var emittedCount = 0
        var lastEmittedEmpty = false
        controller.onItemsChanged = { items, isEmpty ->
            emittedCount++
            lastEmittedEmpty = isEmpty
        }

        // Combine criteria that matches 0 voices: Female + Central
        controller.setGenderFilter(VoiceGender.FEMALE)
        controller.setRegionFilter(VoiceRegion.CENTRAL)

        assertTrue(controller.isEmptyState)
        assertTrue(controller.currentItems.isEmpty())
        assertTrue(lastEmittedEmpty)

        // Action: Reset filters
        controller.resetFilters()

        assertFalse(controller.isEmptyState)
        assertEquals(5, controller.currentItems.size)
        assertEquals(VoiceFilterCriteria.ALL, controller.criteria)
        assertFalse(lastEmittedEmpty)
    }

    @Test
    fun testVoiceSelectionAndConfirmationCallback() {
        val controller = VoiceSelectorController(
            allVoices = sampleVoices,
            initialSelectedId = "hai_dang",
            previewEngine = previewEngine
        )

        // Change selection to truc_ly
        controller.selectVoice("truc_ly")
        assertEquals("truc_ly", controller.selectedVoiceId)

        val trucLyItem = controller.currentItems.find { it.id == "truc_ly" }
        assertNotNull(trucLyItem)
        assertTrue(trucLyItem!!.isSelected)

        val haiDangItem = controller.currentItems.find { it.id == "hai_dang" }
        assertNotNull(haiDangItem)
        assertFalse(haiDangItem!!.isSelected)

        // Confirm selection
        var confirmedVoiceId: String? = null
        controller.onVoiceConfirmed = { id ->
            confirmedVoiceId = id
        }
        controller.confirmSelection()
        assertEquals("truc_ly", confirmedVoiceId)
    }

    @Test
    fun testAudioPreviewToggleAndStateBinding() {
        val controller = VoiceSelectorController(
            allVoices = sampleVoices,
            initialSelectedId = "hai_dang",
            previewEngine = previewEngine
        )

        val targetVoice = sampleVoices[0]

        // Start audition
        controller.togglePreview(targetVoice)
        assertTrue(testPlayer.isPlaying)
        assertTrue(previewEngine.isPlaying)

        val activeItem = controller.currentItems.find { it.id == targetVoice.id }
        assertNotNull(activeItem)
        assertTrue(activeItem!!.previewState is AudioPreviewState.Playing)

        // Other items must remain Idle
        val otherItem = controller.currentItems.find { it.id == "truc_ly" }
        assertNotNull(otherItem)
        assertTrue(otherItem!!.previewState is AudioPreviewState.Idle)

        // Toggle again on same voice -> Stops audition
        controller.togglePreview(targetVoice)
        assertFalse(testPlayer.isPlaying)
        assertFalse(previewEngine.isPlaying)

        val stoppedItem = controller.currentItems.find { it.id == targetVoice.id }
        assertNotNull(stoppedItem)
        assertTrue(stoppedItem!!.previewState is AudioPreviewState.Idle || stoppedItem.previewState is AudioPreviewState.Stopped)
    }

    @Test
    fun testSingleActivePreviewSwitchesActiveVoiceImmediately() {
        val controller = VoiceSelectorController(
            allVoices = sampleVoices,
            initialSelectedId = "hai_dang",
            previewEngine = previewEngine
        )

        // Audition voice A (hai_dang)
        controller.togglePreview(sampleVoices[0])
        assertTrue(previewEngine.isPlaying)
        assertEquals("hai_dang", (controller.currentPreviewState as AudioPreviewState.Playing).voiceId)

        // Immediately audition voice B (truc_ly)
        controller.togglePreview(sampleVoices[1])
        assertTrue(previewEngine.isPlaying)
        assertEquals("truc_ly", (controller.currentPreviewState as AudioPreviewState.Playing).voiceId)

        val voiceAItem = controller.currentItems.find { it.id == "hai_dang" }
        val voiceBItem = controller.currentItems.find { it.id == "truc_ly" }

        assertTrue(voiceAItem!!.previewState is AudioPreviewState.Idle)
        assertTrue(voiceBItem!!.previewState is AudioPreviewState.Playing)
    }

    @Test
    fun testDialogDismissalStopsPreviewImmediately() {
        val controller = VoiceSelectorController(
            allVoices = sampleVoices,
            initialSelectedId = "hai_dang",
            previewEngine = previewEngine
        )

        controller.togglePreview(sampleVoices[0])
        assertTrue(testPlayer.isPlaying)

        // User dismisses dialog (via Cancel, outside touch, or confirm)
        controller.onDismiss()

        assertFalse(testPlayer.isPlaying)
        assertFalse(previewEngine.isPlaying)
        assertTrue(testPlayer.stopCount >= 1)
    }

    @Test
    fun testActivityDestroyReleasesPreviewEngine() {
        val controller = VoiceSelectorController(
            allVoices = sampleVoices,
            initialSelectedId = "hai_dang",
            previewEngine = previewEngine
        )

        controller.togglePreview(sampleVoices[0])
        assertTrue(testPlayer.isPlaying)

        controller.release()

        assertFalse(testPlayer.isPlaying)
        assertFalse(previewEngine.isPlaying)
        assertEquals(AudioPreviewState.Idle, previewEngine.currentState)
        assertTrue(testPlayer.releaseCount >= 1)
    }

    @Test
    fun testGoogleTTSPreviewIntegration() {
        val googleWrapper = TestGoogleTtsWrapper()
        val googleEngine = AudioPreviewEngine(
            context = null,
            audioPlayer = testPlayer,
            googleTtsWrapper = googleWrapper,
            dispatcher = Dispatchers.Unconfined
        )

        val googleVoices = listOf(
            VoiceInfo(id = "vi-vn-x-vic-network", displayName = "Google Tiếng Việt 1", gender = "Nữ", region = "Miền Bắc", style = "Tự nhiên"),
            VoiceInfo(id = "vi-vn-x-gda-local", displayName = "Google Tiếng Việt 2", gender = "Nam", region = "Miền Nam", style = "Tin tức")
        )

        val controller = VoiceSelectorController(
            allVoices = googleVoices,
            initialSelectedId = "vi-vn-x-vic-network",
            previewEngine = googleEngine,
            engineType = ITTSManager.EngineType.GOOGLE_TTS
        )

        controller.togglePreview(googleVoices[0])
        assertEquals("vi-vn-x-vic-network", googleWrapper.lastVoice)
        assertNotNull(googleWrapper.lastSpokenText)
        assertTrue(googleWrapper.lastSpokenText!!.contains("Google Tiếng Việt 1"))

        // Dismissal stops Google TTS
        controller.onDismiss()
        assertTrue(googleWrapper.stopCallCount >= 1)
    }
}
