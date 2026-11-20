package com.skul9x.doctruyen.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.skul9x.doctruyen.R
import com.skul9x.doctruyen.network.RetrofitClient
import com.skul9x.doctruyen.network.Story

/**
 * Adapter for displaying story cards in a grid
 * Features smooth animations and image loading
 */
class StoryAdapter(
    private val onStoryClick: (Story) -> Unit
) : ListAdapter<Story, StoryAdapter.StoryViewHolder>(StoryDiffCallback()) {

    private var lastAnimatedPosition = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_story, parent, false)
        return StoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: StoryViewHolder, position: Int) {
        val story = getItem(position)
        holder.bind(story)
        
        // Cancel lingering animations and reset alpha for recycled views
        holder.itemView.animate().cancel()
        holder.itemView.alpha = 1f

        // Apply hardware-accelerated fade-in animation only for new items
        if (position > lastAnimatedPosition) {
            holder.itemView.alpha = 0f
            holder.itemView.animate()
                .alpha(1f)
                .setDuration(300)
                .setStartDelay((position % 4) * 50L)
                .start()
            lastAnimatedPosition = position
        }
    }

    override fun onViewDetachedFromWindow(holder: StoryViewHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.itemView.animate().cancel()
        holder.itemView.clearAnimation()
    }

    fun resetAnimations() {
        lastAnimatedPosition = -1
    }
    
    override fun onViewRecycled(holder: StoryViewHolder) {
        super.onViewRecycled(holder)
        holder.clearImage()  // Cancel pending image loads for recycled views
    }

    inner class StoryViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val imgStory: ImageView = itemView.findViewById(R.id.imgStory)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvStoryTitle)

        fun bind(story: Story) {
            tvTitle.text = story.title

            // Build full image URL
            val imageUrl = if (story.image?.startsWith("http") == true) {
                story.image
            } else if (!story.image.isNullOrEmpty()) {
                "${RetrofitClient.BASE_URL}${story.image}"
            } else {
                null
            }

            val cookie = RetrofitClient.cookie

            // Load image with Glide using headers
            val glideUrl = if (imageUrl != null && cookie.isNotEmpty()) {
                com.skul9x.doctruyen.utils.AuthenticatedGlideUrl(
                    imageUrl,
                    com.bumptech.glide.load.model.LazyHeaders.Builder()
                        .addHeader("User-Agent", com.skul9x.doctruyen.network.HostingVerifier.USER_AGENT)
                        .addHeader("Cookie", cookie)
                        .addHeader("X-Requested-With", "com.skul9x.doctruyen")
                        .build()
                )
            } else if (imageUrl != null) {
                // Try loading without auth headers (for direct URLs)
                com.bumptech.glide.load.model.GlideUrl(imageUrl)
            } else null

            // Optimized Glide loading with priority and caching (zero per-bind listener allocations)
            Glide.with(itemView.context)
                .load(glideUrl)
                .priority(com.bumptech.glide.Priority.HIGH)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .timeout(15000)  // 15 second timeout
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .placeholder(R.drawable.placeholder_story)
                .error(R.drawable.placeholder_story)
                .centerCrop()
                .into(imgStory)

            // Click listener
            itemView.setOnClickListener {
                onStoryClick(story)
            }
        }
        
        fun clearImage() {
            Glide.with(itemView.context).clear(imgStory)
        }
    }

    class StoryDiffCallback : DiffUtil.ItemCallback<Story>() {
        override fun areItemsTheSame(oldItem: Story, newItem: Story): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Story, newItem: Story): Boolean {
            return oldItem == newItem
        }
    }
}
