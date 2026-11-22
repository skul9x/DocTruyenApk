package com.skul9x.doctruyen.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import com.skul9x.doctruyen.MainActivity
import com.skul9x.doctruyen.R
import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.tts.TTSManagerFactory
import com.skul9x.doctruyen.utils.DebugLogger
import com.skul9x.doctruyen.utils.LogType
import com.skul9x.doctruyen.utils.UserConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

interface IWakeLock {
    fun acquire(timeout: Long? = null)
    fun release()
    val isHeld: Boolean
}

class AndroidWakeLock(private val wakeLock: PowerManager.WakeLock) : IWakeLock {
    override fun acquire(timeout: Long?) {
        if (timeout != null) {
            wakeLock.acquire(timeout)
        } else {
            wakeLock.acquire()
        }
    }

    override fun release() {
        if (wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    override val isHeld: Boolean
        get() = wakeLock.isHeld
}

interface IMediaSessionWrapper {
    fun setPlaybackState(state: Int, actions: Long)
    fun release()
    var isActive: Boolean
    val sessionToken: MediaSessionCompat.Token?
}

class AndroidMediaSession(private val mediaSession: MediaSessionCompat) : IMediaSessionWrapper {
    override fun setPlaybackState(state: Int, actions: Long) {
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                .setActions(actions)
                .build()
        )
    }

    override fun release() {
        mediaSession.release()
    }

    override var isActive: Boolean
        get() = mediaSession.isActive
        set(value) {
            mediaSession.isActive = value
        }

    override val sessionToken: MediaSessionCompat.Token
        get() = mediaSession.sessionToken
}

interface INotificationManagerWrapper {
    fun notify(id: Int, notification: Notification)
    fun cancel(id: Int)
}

class AndroidNotificationManagerWrapper(private val manager: NotificationManager) : INotificationManagerWrapper {
    override fun notify(id: Int, notification: Notification) {
        manager.notify(id, notification)
    }

    override fun cancel(id: Int) {
        manager.cancel(id)
    }
}

class ReadingService : Service() {

    companion object {
        const val CHANNEL_ID = "reading_channel"
        const val NOTIFICATION_ID = 1
        
        const val ACTION_PLAY = "action_play"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_STOP = "action_stop"
        const val ACTION_SWITCH_ENGINE = "action_switch_engine"
        const val ACTION_SWITCH_PRECISION = "com.skul9x.doctruyen.ACTION_SWITCH_PRECISION"
        const val ACTION_SWITCH_PREBUFFER_CHUNKS = "com.skul9x.doctruyen.ACTION_SWITCH_PREBUFFER_CHUNKS"

        const val EXTRA_VOICE_NAME = "extra_voice_name"
        const val EXTRA_ENGINE_TYPE = "extra_engine_type"
        const val EXTRA_PRECISION_TYPE = "precision_type"
        const val EXTRA_PREBUFFER_CHUNKS = "prebuffer_chunks"
    }

    var ttsManager: ITTSManager? = null
        internal set
    private val binder = ReadingBinder()
    val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // State callbacks for UI
    private var stateCallback: ((ITTSManager.TTSState) -> Unit)? = null
    private var progressCallback: ((Int) -> Unit)? = null
    var currentState = ITTSManager.TTSState.IDLE
        private set
        
    var currentStoryTitle: String = ""
        private set
    
    var currentStoryId: Int = 0
        private set

    var preparingStoryId: Int = 0
        private set

    // WakeLock for screen-off & doze mode execution
    var wakeLock: IWakeLock? = null
        internal set

    // MediaSession for lock screen controls & media session management
    var mediaSession: IMediaSessionWrapper? = null
        internal set

    var notificationManager: INotificationManagerWrapper? = null
        internal set

    private var lastNotifiedState: ITTSManager.TTSState? = null
    private var lastNotifiedTitle: String? = null

    inner class ReadingBinder : Binder() {
        fun getService(): ReadingService = this@ReadingService
    }

    override fun onCreate() {
        super.onCreate()
        initWakeLock()
        initMediaSession()
        initNotificationManager()
        createNotificationChannel()
        initTTS()
    }

