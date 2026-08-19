package com.skul9x.doctruyen.tts

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Text-to-Speech Manager using Android's built-in TextToSpeech engine.
 * Retained as a reliable fallback manager for devices where ONNX neural TTS is unavailable.
 */
class AndroidTTSManager(
    context: Context,
    private val onStateChange: (TTSState) -> Unit,
    private val onProgressChange: ((Int) -> Unit)? = null // Progress 0-100
) {
    
    enum class TTSState {
        IDLE,
        INITIALIZING,
        READY,
        PLAYING,
        PAUSED,
        ERROR
    }
    
    // Use applicationContext to prevent Activity memory leak
    private val appContext: Context = context.applicationContext
    
    private var tts: TextToSpeech? = null
    private var currentState = TTSState.IDLE
    private var pendingText: String? = null
    private var pendingTitle: String? = null
    
    // Full text storage for pause/resume
    private var fullText: String = ""
    private var storyTitle: String = ""
    
    // Character position tracking (used by onRangeStart API 26+)
    private var currentCharPosition: Int = 0
    
    // Chunk-based tracking for older devices (API < 26)
    private var textChunks: List<String> = emptyList()
    private var currentChunkIndex = 0
    
    // Track if we're reading title or content
    private var isTitleRead: Boolean = false
    
    // Flag to prevent onDone from resetting state when pausing
    private var isPausedByUser: Boolean = false
    
    init {
        initTTS()
    }
    
    private fun notifyStateChange(state: TTSState) {
        onStateChange(state)
    }
    
    private fun initTTS() {
        currentState = TTSState.INITIALIZING
        notifyStateChange(currentState)
        
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Try Vietnamese first, fallback to default
                val vietnameseLocale = Locale("vi", "VN")
                val result = tts?.setLanguage(vietnameseLocale)
                
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // Fallback to English if Vietnamese not available
                    tts?.setLanguage(Locale.US)
                }
                
                // Configure TTS from settings
                val speed = com.skul9x.doctruyen.utils.UserConfig.getTtsSpeed(appContext)
                val pitch = com.skul9x.doctruyen.utils.UserConfig.getTtsPitch(appContext)
                
                tts?.setSpeechRate(speed)
                tts?.setPitch(pitch)

                // Set saved voice if available
                val savedVoice = com.skul9x.doctruyen.utils.UserConfig.getTtsVoice(appContext)
                if (savedVoice != null) {
                    val voice = tts?.voices?.find { it.name == savedVoice }
                    if (voice != null) tts?.voice = voice
                }
                
                setupListener()
                currentState = TTSState.READY
                notifyStateChange(currentState)
                
                // If there's pending text, play it
                if (pendingTitle != null && pendingText != null) {
                    speakWithTitlePause(pendingTitle!!, pendingText!!)
                    pendingTitle = null
                    pendingText = null
                } else if (pendingText != null) {
                    speak(pendingText!!)
                    pendingText = null
                }
            } else {
                currentState = TTSState.ERROR
                notifyStateChange(currentState)
            }
        }
    }
    
    private fun setupListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                currentState = TTSState.PLAYING
                notifyStateChange(currentState)
            }
            
            override fun onDone(utteranceId: String?) {
                // IMPORTANT: Don't reset to READY if user paused
                if (isPausedByUser) {
                    isPausedByUser = false
                    return
                }
                
                when {
                    // After title, play 1s silence then continue to content
                    utteranceId == "title_chunk" -> {
                        isTitleRead = true
                        playSilenceAndStartContent()
                    }
                    // After silence, content is about to play
                    utteranceId == "silence" -> {
                        // Content already queued via QUEUE_ADD
                    }
                    // Check if we have more chunks to play (for long content)
                    currentChunkIndex < textChunks.size - 1 -> {
                        currentChunkIndex++
                        speakContentChunk(currentChunkIndex)
                    }
                    // All done - reading finished
                    else -> {
                        resetReadingState()
                        currentState = TTSState.READY
                        notifyStateChange(currentState)
                    }
                }
            }
            
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                super.onRangeStart(utteranceId, start, end, frame)
                
                if (utteranceId?.startsWith("content_") == true || utteranceId?.startsWith("resume_") == true) {
                    val chunkOffset = if (currentChunkIndex > 0) {
                        textChunks.take(currentChunkIndex).sumOf { it.length }
                    } else {
                        0
                    }
                    currentCharPosition = chunkOffset + start
                    
                    if (fullText.isNotEmpty()) {
                        val progress = ((currentCharPosition.toFloat() / fullText.length) * 100).toInt().coerceIn(0, 100)
                        onProgressChange?.invoke(progress)
                    }
                }
            }
            
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                currentState = TTSState.ERROR
                notifyStateChange(currentState)
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                currentState = TTSState.ERROR
                notifyStateChange(currentState)
            }
        })
    }
    
    fun speak(text: String) {
        if (currentState == TTSState.INITIALIZING) {
            pendingText = text
            return
        }
        
        currentState = TTSState.PLAYING
        notifyStateChange(currentState)
        
        fullText = text
        storyTitle = ""
        currentCharPosition = 0
        isTitleRead = true
        
        textChunks = splitIntoChunks(text, 3500)
        currentChunkIndex = 0
        
        if (textChunks.isNotEmpty()) {
            speakContentChunk(0)
        }
    }
    
    fun speakWithTitlePause(title: String, content: String) {
        if (currentState == TTSState.INITIALIZING) {
            pendingTitle = title
            pendingText = content
            return
        }
        
        currentState = TTSState.PLAYING
        notifyStateChange(currentState)
        
        storyTitle = title
        fullText = content
        currentCharPosition = 0
        isTitleRead = false
        
        textChunks = splitIntoChunks(content, 3500)
        currentChunkIndex = 0
        
        tts?.speak(
            title,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "title_chunk"
        )
    }
    
    private fun speakContentChunk(index: Int) {
        if (index < textChunks.size) {
            val chunk = textChunks[index]
            tts?.speak(
                chunk,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "content_$index"
            )
        }
    }
    
    private fun playSilenceAndStartContent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.playSilentUtterance(1000L, TextToSpeech.QUEUE_ADD, "silence")
        }
        
        if (textChunks.isNotEmpty()) {
            tts?.speak(
                textChunks[0],
                TextToSpeech.QUEUE_ADD,
                null,
                "content_0"
            )
        }
    }
    
    private fun splitIntoChunks(text: String, maxLength: Int): List<String> {
        if (text.isEmpty()) return emptyList()
        
        val chunks = mutableListOf<String>()
        var remaining = text
        
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLength) {
                chunks.add(remaining)
                break
            }
            
            var breakPoint = remaining.lastIndexOf(". ", maxLength)
            if (breakPoint <= 0) {
                breakPoint = remaining.lastIndexOf(" ", maxLength)
            }
            if (breakPoint <= 0) {
                breakPoint = maxLength
            }
            
            val endIndex = minOf(breakPoint + 1, remaining.length)
            chunks.add(remaining.substring(0, endIndex))
            
            remaining = if (endIndex < remaining.length) {
                remaining.substring(endIndex).trim()
            } else {
                ""
            }
        }
        
        return chunks
    }
    
    fun pause() {
        if (currentState == TTSState.PLAYING) {
            isPausedByUser = true
            currentState = TTSState.PAUSED
            tts?.stop()
            notifyStateChange(currentState)
        }
    }
    
    fun resume() {
        if (currentState != TTSState.PAUSED || fullText.isEmpty()) {
            return
        }
        
        if (!isTitleRead && storyTitle.isNotEmpty()) {
            speakWithTitlePause(storyTitle, fullText)
            return
        }
        
        val safePosition = currentCharPosition.coerceIn(0, fullText.length)
        val adjustedPosition = findNaturalBreakPoint(safePosition)
        
        if (adjustedPosition >= fullText.length) {
            resetReadingState()
            currentState = TTSState.READY
            notifyStateChange(currentState)
            return
        }
        
        val remainingText = fullText.substring(adjustedPosition)
        textChunks = splitIntoChunks(remainingText, 3500)
        currentChunkIndex = 0
        
        if (textChunks.isNotEmpty()) {
            tts?.speak(
                textChunks[0],
                TextToSpeech.QUEUE_FLUSH,
                null,
                "resume_0"
            )
        }
    }
    
    private fun findNaturalBreakPoint(position: Int): Int {
        if (position <= 0) return 0
        if (position >= fullText.length) return fullText.length
        
        val searchStart = maxOf(0, position - 100)
        val searchRange = fullText.substring(searchStart, position)
        
        val sentenceEnd = maxOf(
            searchRange.lastIndexOf(". "),
            searchRange.lastIndexOf("? "),
            searchRange.lastIndexOf("! "),
            searchRange.lastIndexOf(".\n"),
            searchRange.lastIndexOf("?\n"),
            searchRange.lastIndexOf("!\n")
        )
        
        if (sentenceEnd > 0) {
            return searchStart + sentenceEnd + 2
        }
        
        val spacePos = searchRange.lastIndexOf(" ")
        if (spacePos > 0) {
            return searchStart + spacePos + 1
        }
        
        return position
    }
    
    private fun resetReadingState() {
        fullText = ""
        storyTitle = ""
        currentCharPosition = 0
        currentChunkIndex = 0
        textChunks = emptyList()
        isTitleRead = false
    }
    
    fun stop() {
        isPausedByUser = false
        tts?.stop()
        resetReadingState()
        currentState = TTSState.READY
        notifyStateChange(currentState)
    }
    
    fun isPlaying(): Boolean = currentState == TTSState.PLAYING
    
    fun isPaused(): Boolean = currentState == TTSState.PAUSED
    
    fun getVoices(): List<android.speech.tts.Voice> {
        val allVoices = tts?.voices ?: return emptyList()
        val viVoices = allVoices.filter { it.locale.language == "vi" }
        return if (viVoices.isNotEmpty()) viVoices else allVoices.toList()
    }
    
    fun setVoice(voiceName: String) {
        val voice = tts?.voices?.find { it.name == voiceName }
        if (voice != null) {
            tts?.voice = voice
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        currentState = TTSState.IDLE
    }
}
