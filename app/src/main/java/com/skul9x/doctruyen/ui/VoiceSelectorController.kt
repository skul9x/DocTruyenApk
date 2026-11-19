package com.skul9x.doctruyen.ui

import com.skul9x.doctruyen.tts.AudioPreviewEngine
import com.skul9x.doctruyen.tts.AudioPreviewState
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.tts.VoiceFilterCriteria
import com.skul9x.doctruyen.tts.VoiceFilterEngine
import com.skul9x.doctruyen.tts.VoiceGender
import com.skul9x.doctruyen.tts.VoiceInfo
import com.skul9x.doctruyen.tts.VoiceRegion
import com.skul9x.doctruyen.tts.VoiceStyle

/**
 * Controller orchestrating voice selector dialog logic:
 * - 3-tier filtering (Gender, Region, Style)
 * - Empty state detection & filter reset
 * - Voice selection state tracking
 * - Real-time AudioPreviewEngine integration and single-active preview semantics
 * - Lifecycle-safe dismissal and stop
 */
class VoiceSelectorController(
    val allVoices: List<VoiceInfo>,
    initialSelectedId: String,
    val previewEngine: AudioPreviewEngine,
    val engineType: ITTSManager.EngineType = ITTSManager.EngineType.VIENEU,
    private val mainRunner: (() -> Unit) -> Unit = { it() }
) {
    var selectedVoiceId: String = initialSelectedId
        private set

    var criteria: VoiceFilterCriteria = VoiceFilterCriteria.ALL
        private set

    var currentPreviewState: AudioPreviewState = AudioPreviewState.Idle
        private set

    var currentItems: List<VoiceSelectorItem> = emptyList()
        private set

    val isEmptyState: Boolean
        get() = currentItems.isEmpty()

    var onItemsChanged: ((items: List<VoiceSelectorItem>, isEmpty: Boolean) -> Unit)? = null
    var onVoiceConfirmed: ((selectedVoiceId: String) -> Unit)? = null

    init {
        // Wire state listener from previewEngine
        previewEngine.onStateChange = { state ->
            mainRunner {
                currentPreviewState = state
                refreshItems()
            }
        }
        refreshItems()
    }

    fun setGenderFilter(gender: VoiceGender) {
        if (criteria.gender == gender) return
        criteria = criteria.copy(gender = gender)
        refreshItems()
    }

    fun setRegionFilter(region: VoiceRegion) {
        if (criteria.region == region) return
        criteria = criteria.copy(region = region)
        refreshItems()
    }

    fun setStyleFilter(style: VoiceStyle) {
        if (criteria.style == style) return
        criteria = criteria.copy(style = style)
        refreshItems()
    }

    fun resetFilters() {
        criteria = VoiceFilterCriteria.ALL
        refreshItems()
    }

    fun selectVoice(voiceId: String) {
        if (selectedVoiceId == voiceId) return
        selectedVoiceId = voiceId
        refreshItems()
    }

    fun togglePreview(voiceInfo: VoiceInfo) {
        previewEngine.toggle(voiceInfo, engineType)
    }

    fun stopPreview() {
        previewEngine.stop()
    }

    fun onDismiss() {
        previewEngine.stop()
    }

    fun confirmSelection() {
        onVoiceConfirmed?.invoke(selectedVoiceId)
    }

    fun release() {
        previewEngine.release()
    }

    private fun refreshItems() {
        val filteredVoices = VoiceFilterEngine.filterVoiceInfos(allVoices, criteria)
        currentItems = filteredVoices.map { voice ->
            val isSelected = (voice.id == selectedVoiceId)
            val itemPreviewState = when {
                currentPreviewState is AudioPreviewState.Loading && (currentPreviewState as AudioPreviewState.Loading).voiceId == voice.id -> currentPreviewState
                currentPreviewState is AudioPreviewState.Playing && (currentPreviewState as AudioPreviewState.Playing).voiceId == voice.id -> currentPreviewState
                currentPreviewState is AudioPreviewState.Error && (currentPreviewState as AudioPreviewState.Error).voiceId == voice.id -> currentPreviewState
                else -> AudioPreviewState.Idle
            }
            VoiceSelectorItem(
                voiceInfo = voice,
                isSelected = isSelected,
                previewState = itemPreviewState
            )
        }
        onItemsChanged?.invoke(currentItems, isEmptyState)
    }
}
