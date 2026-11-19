package com.skul9x.doctruyen.ui

import androidx.recyclerview.widget.DiffUtil
import com.skul9x.doctruyen.tts.AudioPreviewState
import com.skul9x.doctruyen.tts.VoiceInfo

/**
 * UI model representing a selectable voice row with metadata badges and audio preview state.
 */
data class VoiceSelectorItem(
    val voiceInfo: VoiceInfo,
    val isSelected: Boolean = false,
    val previewState: AudioPreviewState = AudioPreviewState.Idle
) {
    val id: String
        get() = voiceInfo.id

    val displayName: String
        get() = voiceInfo.displayName

    val description: String
        get() = voiceInfo.description
}

/**
 * DiffUtil callback optimizing updates for voice items.
 * Uses payload updates for selection toggles and audio preview state changes
 * to prevent unnecessary full view rebinds and UI flickering.
 */
class VoiceSelectorDiffCallback : DiffUtil.ItemCallback<VoiceSelectorItem>() {

    enum class ChangePayload {
        SELECTION,
        PREVIEW_STATE,
        FULL
    }

    override fun areItemsTheSame(oldItem: VoiceSelectorItem, newItem: VoiceSelectorItem): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: VoiceSelectorItem, newItem: VoiceSelectorItem): Boolean {
        return oldItem == newItem
    }

    override fun getChangePayload(oldItem: VoiceSelectorItem, newItem: VoiceSelectorItem): Any? {
        val selectionChanged = oldItem.isSelected != newItem.isSelected
        val previewChanged = oldItem.previewState != newItem.previewState

        return when {
            selectionChanged && previewChanged -> ChangePayload.FULL
            selectionChanged -> ChangePayload.SELECTION
            previewChanged -> ChangePayload.PREVIEW_STATE
            else -> null
        }
    }
}
