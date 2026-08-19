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
import com.skul9x.doctruyen.tts.vieneu.VieNeuTTSManager

class ReadingService : Service() {

    companion object {
        const val CHANNEL_ID = "reading_channel"
        const val NOTIFICATION_ID = 1
        
        const val ACTION_PLAY = "action_play"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_STOP = "action_stop"

        const val EXTRA_VOICE_NAME = "extra_voice_name"
    }

    var ttsManager: VieNeuTTSManager? = null
        internal set
    private val binder = ReadingBinder()
    
    // State callbacks for UI
    private var stateCallback: ((VieNeuTTSManager.TTSState) -> Unit)? = null
    private var progressCallback: ((Int) -> Unit)? = null
    var currentState = VieNeuTTSManager.TTSState.IDLE
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
        android.util.Log.d("ReadingService", "🔧 initTTS: Creating VieNeuTTSManager")
        ttsManager = VieNeuTTSManager(
            context = this,
            onStateChange = { state ->
                android.util.Log.d("ReadingService", "📡 VieNeuTTSManager callback: state=$state, previousState=$currentState")
                val previousState = currentState
                currentState = state
                
                // Only update notification for relevant states
                if (state == VieNeuTTSManager.TTSState.PLAYING || state == VieNeuTTSManager.TTSState.PAUSED) {
                    updateNotification(state)
                } else if (state == VieNeuTTSManager.TTSState.READY || state == VieNeuTTSManager.TTSState.IDLE) {
                    // Determine if we finished playing naturally
                    if (previousState == VieNeuTTSManager.TTSState.PLAYING) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                         val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                         manager.cancel(NOTIFICATION_ID)
                    }
                    // Reset progress when stopped/ready
                    progressCallback?.invoke(0)
                } else if (state == VieNeuTTSManager.TTSState.ERROR) {
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

    fun setStateCallback(callback: (VieNeuTTSManager.TTSState) -> Unit) {
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
        android.util.Log.d("ReadingService", "🔥 prepareStory: storyId=$storyId, title='$title'")
        if (!voice.isNullOrEmpty()) {
            ttsManager?.setVoice(voice)
        }
        val currentVoice = voice ?: ttsManager?.voiceName ?: "Trúc Ly"
        ttsManager?.prepareStory(storyId, title, content, voice = currentVoice)
    }

    fun cancelPrewarm() {
        ttsManager?.cancelPrewarm()
    }

    fun startReading(storyId: Int, title: String, content: String, voice: String? = null) {
        android.util.Log.d("ReadingService", "🔊 startReading: storyId=$storyId, title='$title', contentLen=${content.length}")
        currentStoryId = storyId
        currentStoryTitle = title
        if (!voice.isNullOrEmpty()) {
            ttsManager?.setVoice(voice)
        }
        // Use speakWithTitlePause for natural 1s pause between title and content
        android.util.Log.d("ReadingService", "📤 Calling ttsManager.speakWithTitlePause...")
        ttsManager?.speakWithTitlePause(title, content, storyId = storyId)
        android.util.Log.d("ReadingService", "✅ After speakWithTitlePause, currentState=$currentState")
        startForeground(NOTIFICATION_ID, buildNotification(VieNeuTTSManager.TTSState.PLAYING))
    }

    fun pauseReading() {
        ttsManager?.pause()
        updateNotification(VieNeuTTSManager.TTSState.PAUSED)
    }

    fun resumeReading() {
        ttsManager?.resume()
        updateNotification(VieNeuTTSManager.TTSState.PLAYING)
    }

    fun stopReading() {
        ttsManager?.stop()
        currentStoryId = 0
        currentStoryTitle = ""
        stopForeground(STOP_FOREGROUND_REMOVE)
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
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(state: VieNeuTTSManager.TTSState) {
        if (state == VieNeuTTSManager.TTSState.PLAYING || state == VieNeuTTSManager.TTSState.PAUSED) {
            val notification = buildNotification(state)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } else {
             val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
             manager.cancel(NOTIFICATION_ID)
        }
    }

    private fun buildNotification(state: VieNeuTTSManager.TTSState): Notification {
        val isPlaying = state == VieNeuTTSManager.TTSState.PLAYING

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

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Đang đọc: $currentStoryTitle")
            .setContentText(if (isPlaying) "Đang phát..." else "Đã tạm dừng")
            .setSmallIcon(R.mipmap.ic_launcher)
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
        ttsManager?.stop()
        ttsManager?.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        ttsManager?.shutdown()
        super.onDestroy()
    }
}
