package com.skul9x.doctruyen.service

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WakeLockLifecycleTest {

    private class FakeWakeLock : IWakeLock {
        override var isHeld: Boolean = false
        var acquireCount = 0
        var releaseCount = 0

        override fun acquire(timeout: Long?) {
            isHeld = true
            acquireCount++
        }

        override fun release() {
            isHeld = false
            releaseCount++
        }
    }

    private lateinit var fakeWakeLock: FakeWakeLock
    private lateinit var service: ReadingService

    @Before
    fun setUp() {
        fakeWakeLock = FakeWakeLock()
        service = ReadingService().apply {
            wakeLock = fakeWakeLock
        }
    }

    @Test
    fun testWakeLockAcquire_onPlayingState() {
        assertFalse("WakeLock should not be held initially", fakeWakeLock.isHeld)

        // Acquire lock
        service.acquireWakeLock()
        assertTrue("WakeLock should be held when acquired", fakeWakeLock.isHeld)
        assertEquals(1, fakeWakeLock.acquireCount)

        // Calling acquire again should not double-acquire if already held
        service.acquireWakeLock()
        assertEquals(1, fakeWakeLock.acquireCount)
    }

    @Test
    fun testWakeLockRelease_onPauseStopIdle() {
        service.acquireWakeLock()
        assertTrue(fakeWakeLock.isHeld)

        // Release on pause
        service.releaseWakeLock()
        assertFalse("WakeLock should be released on pause", fakeWakeLock.isHeld)
        assertEquals(1, fakeWakeLock.releaseCount)

        // Calling release when not held should be safe
        service.releaseWakeLock()
        assertEquals(1, fakeWakeLock.releaseCount)
    }

    @Test
    fun testWakeLockRelease_onServiceShutdown() {
        service.acquireWakeLock()
        assertTrue(fakeWakeLock.isHeld)

        // Destroy / shutdown
        service.onDestroy()
        assertFalse("WakeLock must be released after service onDestroy", fakeWakeLock.isHeld)
    }
}
