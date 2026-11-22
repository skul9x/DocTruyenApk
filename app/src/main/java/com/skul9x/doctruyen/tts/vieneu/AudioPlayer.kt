package com.skul9x.doctruyen.tts.vieneu

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Shared dispatchers for audio playback hardware isolation.
 */
object AudioDispatchers {
    /**
     * Dedicated single-thread audio dispatcher with MAX_PRIORITY thread.
     * Prevents hardware AudioTrack blocking writes from monopolizing Dispatchers.Default compute pool.
     */
    val AudioTrackDispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "VieNeu-AudioTrack-Thread").apply {
                isDaemon = true
                priority = Thread.MAX_PRIORITY
            }
        }.asCoroutineDispatcher()
    }
}

/**
 * Low-latency audio player using Android's AudioTrack API in streaming mode.
 * Supports 48 kHz mono 16-bit PCM playback.
 */
open class AudioPlayer(
    val sampleRate: Int = 48000,
    val channelConfig: Int = AudioFormat.CHANNEL_OUT_MONO,
    val encoding: Int = AudioFormat.ENCODING_PCM_16BIT,
    val audioDispatcher: CoroutineDispatcher = AudioDispatchers.AudioTrackDispatcher
) {

    private val lock = Any()
    private val pauseLock = Any()
    private var pauseGateJob: CompletableJob = Job().apply { complete() }
    private var audioTrack: AudioTrack? = null

    private var needsReinit = false
    @Volatile
    private var _isPaused: Boolean = false

    val isInitialized: Boolean
        get() = synchronized(lock) {
            try {
                !needsReinit && audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED
            } catch (e: Throwable) {
                false
            }
        }

    val isPlaying: Boolean
        get() = synchronized(lock) {
            try {
                audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING
            } catch (e: Throwable) {
                false
            }
        }

    open val isPaused: Boolean
        get() = synchronized(lock) {
            _isPaused || (audioTrack?.playState == AudioTrack.PLAYSTATE_PAUSED)
        }

    /**
     * Initializes the underlying AudioTrack with a buffer size >= 2 * minBufferSize.
     */
    open fun init() {
        synchronized(lock) {
            if (audioTrack != null && !needsReinit) {
                return
            }

            if (needsReinit) {
                try {
                    audioTrack?.release()
                } catch (e: Throwable) {
                    Log.w(TAG, "Failed to release previous AudioTrack on re-init: ${e.message}")
                }
                audioTrack = null
                needsReinit = false
            }

            try {
                val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
                val bufferSize = if (minBufferSize > 0) maxOf(minBufferSize * 2, sampleRate * 2) else sampleRate * 2

                val attrBuilder = AudioAttributes.Builder()
                attrBuilder.setUsage(AudioAttributes.USAGE_MEDIA)
                attrBuilder.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                val attributes = attrBuilder.build()

                val formatBuilder = AudioFormat.Builder()
                formatBuilder.setSampleRate(sampleRate)
                formatBuilder.setChannelMask(channelConfig)
                formatBuilder.setEncoding(encoding)
                val format = formatBuilder.build()

                val trackBuilder = AudioTrack.Builder()
                if (attributes != null) trackBuilder.setAudioAttributes(attributes)
                if (format != null) trackBuilder.setAudioFormat(format)
                trackBuilder.setBufferSizeInBytes(bufferSize)
                trackBuilder.setTransferMode(AudioTrack.MODE_STREAM)
                audioTrack = trackBuilder.build()
            } catch (e: Throwable) {
                Log.w(TAG, "AudioTrack initialization skipped or failed: ${e.message}")
            }
        }
    }

    /**
     * Resets the AudioPlayer by releasing the existing AudioTrack and creating a fresh one.
     */
    open fun reset() {
        synchronized(lock) {
            release()
            init()
        }
    }

    /**
     * Writes 16-bit PCM audio samples to the AudioTrack asynchronously on dedicated audioDispatcher.
     * Breaks writes into small frames (100ms–200ms slices) with cooperative cancellation checks so pause responds
     * with sub-second precision and resume does not replay already heard audio.
     */
    open suspend fun writeSuspended(pcm16: ShortArray, offset: Int = 0, size: Int = pcm16.size): Int =
        withContext(audioDispatcher) {
            val sliceSize = (sampleRate * 0.1).toInt().coerceAtLeast(1024)
            var totalWritten = 0
            while (totalWritten < size && isActive) {
                // If player is paused, cooperatively suspend slice writing until play() or stop()
                if (isPaused) {
                    val gateToJoin = synchronized(pauseLock) {
                        if (!pauseGateJob.isCompleted) pauseGateJob else null
                    }
                    gateToJoin?.join()
                    if (!isActive) break
                }

                val toWrite = minOf(sliceSize, size - totalWritten)
                val written = write(pcm16, offset + totalWritten, toWrite)
                if (written <= 0) {
                    break
                }
                totalWritten += written
            }
            totalWritten
        }

    /**
     * Writes 16-bit PCM audio samples to the AudioTrack.
     * Blocks if the buffer is full until all samples are written or track is stopped.
     */
    open fun write(pcm16: ShortArray, offset: Int = 0, size: Int = pcm16.size): Int {
        val track: AudioTrack?
        synchronized(lock) {
            track = audioTrack
        }
        if (track == null) {
            return 0
        }

        try {
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                return 0
            }

            var totalWritten = 0
            while (totalWritten < size) {
                val count = track.write(
                    pcm16,
                    offset + totalWritten,
                    size - totalWritten,
                    AudioTrack.WRITE_BLOCKING
                )
                if (count <= 0) {
                    if (count < 0) Log.w(TAG, "AudioTrack write error: $count")
                    break
                }
                totalWritten += count
            }
            return totalWritten
        } catch (e: Throwable) {
            Log.w(TAG, "AudioTrack write failed", e)
            return 0
        }
    }

    /**
     * Starts audio playback.
     */
    open fun play() {
        synchronized(lock) {
            _isPaused = false
            synchronized(pauseLock) {
                if (!pauseGateJob.isCompleted) {
                    pauseGateJob.complete()
                }
            }
            try {
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.play()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start AudioTrack playback", e)
            }
        }
    }

    /**
     * Pauses audio playback without dropping queued audio.
     */
    open fun pause() {
        synchronized(lock) {
            _isPaused = true
            synchronized(pauseLock) {
                if (pauseGateJob.isCompleted) {
                    pauseGateJob = Job()
                }
            }
            try {
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        audioTrack?.pause()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to pause AudioTrack", e)
            }
        }
    }

    /**
     * Stops audio playback, flushes buffered audio, and marks player for re-initialization.
     */
    open fun stop() {
        synchronized(lock) {
            _isPaused = false
            synchronized(pauseLock) {
                if (!pauseGateJob.isCompleted) {
                    pauseGateJob.complete()
                }
            }
            try {
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    if (audioTrack?.playState != AudioTrack.PLAYSTATE_STOPPED) {
                        audioTrack?.stop()
                    }
                    audioTrack?.flush()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to stop AudioTrack", e)
            } finally {
                needsReinit = true
            }
        }
    }

    /**
     * Flushes currently buffered audio.
     */
    open fun flush() {
        synchronized(lock) {
            try {
                if (audioTrack?.state == AudioTrack.STATE_INITIALIZED) {
                    audioTrack?.flush()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to flush AudioTrack", e)
            }
        }
    }

    /**
     * Adjusts the volume / gain of the player (0.0f = silent, 1.0f = full volume).
     */
    open fun setVolume(volume: Float) {
        synchronized(lock) {
            val clamped = volume.coerceIn(0f, 1f)
            try {
                audioTrack?.setVolume(clamped)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to set AudioTrack volume", e)
            }
        }
    }

    /**
     * Releases the AudioTrack and native resources.
     */
    open fun release() {
        synchronized(lock) {
            try {
                audioTrack?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED) {
                        if (it.playState != AudioTrack.PLAYSTATE_STOPPED) {
                            it.stop()
                        }
                        it.flush()
                    }
                    it.release()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to release AudioTrack", e)
            } finally {
                audioTrack = null
            }
        }
    }

    companion object {
        private const val TAG = "AudioPlayer"
    }
}
