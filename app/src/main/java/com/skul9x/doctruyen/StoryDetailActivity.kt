package com.skul9x.doctruyen

import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.skul9x.doctruyen.network.RetrofitClient
import com.skul9x.doctruyen.tts.TTSManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StoryDetailActivity : AppCompatActivity() {
    
    private lateinit var imgStory: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvContent: TextView
    private lateinit var tvTtsStatus: TextView
    private lateinit var btnBack: FloatingActionButton
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnStop: MaterialButton
    
    // Service Binding
    private var readingService: com.skul9x.doctruyen.service.ReadingService? = null
    private var isBound = false
    private var storyId: Int = 0
    private var storyContent: String = ""
    
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            // Safe cast - return early if cast fails
            val binder = service as? com.skul9x.doctruyen.service.ReadingService.ReadingBinder ?: return
            val boundService = binder.getService()
            readingService = boundService
            isBound = true
            
            // Connect to state updates
            // Capture service reference to avoid race condition
            boundService.setStateCallback { state ->
                runOnUiThread {
                    // Check if activity is still valid
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    
                    // Only sync state if this is the same story being read
                    val serviceStoryId = boundService.currentStoryId
                    if (serviceStoryId == storyId && serviceStoryId != 0) {
                        updateTTSUI(state)
                    } else if (state == TTSManager.TTSState.READY || 
                               state == TTSManager.TTSState.IDLE) {
                        // Service is ready but not reading this story, show initial state
                        updateTTSUI(TTSManager.TTSState.READY)
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            readingService = null
            isBound = false
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_story_detail)
        
        initViews()
        setupListeners()
        loadStoryFromIntent()
        
        // Start Service if not running (to ensure it's created)
        val intent = android.content.Intent(this, com.skul9x.doctruyen.service.ReadingService::class.java)
        startService(intent)
    }
    
    override fun onStart() {
        super.onStart()
        // Bind to service
        val intent = android.content.Intent(this, com.skul9x.doctruyen.service.ReadingService::class.java)
        bindService(intent, serviceConnection, android.content.Context.BIND_AUTO_CREATE)
    }
    
    override fun onStop() {
        super.onStop()
        if (isBound) {
            readingService?.removeStateCallback()
            unbindService(serviceConnection)
            isBound = false
        }
    }
    
    private fun initViews() {
        imgStory = findViewById(R.id.imgStoryDetail)
        tvTitle = findViewById(R.id.tvTitle)
        tvContent = findViewById(R.id.tvContent)
        tvTtsStatus = findViewById(R.id.tvTtsStatus)
        btnBack = findViewById(R.id.btnBack)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnStop = findViewById(R.id.btnStop)
    }
    
    private fun setupListeners() {
        btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        
        btnPlayPause.setOnClickListener {
            handlePlayPause()
        }
        
        btnStop.setOnClickListener {
            // Stop service playback - safe null check
            val service = readingService ?: return@setOnClickListener
            service.stopReading()
        }
    }
    
    private fun loadStoryFromIntent() {
        storyId = intent.getIntExtra("story_id", 0)
        val title = intent.getStringExtra("story_title") ?: ""
        val image = intent.getStringExtra("story_image")
        
        tvTitle.text = title
        
        // Load image
        val imageUrl = if (image?.startsWith("http") == true) {
            image
        } else if (!image.isNullOrEmpty()) {
            "${RetrofitClient.BASE_URL}$image"
        } else {
            null
        }
        
        // Load image with Glide using headers
        val glideUrl = if (imageUrl != null) {
            com.skul9x.doctruyen.utils.AuthenticatedGlideUrl(
                imageUrl,
                com.bumptech.glide.load.model.LazyHeaders.Builder()
                    .addHeader("User-Agent", com.skul9x.doctruyen.network.HostingVerifier.USER_AGENT)
                    .addHeader("Cookie", RetrofitClient.cookie)
                    .addHeader("X-Requested-With", "com.skul9x.doctruyen")
                    .build()
            )
        } else null

        // Check if activity is still valid before loading image
        if (!isFinishing && !isDestroyed) {
            Glide.with(this)
                .load(glideUrl)
                .placeholder(R.drawable.placeholder_story)
                .error(R.drawable.placeholder_story)
                .centerCrop()
                .into(imgStory)
        }
        
        // Load full story content
        loadStoryContent()
    }
    
    private fun loadStoryContent() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.getApiService(this@StoryDetailActivity).getStoryDetail(id = storyId)
                }
                
                if (response.status == "success") {
                    var rawContent = response.data.contentText ?: response.data.content ?: ""
                    
                    // Fix: Handle literal "\n" strings if they exist, and format line breaks
                    // First, unescape literal slash-n if present from some JSON encodings
                    rawContent = rawContent.replace("\\n", "\n")
                    
                    // If content doesn't have HTML tags but has newlines, convert them to <br> for HTML rendering
                    // OR if it's just text, TextView handles \n fine. 
                    // However, user specifically asked for "characters for new line" -> likely <br> or similar.
                    // Best approach: Convert newlines to <br> and treat as HTML to support bold/italic if any.
                    
                    // Check if it looks like HTML already
                    if (!rawContent.contains("<br") && !rawContent.contains("<p")) {
                         rawContent = rawContent.replace("\n", "<br>")
                    }
                    
                    storyContent = android.text.Html.fromHtml(rawContent, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                    
                    // Set text with HTML rendering
                    tvContent.text = android.text.Html.fromHtml(rawContent, android.text.Html.FROM_HTML_MODE_LEGACY)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this@StoryDetailActivity,
                    getString(R.string.error_loading),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun handlePlayPause() {
        // Safe null check - capture reference to avoid race condition
        val service = readingService ?: return
        if (!isBound) return
        
        val state = service.currentState 
        val serviceStoryId = service.currentStoryId
        
        when {
            // Currently playing this story - pause it
            state == TTSManager.TTSState.PLAYING && serviceStoryId == storyId -> {
                service.pauseReading()
            }
            // Paused on this story - resume it
            state == TTSManager.TTSState.PAUSED && serviceStoryId == storyId -> {
                service.resumeReading()
            }
            // Not reading this story or ready/idle - start reading this story
            else -> {
                if (storyContent.isNotEmpty()) {
                    val title = tvTitle.text.toString()
                    service.startReading(storyId, title, storyContent)
                } else {
                    Toast.makeText(this, getString(R.string.loading), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun updateTTSUI(state: TTSManager.TTSState) {
        when (state) {
            TTSManager.TTSState.IDLE, TTSManager.TTSState.INITIALIZING, TTSManager.TTSState.READY, TTSManager.TTSState.ERROR -> {
                // Button A: "Đọc truyện", Active
                btnPlayPause.text = getString(R.string.btn_read_story)
                btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
                btnPlayPause.isEnabled = true
                
                // Button B: "Dừng đọc", Disabled, Dimmed
                btnStop.text = getString(R.string.btn_stop_reading)
                btnStop.isEnabled = false
                btnStop.alpha = 0.5f // Dimmed
                
                tvTtsStatus.visibility = View.GONE
            }
            TTSManager.TTSState.PLAYING -> {
                // Button A: "Tạm dừng", Active
                btnPlayPause.text = getString(R.string.btn_pause)
                btnPlayPause.setIconResource(android.R.drawable.ic_media_pause)
                btnPlayPause.isEnabled = true
                
                // Button B: "Dừng đọc", Active, Bright
                btnStop.text = getString(R.string.btn_stop_reading)
                btnStop.isEnabled = true
                btnStop.alpha = 1.0f // Bright
                
                tvTtsStatus.text = getString(R.string.tts_reading)
                tvTtsStatus.visibility = View.VISIBLE
            }
            TTSManager.TTSState.PAUSED -> {
                // Button A: "Đọc tiếp", Active
                btnPlayPause.text = getString(R.string.btn_resume)
                btnPlayPause.setIconResource(android.R.drawable.ic_media_play)
                btnPlayPause.isEnabled = true
                
                // Button B: "Dừng đọc", Active, Bright
                btnStop.text = getString(R.string.btn_stop_reading)
                btnStop.isEnabled = true
                btnStop.alpha = 1.0f // Bright
                
                tvTtsStatus.text = getString(R.string.tts_paused)
                tvTtsStatus.visibility = View.VISIBLE
            }
        }
        
        // Show error toast if needed, but UI resets to Ready state visually
        if (state == TTSManager.TTSState.ERROR) {
            Toast.makeText(this, getString(R.string.tts_error), Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // No TTS shutdown here, handled by service
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
