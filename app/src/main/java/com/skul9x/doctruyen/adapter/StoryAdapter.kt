package com.skul9x.doctruyen.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
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
        
        // Apply animation only for new items
        if (position > lastAnimatedPosition) {
            val animation = AnimationUtils.loadAnimation(
                holder.itemView.context,
                android.R.anim.fade_in
            )
            animation.duration = 300
            animation.startOffset = (position % 4) * 50L
            holder.itemView.startAnimation(animation)
            lastAnimatedPosition = position
        }
    }

    override fun onViewDetachedFromWindow(holder: StoryViewHolder) {
        super.onViewDetachedFromWindow(holder)
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
            
            // Debug log
            val cookie = RetrofitClient.cookie
            com.skul9x.doctruyen.utils.DebugLogger.log("IMAGE", "Loading: $imageUrl | Cookie: ${cookie.take(30)}...")

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

            // Optimized Glide loading with priority and caching
            Glide.with(itemView.context)
                .load(glideUrl)
                .priority(com.bumptech.glide.Priority.HIGH)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .timeout(15000)  // 15 second timeout
                .transition(DrawableTransitionOptions.withCrossFade(150))
                .placeholder(R.drawable.placeholder_story)
                .error(R.drawable.placeholder_story)
                .centerCrop()
                .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(
                        e: com.bumptech.glide.load.engine.GlideException?,
                        model: Any?,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        com.skul9x.doctruyen.utils.DebugLogger.log("IMAGE_ERROR", "Failed: $imageUrl | Error: ${e?.message}")
                        return false // Let Glide handle error placeholder
                    }

                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        model: Any,
                        target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                        dataSource: com.bumptech.glide.load.DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        com.skul9x.doctruyen.utils.DebugLogger.log("IMAGE_OK", "Loaded: $imageUrl from $dataSource")
                        return false
                    }
                })
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
