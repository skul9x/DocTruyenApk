package com.skul9x.doctruyen.tts

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Text-to-Speech Manager for reading stories aloud
 * Handles Vietnamese language pronunciation
 * 
 * Uses onRangeStart callback (API 26+) to track character position
 * for accurate pause/resume functionality.
 * 
 * IMPORTANT: Call shutdown() when done to release TTS resources.
 */
class TTSManager(
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
                // tts.stop() from pause() triggers onDone, we must not override PAUSED state
                if (isPausedByUser) {
                    isPausedByUser = false
                    return // Don't change state, stay PAUSED
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
            
            /**
             * IMPORTANT: onRangeStart tracks the current reading position (API 26+)
             * 'start' is the character index of the word being spoken
             */
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                super.onRangeStart(utteranceId, start, end, frame)
                
                // Only track position for content chunks (not title)
                if (utteranceId?.startsWith("content_") == true || utteranceId?.startsWith("resume_") == true) {
                    // Calculate absolute position in fullText
                    // For chunked text, add offset of previous chunks
                    val chunkOffset = if (currentChunkIndex > 0) {
                        textChunks.take(currentChunkIndex).sumOf { it.length }
                    } else {
                        0
                    }
                    currentCharPosition = chunkOffset + start
                    
                    // Report progress as percentage
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
    
    /**
     * Start speaking the given text
     */
    /**
     * Start speaking the given text
     */
    fun speak(text: String) {
        if (currentState == TTSState.INITIALIZING) {
            pendingText = text
            return
        }
        
        // Optimistically set playing state
        currentState = TTSState.PLAYING
        notifyStateChange(currentState)
        
        fullText = text
        storyTitle = ""
        currentCharPosition = 0
        isTitleRead = true // No title, go straight to content
        
        // Split text into chunks (max 3500 chars per chunk for TTS limit)
        textChunks = splitIntoChunks(text, 3500)
        currentChunkIndex = 0
        
        if (textChunks.isNotEmpty()) {
            speakContentChunk(0)
        }
    }
    
    /**
     * Speak title first, pause, then content
     */
    fun speakWithTitlePause(title: String, content: String) {
        if (currentState == TTSState.INITIALIZING) {
            pendingTitle = title
            pendingText = content
            return
        }
        
        // Optimistically set playing state
        currentState = TTSState.PLAYING
        notifyStateChange(currentState)
        
        storyTitle = title
        fullText = content
        currentCharPosition = 0
        isTitleRead = false
        
        // Split content into chunks
        textChunks = splitIntoChunks(content, 3500)
        currentChunkIndex = 0
        
        // Start with title
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
        // Play 1 second of silence before content
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.playSilentUtterance(1000L, TextToSpeech.QUEUE_ADD, "silence")
        }
        
        // Queue first content chunk
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
            
            // Find a good break point (sentence end or space)
            var breakPoint = remaining.lastIndexOf(". ", maxLength)
            if (breakPoint <= 0) {
                breakPoint = remaining.lastIndexOf(" ", maxLength)
            }
            if (breakPoint <= 0) {
                breakPoint = maxLength
            }
            
            // Ensure breakPoint doesn't exceed remaining length
            val endIndex = minOf(breakPoint + 1, remaining.length)
            chunks.add(remaining.substring(0, endIndex))
            
            // Safely get remaining text
            remaining = if (endIndex < remaining.length) {
                remaining.substring(endIndex).trim()
            } else {
                ""
            }
        }
        
        return chunks
    }
    
    /**
     * Pause the current speech
     * Saves current character position for resume
     */
    fun pause() {
        if (currentState == TTSState.PLAYING) {
            // Set flag BEFORE tts.stop() to prevent onDone from resetting state
            isPausedByUser = true
            currentState = TTSState.PAUSED
            tts?.stop()
            notifyStateChange(currentState)
            // currentCharPosition is already updated by onRangeStart
        }
    }
    
    /**
     * Resume from paused state
     * Uses substring to continue from saved character position
     */
    fun resume() {
        if (currentState != TTSState.PAUSED || fullText.isEmpty()) {
            return
        }
        
        // If title wasn't read yet, restart from title
        if (!isTitleRead && storyTitle.isNotEmpty()) {
            speakWithTitlePause(storyTitle, fullText)
            return
        }
        
        // Get remaining text from current position
        val safePosition = currentCharPosition.coerceIn(0, fullText.length)
        
        // Find a good starting point (beginning of word/sentence)
        val adjustedPosition = findNaturalBreakPoint(safePosition)
        
        if (adjustedPosition >= fullText.length) {
            // Already finished, reset and notify ready
            resetReadingState()
            currentState = TTSState.READY
            notifyStateChange(currentState)
            return
        }
        
        // Get remaining text
        val remainingText = fullText.substring(adjustedPosition)
        
        // Re-chunk the remaining text
        textChunks = splitIntoChunks(remainingText, 3500)
        currentChunkIndex = 0
        
        // Update base position for tracking
        // Note: onRangeStart will report positions relative to the new text
        // We need to offset them by adjustedPosition
        
        if (textChunks.isNotEmpty()) {
            tts?.speak(
                textChunks[0],
                TextToSpeech.QUEUE_FLUSH,
                null,
                "resume_0"
            )
        }
    }
    
    /**
     * Find a natural break point near the given position
     * Looks for sentence end (. ? !) or word boundary (space)
     */
    private fun findNaturalBreakPoint(position: Int): Int {
        if (position <= 0) return 0
        if (position >= fullText.length) return fullText.length
        
        // Look backwards for a sentence ending (within 100 chars)
        val searchStart = maxOf(0, position - 100)
        val searchRange = fullText.substring(searchStart, position)
        
        // Try to find sentence end
        val sentenceEnd = maxOf(
            searchRange.lastIndexOf(". "),
            searchRange.lastIndexOf("? "),
            searchRange.lastIndexOf("! "),
            searchRange.lastIndexOf(".\n"),
            searchRange.lastIndexOf("?\n"),
            searchRange.lastIndexOf("!\n")
        )
        
        if (sentenceEnd > 0) {
            // Found sentence end, return position after it
            return searchStart + sentenceEnd + 2
        }
        
        // Fallback: find word boundary (space)
        val spacePos = searchRange.lastIndexOf(" ")
        if (spacePos > 0) {
            return searchStart + spacePos + 1
        }
        
        // No good break point found, use original position
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
    
    /**
     * Stop the speech completely
     */
    fun stop() {
        // Reset pause flag to prevent stuck state
        isPausedByUser = false
        tts?.stop()
        resetReadingState()
        currentState = TTSState.READY
        notifyStateChange(currentState)
    }
    
    /**
     * Check if TTS is currently playing
     */
    fun isPlaying(): Boolean = currentState == TTSState.PLAYING
    
    /**
     * Check if TTS is paused
     */
    fun isPaused(): Boolean = currentState == TTSState.PAUSED
    
    /**
     * Get list of available voices (prefer Vietnamese)
     */
    fun getVoices(): List<android.speech.tts.Voice> {
        val allVoices = tts?.voices ?: return emptyList()
        // Filter for Vietnamese voices first
        val viVoices = allVoices.filter { it.locale.language == "vi" }
        return if (viVoices.isNotEmpty()) viVoices else allVoices.toList()
    }
    
    /**
     * Set specific voice by name
     */
    fun setVoice(voiceName: String) {
        val voice = tts?.voices?.find { it.name == voiceName }
        if (voice != null) {
            tts?.voice = voice
        }
    }

    /**
     * Release TTS resources
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        currentState = TTSState.IDLE
    }
}
