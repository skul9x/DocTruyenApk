package com.skul9x.doctruyen.service

import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.skul9x.doctruyen.tts.ITTSManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MediaSessionStateTest {

    private class FakeMediaSessionWrapper : IMediaSessionWrapper {
        var lastState: Int = -1
        var lastActions: Long = 0L
        var isReleased = false
        override var isActive: Boolean = false
        override val sessionToken: MediaSessionCompat.Token? = null

        override fun setPlaybackState(state: Int, actions: Long) {
            lastState = state
            lastActions = actions
        }

        override fun release() {
            isReleased = true
        }
    }

    private lateinit var fakeMediaSession: FakeMediaSessionWrapper
    private lateinit var service: ReadingService

    @Before
    fun setUp() {
        fakeMediaSession = FakeMediaSessionWrapper()
        service = ReadingService().apply {
            mediaSession = fakeMediaSession
        }
    }

    @Test
    fun testMediaSessionStateMapping() {
        // 1. Test PLAYING state
        service.updateMediaSessionState(ITTSManager.TTSState.PLAYING)
        assertEquals(PlaybackStateCompat.STATE_PLAYING, fakeMediaSession.lastState)
        assertTrue((fakeMediaSession.lastActions and PlaybackStateCompat.ACTION_PLAY_PAUSE) != 0L)

        // 2. Test PAUSED state
        service.updateMediaSessionState(ITTSManager.TTSState.PAUSED)
        assertEquals(PlaybackStateCompat.STATE_PAUSED, fakeMediaSession.lastState)

        // 3. Test INITIALIZING state
        service.updateMediaSessionState(ITTSManager.TTSState.INITIALIZING)
        assertEquals(PlaybackStateCompat.STATE_BUFFERING, fakeMediaSession.lastState)

        // 4. Test READY / IDLE state
        service.updateMediaSessionState(ITTSManager.TTSState.READY)
        assertEquals(PlaybackStateCompat.STATE_STOPPED, fakeMediaSession.lastState)

        // 5. Test ERROR state
        service.updateMediaSessionState(ITTSManager.TTSState.ERROR)
        assertEquals(PlaybackStateCompat.STATE_ERROR, fakeMediaSession.lastState)
    }

    @Test
    fun testMediaSessionRelease_onServiceDestroy() {
        assertFalse(fakeMediaSession.isReleased)
        service.onDestroy()
        assertTrue(fakeMediaSession.isReleased)
    }
}