    private fun initNotificationManager() {
        try {
            val rawManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (rawManager != null) {
                notificationManager = AndroidNotificationManagerWrapper(rawManager)
            }
        } catch (e: Throwable) {
            DebugLogger.logError("ReadingService", "Failed to initialize NotificationManager wrapper: ${e.message}", e)
        }
    }

    private fun initWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val rawLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DocTruyen:ReadingWakeLock")?.apply {
                setReferenceCounted(false)
            }
            if (rawLock != null) {
                wakeLock = AndroidWakeLock(rawLock)
            }
        } catch (e: Throwable) {
            DebugLogger.logError("ReadingService", "Failed to create WakeLock: ${e.message}", e)
        }
    }

    fun acquireWakeLock() {
        try {
            wakeLock?.let {
                if (!it.isHeld) {
                    it.acquire(10 * 60 * 1000L) // 10 minutes safety timeout
                    DebugLogger.log("ReadingService", "🔒 WakeLock acquired", LogType.INFO)
                }
            }
        } catch (e: Throwable) {
            DebugLogger.logError("ReadingService", "Error acquiring WakeLock: ${e.message}", e)
        }
    }

    fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    DebugLogger.log("ReadingService", "🔓 WakeLock released", LogType.INFO)
                }
            }
        } catch (e: Throwable) {
            DebugLogger.logError("ReadingService", "Error releasing WakeLock: ${e.message}", e)
        }
    }

    private fun initMediaSession() {
        try {
            val rawSession = MediaSessionCompat(this, "DocTruyenMediaSession").apply {
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onPlay() {
                        resumeReading()
                    }

                    override fun onPause() {
                        pauseReading()
                    }

                    override fun onStop() {
                        stopReading()
                    }
                })
                isActive = true
            }
            mediaSession = AndroidMediaSession(rawSession)
            updateMediaSessionState(ITTSManager.TTSState.IDLE)
        } catch (e: Throwable) {
            DebugLogger.logError("ReadingService", "Failed to initialize MediaSessionCompat: ${e.message}", e)
        }
    }

    fun updateMediaSessionState(state: ITTSManager.TTSState) {
        val playbackState = when (state) {
            ITTSManager.TTSState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            ITTSManager.TTSState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            ITTSManager.TTSState.INITIALIZING -> PlaybackStateCompat.STATE_BUFFERING
            ITTSManager.TTSState.READY, ITTSManager.TTSState.IDLE -> PlaybackStateCompat.STATE_STOPPED
            ITTSManager.TTSState.ERROR -> PlaybackStateCompat.STATE_ERROR
        }

        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP

        try {
            mediaSession?.setPlaybackState(playbackState, actions)
        } catch (e: Throwable) {
            DebugLogger.logError("ReadingService", "Failed to set MediaSession PlaybackState: ${e.message}", e)
        }
    }

    var ttsManagerFactory: ((Context, (ITTSManager.TTSState) -> Unit, ((Int) -> Unit)?, ITTSManager.EngineType?) -> ITTSManager)? = null
        internal set

    internal fun handleStateChange(state: ITTSManager.TTSState) {
        DebugLogger.log("ReadingService", "📡 TTSManager callback: state=$state, previousState=$currentState", LogType.INFO)
        val previousState = currentState
        val isStateChanged = previousState != state
        currentState = state
        
        if (isStateChanged) {
            updateMediaSessionState(state)

            // Manage WakeLock based on TTS state
            if (state == ITTSManager.TTSState.PLAYING) {
                acquireWakeLock()
            } else if (state == ITTSManager.TTSState.PAUSED ||
                state == ITTSManager.TTSState.READY ||
                state == ITTSManager.TTSState.IDLE ||
                state == ITTSManager.TTSState.ERROR) {
                releaseWakeLock()
            }

            // Only update notification for relevant state transitions
            if (state == ITTSManager.TTSState.PLAYING || state == ITTSManager.TTSState.PAUSED) {
                updateNotification(state)
            } else if (state == ITTSManager.TTSState.READY || state == ITTSManager.TTSState.IDLE) {
                // Determine if we finished playing naturally
                if (previousState == ITTSManager.TTSState.PLAYING) {
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } catch (e: Throwable) {
                        DebugLogger.logError("ReadingService", "Failed to stopForeground: ${e.message}", e)
                    }
                } else {
                    try {
                        val manager = notificationManager ?: (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.let { AndroidNotificationManagerWrapper(it) }
                        manager?.cancel(NOTIFICATION_ID)
                    } catch (e: Throwable) {
                        DebugLogger.logError("ReadingService", "Failed to cancel notification: ${e.message}", e)
                    }
                }
                currentStoryId = 0
                preparingStoryId = 0
                lastNotifiedState = null
                lastNotifiedTitle = null
                // Reset progress when stopped/ready
                progressCallback?.invoke(0)
            } else if (state == ITTSManager.TTSState.ERROR) {
                try {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } catch (e: Throwable) {
                    DebugLogger.logError("ReadingService", "Failed to stopForeground: ${e.message}", e)
                }
            }
            
            stateCallback?.invoke(state)
        }
    }

    fun initTTS(forceEngineType: ITTSManager.EngineType? = null) {
        val current = ttsManager
        if (current != null) {
            ttsManager = null
            current.stop()
            serviceScope.launch(Dispatchers.IO) {
                current.shutdown()
            }
        }

        DebugLogger.log("ReadingService", "🔧 initTTS: Creating TTSManager via TTSManagerFactory", LogType.INFO)
        val customFactory = ttsManagerFactory
        ttsManager = if (customFactory != null) {
            customFactory(
                this,
                { handleStateChange(it) },
                { progress -> progressCallback?.invoke(progress) },
                forceEngineType
            )
        } else {
            TTSManagerFactory.create(
                context = this,
                forceEngineType = forceEngineType,
                onStateChange = { handleStateChange(it) },
                onProgressChange = { progress ->
                    progressCallback?.invoke(progress)
                }
            )
        }
    }

    fun switchEngine(engineType: ITTSManager.EngineType) {
        UserConfig.setTtsEngine(this, engineType)
        initTTS(engineType)
    }

    fun switchPrecision(precision: String) {
        UserConfig.setTtsPrecision(this, precision)
        val mgr = ttsManager
        if (mgr is com.skul9x.doctruyen.tts.vieneu.VieNeuTTSManager) {
            DebugLogger.log("ReadingService", "🔄 Switching VieNeu precision to $precision", LogType.INFO)
            mgr.setPrecision(precision)
        } else {
            DebugLogger.log("ReadingService", "ℹ️ Precision saved as $precision (active engine is not VieNeu)", LogType.INFO)
        }
    }

    fun switchPrebufferChunks(chunks: Int) {
        val clamped = chunks.coerceIn(UserConfig.MIN_PREBUFFER_CHUNKS, UserConfig.MAX_PREBUFFER_CHUNKS)
        UserConfig.setPrebufferChunks(this, clamped)
        val mgr = ttsManager
        if (mgr is com.skul9x.doctruyen.tts.vieneu.VieNeuTTSManager) {
            DebugLogger.log("ReadingService", "🔄 Setting VieNeu prebuffer chunks to $clamped", LogType.INFO)
            mgr.setPrebufferChunks(clamped)
        } else {
            DebugLogger.log("ReadingService", "ℹ️ Prebuffer chunks saved as $clamped (active engine is not VieNeu)", LogType.INFO)
        }
    }

    fun setPrebufferChunks(chunks: Int) {
        switchPrebufferChunks(chunks)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resumeReading()
            ACTION_PAUSE -> pauseReading()
            ACTION_STOP -> stopReading()
            ACTION_SWITCH_ENGINE -> {
                val engineName = intent.getStringExtra(EXTRA_ENGINE_TYPE)
                val engineType = if (engineName != null) {
                    try { ITTSManager.EngineType.valueOf(engineName) } catch (e: Throwable) { null }
                } else null
                if (engineType != null) {
                    switchEngine(engineType)
                }
            }
            ACTION_SWITCH_PRECISION -> {
                val precision = intent.getStringExtra(EXTRA_PRECISION_TYPE) ?: UserConfig.getTtsPrecision(this)
                switchPrecision(precision)
            }
            ACTION_SWITCH_PREBUFFER_CHUNKS -> {
                val chunks = intent.getIntExtra(EXTRA_PREBUFFER_CHUNKS, UserConfig.DEFAULT_PREBUFFER_CHUNKS)
                switchPrebufferChunks(chunks)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun setStateCallback(callback: (ITTSManager.TTSState) -> Unit) {
        stateCallback = callback
        // Immediately notify current state
        callback(currentState)
    }
    
    fun setProgressCallback(callback: (Int) -> Unit) {
        progressCallback = callback
    }
    
    fun removeStateCallback() {
        stateCallback = null
        progressCallback = null
    }

    fun prepareStory(storyId: Int, title: String, content: String, voice: String? = null) {
        // Guard: Skip if already playing or preparing the exact same story
        val currentState = ttsManager?.currentState
        if ((currentStoryId == storyId || preparingStoryId == storyId) && 
            (currentState == ITTSManager.TTSState.PLAYING || 
             currentState == ITTSManager.TTSState.PAUSED ||
             currentState == ITTSManager.TTSState.READY)) {
            DebugLogger.log("ReadingService", 
                "⏭️ prepareStory SKIPPED: already active on storyId=$storyId (state=$currentState)", 
                LogType.INFO)
            return
        }
        preparingStoryId = storyId

        val isGoogle = ttsManager?.engineType == ITTSManager.EngineType.GOOGLE_TTS
        val freshVoice = voice
            ?: (if (isGoogle) UserConfig.getGoogleTtsVoice(this) else UserConfig.getTtsVoice(this))
            ?: ttsManager?.currentVoiceName
            ?: ""
        DebugLogger.log("ReadingService", "🔥 prepareStory: storyId=$storyId, title='$title', voice='$freshVoice'", LogType.INFO)
        if (freshVoice.isNotEmpty()) {
            ttsManager?.setVoice(freshVoice)
        }
        ttsManager?.prepareStory(storyId, title, content, voice = freshVoice)
    }

    fun cancelPrewarm() {
        preparingStoryId = 0
        ttsManager?.cancelPrewarm()
    }

    fun startReading(storyId: Int, title: String, content: String, voice: String? = null) {
        preparingStoryId = 0
        val freshVoice = voice ?: (if (ttsManager?.engineType == ITTSManager.EngineType.GOOGLE_TTS) UserConfig.getGoogleTtsVoice(this) else UserConfig.getTtsVoice(this)) ?: ttsManager?.currentVoiceName ?: ""
        DebugLogger.log("ReadingService", "🔊 startReading: storyId=$storyId (prev=$currentStoryId), title='$title', contentLen=${content.length}, voice='$freshVoice'", LogType.INFO)
        
        // If switching from an existing active story to a new one, immediately stop/abort audio pipeline
        if (currentStoryId != 0 && currentStoryId != storyId) {
            DebugLogger.log("ReadingService", "🔄 Story switched from $currentStoryId to $storyId. Aborting previous playback.", LogType.INFO)
            ttsManager?.stop()
        }
        
        currentStoryId = storyId
        currentStoryTitle = title
        if (freshVoice.isNotEmpty()) {
            ttsManager?.setVoice(freshVoice)
        }
        // Use speakWithTitlePause for natural pause between title and content
        ttsManager?.speakWithTitlePause(title, content, storyId = storyId)
        lastNotifiedState = ITTSManager.TTSState.PLAYING
        lastNotifiedTitle = title
        try {
            val notification = notificationBuilder?.invoke(ITTSManager.TTSState.PLAYING) ?: buildNotification(ITTSManager.TTSState.PLAYING)
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Throwable) {
            DebugLogger.logError("ReadingService", "Failed to startForeground: ${e.message}", e)
        }
    }

    fun pauseReading() {
        ttsManager?.pause()
        releaseWakeLock()
        try {
            updateNotification(ITTSManager.TTSState.PAUSED)
        } catch (e: Throwable) {
            DebugLogger.logError("ReadingService", "Failed to updateNotification: ${e.message}", e)
        }
    }

    fun resumeReading() {
        ttsManager?.resume()
        acquireWakeLock()
        try {
            updateNotification(ITTSManager.TTSState.PLAYING)
        } catch (e: Throwable) {
            DebugLogger.logError("ReadingService", "Failed to updateNotification: ${e.message}", e)
        }
    }

    fun stopReading() {
        ttsManager?.stop()
        releaseWakeLock()
        currentStoryId = 0
        preparingStoryId = 0
        currentStoryTitle = ""
        lastNotifiedState = null
        lastNotifiedTitle = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Throwable) {
            DebugLogger.logError("ReadingService", "Failed to stopForeground: ${e.message}", e)
        }
    }
    
    fun shutdown() {
        stopReading()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reading Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    var notificationBuilder: ((ITTSManager.TTSState) -> Notification)? = null
        internal set

    private fun updateNotification(state: ITTSManager.TTSState) {
        if (state == ITTSManager.TTSState.PLAYING || state == ITTSManager.TTSState.PAUSED) {
            if (lastNotifiedState == state && lastNotifiedTitle == currentStoryTitle) {
                return
            }
            lastNotifiedState = state
            lastNotifiedTitle = currentStoryTitle
            val notification = notificationBuilder?.invoke(state) ?: buildNotification(state)
            val manager = notificationManager ?: (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.let { AndroidNotificationManagerWrapper(it) }
            manager?.notify(NOTIFICATION_ID, notification)
        } else {
            lastNotifiedState = null
            lastNotifiedTitle = null
            val manager = notificationManager ?: (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.let { AndroidNotificationManagerWrapper(it) }
            manager?.cancel(NOTIFICATION_ID)
        }
    }

    private fun buildNotification(state: ITTSManager.TTSState): Notification {
        val isPlaying = state == ITTSManager.TTSState.PLAYING

        val playPauseIntent = Intent(this, ReadingService::class.java).apply {
            action = if (isPlaying) ACTION_PAUSE else ACTION_PLAY
        }
        val playPausePendingIntent = PendingIntent.getService(
            this, 0, playPauseIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, ReadingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (isPlaying) "Tạm dừng" else "Tiếp tục"

        // Open App Intent
        val contentIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(0, 1)

        mediaSession?.sessionToken?.let { token ->
            mediaStyle.setMediaSession(token)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Đang đọc: $currentStoryTitle")
            .setContentText(if (isPlaying) "Đang phát..." else "Đã tạm dừng")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .addAction(playPauseIcon, playPauseText, playPausePendingIntent)
            .addAction(android.R.drawable.ic_delete, "Dừng đọc", stopPendingIntent)
            .setStyle(mediaStyle)
            .build()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        DebugLogger.log("ReadingService", "⚠️ onTrimMemory received: level=$level", LogType.INFO)
        when (level) {
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_UI_HIDDEN,
            TRIM_MEMORY_COMPLETE,
            TRIM_MEMORY_MODERATE -> {
                // If not actively reading or preparing, prune caches
                if (currentState != ITTSManager.TTSState.PLAYING && currentState != ITTSManager.TTSState.INITIALIZING) {
                    DebugLogger.log("ReadingService", "🧹 Pruning idle prewarm caches and G2P cache on memory pressure", LogType.INFO)
                    com.skul9x.doctruyen.tts.vieneu.PrewarmedChunkCache.clear()
                    com.skul9x.doctruyen.tts.vieneu.SeaG2P.clearCache()
                }
            }
        }
    }

    /**
     * Called when app is removed from Recent Apps (swipe to kill)
     * Stop TTS and clean up resources
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        releaseWakeLock()
        cancelPrewarm()
        com.skul9x.doctruyen.tts.vieneu.PrewarmedChunkCache.clear()
        com.skul9x.doctruyen.tts.vieneu.SeaG2P.clearCache()
        val mgr = ttsManager
        ttsManager = null
        mgr?.stop()
        serviceScope.launch(NonCancellable) {
            mgr?.shutdown()
        }
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        cancelPrewarm()
        com.skul9x.doctruyen.tts.vieneu.PrewarmedChunkCache.clear()
        com.skul9x.doctruyen.tts.vieneu.SeaG2P.clearCache()
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        val mgr = ttsManager
        ttsManager = null
        mgr?.stop()
        serviceScope.launch(NonCancellable) {
            mgr?.shutdown()
        }
        serviceScope.cancel()
        super.onDestroy()
    }
}
