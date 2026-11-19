package com.skul9x.doctruyen.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.skul9x.doctruyen.R
import com.skul9x.doctruyen.tts.AudioPreviewState

/**
 * ListAdapter displaying filtered voice options with DiffUtil optimizations,
 * selection radio buttons, category badges, and audition preview controls.
 */
class VoiceSelectorAdapter(
    private val onItemClick: (VoiceSelectorItem) -> Unit,
    private val onPreviewClick: (VoiceSelectorItem) -> Unit
) : ListAdapter<VoiceSelectorItem, VoiceSelectorAdapter.VoiceViewHolder>(VoiceSelectorDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VoiceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.dialog_item_voice, parent, false)
        return VoiceViewHolder(view, onItemClick, onPreviewClick)
    }

    override fun onBindViewHolder(holder: VoiceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onBindViewHolder(holder: VoiceViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
        } else {
            val item = getItem(position)
            for (payload in payloads) {
                when (payload) {
                    VoiceSelectorDiffCallback.ChangePayload.SELECTION -> {
                        holder.bindSelection(item.isSelected)
                    }
                    VoiceSelectorDiffCallback.ChangePayload.PREVIEW_STATE -> {
                        holder.bindPreviewState(item.previewState)
                    }
                    VoiceSelectorDiffCallback.ChangePayload.FULL -> {
                        holder.bindSelection(item.isSelected)
                        holder.bindPreviewState(item.previewState)
                    }
                    else -> holder.bind(item)
                }
            }
        }
    }

    class VoiceViewHolder(
        itemView: View,
        private val onItemClick: (VoiceSelectorItem) -> Unit,
        private val onPreviewClick: (VoiceSelectorItem) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val layoutVoiceItem: View = itemView.findViewById(R.id.layoutVoiceItem)
        private val rbVoiceSelected: RadioButton = itemView.findViewById(R.id.rbVoiceSelected)
        private val tvVoiceItemTitle: TextView = itemView.findViewById(R.id.tvVoiceItemTitle)
        private val tvVoiceBadges: TextView = itemView.findViewById(R.id.tvVoiceBadges)
        private val tvVoiceItemSubtitle: TextView = itemView.findViewById(R.id.tvVoiceItemSubtitle)
        private val btnVoicePreview: ImageButton = itemView.findViewById(R.id.btnVoicePreview)
        private val pbVoicePreviewLoading: ProgressBar = itemView.findViewById(R.id.pbVoicePreviewLoading)

        private var currentItem: VoiceSelectorItem? = null

        init {
            layoutVoiceItem.setOnClickListener {
                currentItem?.let { onItemClick(it) }
            }
            btnVoicePreview.setOnClickListener {
                currentItem?.let { onPreviewClick(it) }
            }
        }

        fun bind(item: VoiceSelectorItem) {
            currentItem = item
            tvVoiceItemTitle.text = item.voiceInfo.displayName

            val badges = item.voiceInfo.getFormattedBadges(bracketed = true)
            if (badges.isNotBlank()) {
                tvVoiceBadges.text = badges
                tvVoiceBadges.visibility = View.VISIBLE
            } else {
                tvVoiceBadges.visibility = View.GONE
            }

            if (item.voiceInfo.description.isNotBlank()) {
                tvVoiceItemSubtitle.text = item.voiceInfo.description
                tvVoiceItemSubtitle.visibility = View.VISIBLE
            } else {
                tvVoiceItemSubtitle.visibility = View.GONE
            }

            bindSelection(item.isSelected)
            bindPreviewState(item.previewState)
        }

        fun bindSelection(isSelected: Boolean) {
            rbVoiceSelected.isChecked = isSelected
            layoutVoiceItem.isSelected = isSelected
            layoutVoiceItem.isActivated = isSelected
        }

        fun bindPreviewState(previewState: AudioPreviewState) {
            val context = itemView.context
            when (previewState) {
                is AudioPreviewState.Loading -> {
                    btnVoicePreview.visibility = View.GONE
                    pbVoicePreviewLoading.visibility = View.VISIBLE
                }
                is AudioPreviewState.Playing -> {
                    pbVoicePreviewLoading.visibility = View.GONE
                    btnVoicePreview.visibility = View.VISIBLE
                    btnVoicePreview.setImageResource(R.drawable.ic_preview_stop)
                    btnVoicePreview.contentDescription = context.getString(R.string.voice_preview_stop)
                }
                else -> {
                    pbVoicePreviewLoading.visibility = View.GONE
                    btnVoicePreview.visibility = View.VISIBLE
                    btnVoicePreview.setImageResource(R.drawable.ic_preview_play)
                    btnVoicePreview.contentDescription = context.getString(R.string.voice_preview_play)
                }
            }
        }
    }
}
