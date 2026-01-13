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
import androidx.core.app.NotificationCompat
import com.skul9x.doctruyen.MainActivity
import com.skul9x.doctruyen.R
import com.skul9x.doctruyen.tts.TTSManager

class ReadingService : Service() {

    companion object {
        const val CHANNEL_ID = "reading_channel"
        const val NOTIFICATION_ID = 1
        
        const val ACTION_PLAY = "action_play"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_STOP = "action_stop"
    }

    private var ttsManager: TTSManager? = null
    private val binder = ReadingBinder()
    
    // State callbacks for UI
    private var stateCallback: ((TTSManager.TTSState) -> Unit)? = null
    private var progressCallback: ((Int) -> Unit)? = null
    var currentState = TTSManager.TTSState.IDLE
        private set
        
    var currentStoryTitle: String = ""
        private set
    
    var currentStoryId: Int = 0
        private set

    inner class ReadingBinder : Binder() {
        fun getService(): ReadingService = this@ReadingService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initTTS()
    }

    private fun initTTS() {
        android.util.Log.d("ReadingService", "🔧 initTTS: Creating TTSManager")
        ttsManager = TTSManager(
            context = this,
            onStateChange = { state ->
                android.util.Log.d("ReadingService", "📡 TTSManager callback: state=$state, previousState=$currentState")
                val previousState = currentState
                currentState = state
                
                // Only update notification for relevant states
                if (state == TTSManager.TTSState.PLAYING || state == TTSManager.TTSState.PAUSED) {
                    updateNotification(state)
                } else if (state == TTSManager.TTSState.READY || state == TTSManager.TTSState.IDLE) {
                    // Determine if we finished playing naturally
                    if (previousState == TTSManager.TTSState.PLAYING) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                         // Just remove notification if it exists (e.g. from Stop button)
                         // But wait, stopReading already calls stopForeground.
                         // The issue is updateNotification might have reposted it.
                         // Here we DON'T repost notification.
                         val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                         manager.cancel(NOTIFICATION_ID)
                    }
                    // Reset progress when stopped/ready
                    progressCallback?.invoke(0)
                } else if (state == TTSManager.TTSState.ERROR) {
                     stopForeground(STOP_FOREGROUND_REMOVE)
                }
                
                android.util.Log.d("ReadingService", "📤 Forwarding to stateCallback: callback=${stateCallback != null}, state=$state")
                stateCallback?.invoke(state)
            },
            onProgressChange = { progress ->
                progressCallback?.invoke(progress)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> resumeReading()
            ACTION_PAUSE -> pauseReading()
            ACTION_STOP -> stopReading()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    fun setStateCallback(callback: (TTSManager.TTSState) -> Unit) {
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

    fun startReading(storyId: Int, title: String, content: String) {
        android.util.Log.d("ReadingService", "🔊 startReading: storyId=$storyId, title='$title', contentLen=${content.length}")
        currentStoryId = storyId
        currentStoryTitle = title
        // Use speakWithTitlePause for natural 1s pause between title and content
        android.util.Log.d("ReadingService", "📤 Calling ttsManager.speakWithTitlePause...")
        ttsManager?.speakWithTitlePause(title, content)
        android.util.Log.d("ReadingService", "✅ After speakWithTitlePause, currentState=$currentState")
        startForeground(NOTIFICATION_ID, buildNotification(TTSManager.TTSState.PLAYING))
    }

    fun pauseReading() {
        ttsManager?.pause()
        updateNotification(TTSManager.TTSState.PAUSED)
    }

    fun resumeReading() {
        ttsManager?.resume()
        updateNotification(TTSManager.TTSState.PLAYING)
    }

    fun stopReading() {
        ttsManager?.stop()
        currentStoryId = 0
        currentStoryTitle = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Don't call stopSelf() - keep service alive for next play action
        // Service will be properly destroyed when Activity unbinds
    }
    
    // Allow Activity to manually shutdown on destroy if needed
    fun shutdown() {
        stopReading()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Reading Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(state: TTSManager.TTSState) {
        if (state == TTSManager.TTSState.PLAYING || state == TTSManager.TTSState.PAUSED) {
            val notification = buildNotification(state)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } else {
             // For other states (READY, STOPPED), do not show notification
             val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
             manager.cancel(NOTIFICATION_ID)
        }
    }

    private fun buildNotification(state: TTSManager.TTSState): Notification {
        val isPlaying = state == TTSManager.TTSState.PLAYING

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
        
        // Use R.drawable.ic_media_pause/play if available, or fallback to android system icons
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseText = if (isPlaying) "Tạm dừng" else "Tiếp tục"

        // Open App Intent
        val contentIntent = Intent(this, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            this, 0, contentIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Đang đọc: $currentStoryTitle")
            .setContentText(if (isPlaying) "Đang phát..." else "Đã tạm dừng")
            .setSmallIcon(R.mipmap.ic_launcher) // Use app icon
            .setContentIntent(contentPendingIntent)
            .setOngoing(isPlaying)
            .addAction(playPauseIcon, playPauseText, playPausePendingIntent)
            .addAction(android.R.drawable.ic_delete, "Dừng đọc", stopPendingIntent)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1))
            .build()
    }

    /**
     * Called when app is removed from Recent Apps (swipe to kill)
     * Stop TTS and clean up resources
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Stop TTS reading
        ttsManager?.stop()
        ttsManager?.shutdown()
        // Remove notification and stop service
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ttsManager?.shutdown()
        super.onDestroy()
    }
}
