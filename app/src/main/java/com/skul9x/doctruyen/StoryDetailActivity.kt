package com.skul9x.doctruyen

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.skul9x.doctruyen.network.RetrofitClient
import com.skul9x.doctruyen.tts.TTSManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType

class StoryDetailActivity : AppCompatActivity() {
    
    private val TAG = "StoryDetail"
    
    private lateinit var imgStory: ImageView
    private lateinit var tvTitle: TextView
    private lateinit var tvContent: TextView
    private lateinit var tvTtsStatus: TextView
    private lateinit var btnBack: FloatingActionButton
    private lateinit var btnPlayPause: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var loadingContainer: View
    private lateinit var errorContainer: View
    private lateinit var btnRetry: MaterialButton
    
    // Service Binding
    private var readingService: com.skul9x.doctruyen.service.ReadingService? = null
    private var isBound = false
    private var storyId: Int = 0
    private var storyContent: String = ""
    
    // Permission launcher for Android 13+ notification permission
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(
                this,
                "Cần quyền thông báo để điều khiển TTS từ notification",
                Toast.LENGTH_LONG
            ).show()
        }
    }
    
    private val serviceConnection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            DebugLogger.log(TAG, "✅ Service connected", LogType.INFO)
            
            // Safe cast - return early if cast fails
            val binder = service as? com.skul9x.doctruyen.service.ReadingService.ReadingBinder
            if (binder == null) {
                DebugLogger.logError(TAG, "❌ Service binder cast failed")
                return
            }
            
            val boundService = binder.getService()
            readingService = boundService
            isBound = true
            
            DebugLogger.log(TAG, "Service bound: isBound=$isBound, currentStoryId=$storyId", LogType.INFO)
            
            // Connect to state updates
            boundService.setStateCallback { state ->
                runOnUiThread {
                    // Check if activity is still valid
                    if (isFinishing || isDestroyed) {
                        DebugLogger.log(TAG, "⚠️ Activity finishing/destroyed, skipping state update", LogType.INFO)
                        return@runOnUiThread
                    }
                    
                    val serviceStoryId = boundService.currentStoryId
                    DebugLogger.log(TAG, "📡 State callback: state=$state, serviceStoryId=$serviceStoryId, thisStoryId=$storyId", LogType.INFO)
                    
                    // Only sync state if this is the same story being read
                    if (serviceStoryId == storyId && serviceStoryId != 0) {
                        DebugLogger.log(TAG, "✅ Updating UI for current story", LogType.INFO)
                        updateTTSUI(state)
                    } else if (state == TTSManager.TTSState.READY || 
                               state == TTSManager.TTSState.IDLE) {
                        DebugLogger.log(TAG, "🔄 Service ready/idle, showing initial state", LogType.INFO)
                        updateTTSUI(TTSManager.TTSState.READY)
                    } else {
                        DebugLogger.log(TAG, "⚠️ State mismatch: service reading story $serviceStoryId, viewing $storyId", LogType.INFO)
                    }
                }
            }
            
            // Connect to progress updates
            val progressBar = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressTts)
            boundService.setProgressCallback { progress ->
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        progressBar.progress = progress
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            DebugLogger.log(TAG, "❌ Service disconnected", LogType.INFO)
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
        
        // Request notification permission for Android 13+
        requestNotificationPermission()
        
        // Start Service if not running (to ensure it's created)
        val intent = android.content.Intent(this, com.skul9x.doctruyen.service.ReadingService::class.java)
        startService(intent)
    }
    
    /**
     * Request POST_NOTIFICATIONS permission for Android 13+ (API 33)
     * Required for foreground service notification controls
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(permission)
            }
        }
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
        loadingContainer = findViewById(R.id.loadingContainer)
        errorContainer = findViewById(R.id.errorContainer)
        btnRetry = findViewById(R.id.btnRetry)
    }
    
    private fun setupListeners() {
        btnBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        
        btnPlayPause.setOnClickListener {
            DebugLogger.log(TAG, "👆 btnPlayPause clicked: Text='${btnPlayPause.text}', Enabled=${btnPlayPause.isEnabled}, Alpha=${btnPlayPause.alpha}", LogType.INFO)
            handlePlayPause()
        }
        
        btnStop.setOnClickListener {
            DebugLogger.log(TAG, "👆 btnStop clicked: Text='${btnStop.text}', Enabled=${btnStop.isEnabled}, Alpha=${btnStop.alpha}", LogType.INFO)
            
            // Stop service playback - with user feedback if service not ready
            val service = readingService
            if (service == null || !isBound) {
                DebugLogger.logError(TAG, "❌ btnStop: Service not ready")
                Toast.makeText(this, "Dịch vụ đọc chưa sẵn sàng", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            DebugLogger.log(TAG, "⏹️ Action: STOP reading", LogType.INFO)
            service.stopReading()
        }
        
        btnRetry.setOnClickListener {
            loadStoryContent()
        }
    }
    
    private fun loadStoryFromIntent() {
        storyId = intent.getIntExtra("story_id", 0)
        val title = intent.getStringExtra("story_title") ?: ""
        val image = intent.getStringExtra("story_image")
        
        DebugLogger.log(TAG, "📖 Loading story: id=$storyId, title='$title'", LogType.INFO)
        
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
        DebugLogger.log(TAG, "📥 loadStoryContent: storyId=$storyId", LogType.REQUEST)
        
        // Show loading state
        showLoadingState()
        
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.getApiService(this@StoryDetailActivity).getStoryDetail(id = storyId)
                }
                
                if (response.status == "success") {
                    var rawContent = response.data.contentText ?: response.data.content ?: ""
                    
                    // Fix: Handle literal "\\n" strings if they exist, and format line breaks
                    rawContent = rawContent.replace("\\\\n", "\n")
                    
                    // Check if it looks like HTML already
                    if (!rawContent.contains("<br") && !rawContent.contains("<p")) {
                         rawContent = rawContent.replace("\n", "<br>")
                    }
                    
                    // SECURITY: Sanitize HTML to prevent XSS
                    val sanitizedContent = sanitizeHtml(rawContent)
                    
                    storyContent = android.text.Html.fromHtml(sanitizedContent, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                    
                    val preview = storyContent.take(50) + if (storyContent.length > 50) "..." else ""
                    DebugLogger.log(TAG, "✅ Content loaded: ${storyContent.length} chars, preview: '$preview'", LogType.RESPONSE)
                    
                    // Set text with HTML rendering
                    tvContent.text = android.text.Html.fromHtml(sanitizedContent, android.text.Html.FROM_HTML_MODE_LEGACY)
                    
                    // Show content
                    showContentState()
                } else {
                    showErrorState("Không thể tải nội dung truyện")
                }
            } catch (e: Exception) {
                if (e.message == "FreeNfChallenge") {
                    withContext(Dispatchers.Main) {
                         Toast.makeText(this@StoryDetailActivity, "Phiên hết hạn, đang xác thực lại...", Toast.LENGTH_SHORT).show()
                         RetrofitClient.clearCookie(this@StoryDetailActivity)
                         verifyHosting()
                    }
                    return@launch
                }
                showErrorState(getString(R.string.error_loading))
            }
        }
    }
    
    private fun verifyHosting() {
        val url = com.skul9x.doctruyen.utils.UserConfig.getBaseUrl(this)
        com.skul9x.doctruyen.network.HostingVerifier.verify(this, url) { success ->
            runOnUiThread {
                if (success) {
                    loadStoryContent()
                } else {
                    showErrorState("Xác thực thất bại, vui lòng thử lại")
                }
            }
        }
    }
    
    /**
     * Sanitize HTML content to prevent XSS attacks
     * Only allows safe tags: br, p, b, i, u, strong, em
     */
    private fun sanitizeHtml(html: String): String {
        // Remove script tags and their content
        var sanitized = html.replace(Regex("<script[^>]*>.*?</script>", RegexOption.IGNORE_CASE), "")
        // Remove event handlers (onclick, onerror, etc.)
        sanitized = sanitized.replace(Regex("\\s*on\\w+\\s*=\\s*[\"'][^\"']*[\"']", RegexOption.IGNORE_CASE), "")
        // Remove javascript: links
        sanitized = sanitized.replace(Regex("javascript:", RegexOption.IGNORE_CASE), "")
        // Remove iframe, object, embed tags
        sanitized = sanitized.replace(Regex("<(iframe|object|embed|form|input)[^>]*>.*?</(iframe|object|embed|form|input)>", RegexOption.IGNORE_CASE), "")
        sanitized = sanitized.replace(Regex("<(iframe|object|embed|form|input)[^>]*/>", RegexOption.IGNORE_CASE), "")
        return sanitized
    }
    
    private fun showLoadingState() {
        loadingContainer.visibility = View.VISIBLE
        errorContainer.visibility = View.GONE
        tvContent.visibility = View.GONE
    }
    
    private fun showContentState() {
        loadingContainer.visibility = View.GONE
        errorContainer.visibility = View.GONE
        tvContent.visibility = View.VISIBLE
    }
    
    private fun showErrorState(message: String) {
        loadingContainer.visibility = View.GONE
        errorContainer.visibility = View.VISIBLE
        tvContent.visibility = View.GONE
        findViewById<TextView>(R.id.tvErrorMessage).text = message
    }
    
    private fun handlePlayPause() {
        DebugLogger.log(TAG, "👆 btnPlayPause clicked", LogType.INFO)
        
        // Safe null check - with user feedback if service not ready
        val service = readingService
        if (service == null || !isBound) {
            DebugLogger.logError(TAG, "❌ Service not ready: service=${service != null}, isBound=$isBound")
            Toast.makeText(this, "Dịch vụ đọc chưa sẵn sàng, vui lòng thử lại", Toast.LENGTH_SHORT).show()
            return
        }
        
        val state = service.currentState 
        val serviceStoryId = service.currentStoryId
        val contentPreview = storyContent.take(50) + if (storyContent.length > 50) "..." else ""
        
        DebugLogger.log(TAG, """
            |🎮 handlePlayPause:
            |  - currentState: $state
            |  - serviceStoryId: $serviceStoryId
            |  - thisStoryId: $storyId  
            |  - storyContent.isEmpty: ${storyContent.isEmpty()}
            |  - contentPreview: '$contentPreview'
        """.trimMargin(), LogType.INFO)
        
        when {
            // Currently playing this story - pause it
            state == TTSManager.TTSState.PLAYING && serviceStoryId == storyId -> {
                DebugLogger.log(TAG, "⏸️ Action: PAUSE (playing this story)", LogType.INFO)
                service.pauseReading()
            }
            // Paused on this story - resume it
            state == TTSManager.TTSState.PAUSED && serviceStoryId == storyId -> {
                DebugLogger.log(TAG, "▶️ Action: RESUME (paused on this story)", LogType.INFO)
                service.resumeReading()
            }
            // Not reading this story or ready/idle - start reading this story
            else -> {
                if (storyContent.isNotEmpty()) {
                    val title = tvTitle.text.toString()
                    DebugLogger.log(TAG, "🔊 Action: START reading story $storyId '$title'", LogType.INFO)
                    service.startReading(storyId, title, storyContent)
                } else {
                    DebugLogger.logError(TAG, "⚠️ Cannot start: storyContent is EMPTY!")
                    Toast.makeText(this, getString(R.string.loading), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun updateTTSUI(state: TTSManager.TTSState) {
        DebugLogger.log(TAG, "🎨 updateTTSUI: state=$state", LogType.INFO)
        
        val progressTts = findViewById<com.google.android.material.progressindicator.LinearProgressIndicator>(R.id.progressTts)
        
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
                
                tvTtsStatus.visibility = View.INVISIBLE
                progressTts.visibility = View.GONE
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
                progressTts.visibility = View.VISIBLE
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
                progressTts.visibility = View.VISIBLE
            }
        }
        
        DebugLogger.log(TAG, """
            |  UI Updated:
            |  - btnPlayPause: Text='${btnPlayPause.text}', Icon=?, Enabled=${btnPlayPause.isEnabled}
            |  - btnStop: Text='${btnStop.text}', Enabled=${btnStop.isEnabled}, Alpha=${btnStop.alpha}
        """.trimMargin(), LogType.INFO)
        
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
