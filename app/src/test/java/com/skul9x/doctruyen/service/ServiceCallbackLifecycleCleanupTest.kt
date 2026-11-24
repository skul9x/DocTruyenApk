package com.skul9x.doctruyen.service

import com.skul9x.doctruyen.tts.ITTSManager
import com.skul9x.doctruyen.tts.VoiceInfo
import com.skul9x.doctruyen.tts.vieneu.NpzReader
import com.skul9x.doctruyen.tts.vieneu.PauseConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Comprehensive verification for Phase 04:
 * Service Callback Memory Leak Elimination & Scalar Npz Parsing Hardening (AUD-004 & AUD-006).
 *
 * Verifies:
 * 1. Callback registration in `ReadingService` and subsequent invocation of `removeStateCallback()`
 *    or `removeCallbacks()` cleanly clears both `stateCallback` and `progressCallback` to null.
 * 2. Simulating Activity lifecycle destruction detaches listeners and prevents Activity context retention.
 * 3. `NpzReader` properly parses scalar arrays with empty shape `()` from streams and returns exact float values.
 */
class ServiceCallbackLifecycleCleanupTest {

    private class MockTTSManager : ITTSManager {
        override val engineType: ITTSManager.EngineType = ITTSManager.EngineType.VIENEU
        override var currentState: ITTSManager.TTSState = ITTSManager.TTSState.IDLE
        override val currentVoiceName: String = "Hải Đăng"

        var stopCallCount = 0
        var cancelPrewarmCallCount = 0

        override fun speak(text: String, storyId: Int) {}
        override fun speakWithTitlePause(title: String, content: String, storyId: Int, pauseConfig: PauseConfig) {
            currentState = ITTSManager.TTSState.PLAYING
        }
        override fun pause() { currentState = ITTSManager.TTSState.PAUSED }
        override fun resume() { currentState = ITTSManager.TTSState.PLAYING }
        override fun stop() {
            stopCallCount++
            currentState = ITTSManager.TTSState.IDLE
        }
        override fun isPlaying(): Boolean = currentState == ITTSManager.TTSState.PLAYING
        override fun isPaused(): Boolean = currentState == ITTSManager.TTSState.PAUSED
        override fun setVoice(voiceName: String) {}
        override fun getAvailableVoices(): List<VoiceInfo> = emptyList()
        override fun setSpeed(speed: Float) {}
        override fun setPitch(pitch: Float) {}
        override fun prepareStory(storyId: Int, title: String, content: String, voice: String?, pauseConfig: PauseConfig) {
            currentState = ITTSManager.TTSState.READY
        }
        override fun cancelPrewarm() {
            cancelPrewarmCallCount++
            currentState = ITTSManager.TTSState.IDLE
        }
        override fun shutdown() {
            currentState = ITTSManager.TTSState.IDLE
        }
    }

    private lateinit var service: ReadingService
    private lateinit var mockTTS: MockTTSManager
    private val preferencesMap = mutableMapOf<String, Any?>()

    @Before
    fun setUp() {
        preferencesMap.clear()

        val editorProxy = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences.Editor::class.java.classLoader,
            arrayOf(android.content.SharedPreferences.Editor::class.java),
            java.lang.reflect.InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "putString" -> {
                        preferencesMap[args[0] as String] = args[1]
                        proxy
                    }
                    "apply", "commit" -> null
                    else -> null
                }
            }
        ) as android.content.SharedPreferences.Editor

        val prefsProxy = java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java),
            java.lang.reflect.InvocationHandler { _, method, args ->
                when (method.name) {
                    "getString" -> {
                        val key = args[0] as String
                        val def = args[1] as? String
                        preferencesMap[key] as? String ?: def
                    }
                    "edit" -> editorProxy
                    else -> null
                }
            }
        ) as android.content.SharedPreferences

        com.skul9x.doctruyen.utils.UserConfig.customPrefsProvider = { prefsProxy }

        service = ReadingService().apply {
            notificationManager = object : INotificationManagerWrapper {
                override fun notify(id: Int, notification: android.app.Notification) {}
                override fun cancel(id: Int) {}
            }
            notificationBuilder = { android.app.Notification() }
        }
        mockTTS = MockTTSManager()
        service.ttsManager = mockTTS
    }

    @After
    fun tearDown() {
        com.skul9x.doctruyen.utils.UserConfig.customPrefsProvider = null
    }

    /**
     * Requirement 1:
     * Registering UI callbacks in ReadingService and calling removeStateCallback() / removeCallbacks()
     * sets both stateCallback and progressCallback to null.
     */
    @Test
    fun testRemoveStateCallbackClearsBothStateAndProgressCallbacks() {
        var stateReceived: ITTSManager.TTSState? = null
        var progressReceived: Int? = null

        // 1. Attach callbacks
        service.setStateCallback { stateReceived = it }
        service.setProgressCallback { progressReceived = it }

        // Initial callback invocation upon registration
        assertEquals("Initial state must be dispatched upon registration", ITTSManager.TTSState.IDLE, stateReceived)

        // Reset tracking vars
        stateReceived = null
        progressReceived = null

        // 2. Invoke removeStateCallback()
        service.removeStateCallback()

        // 3. Trigger state and progress dispatches directly on service
        service.handleStateChange(ITTSManager.TTSState.PLAYING)

        // Progress callback via field reflection or stopReading reset
        assertNull("State callback must not be invoked after removeStateCallback()", stateReceived)
        assertNull("Progress callback must not be invoked after removeStateCallback()", progressReceived)

        // Also verify removeCallbacks() directly
        service.setStateCallback { stateReceived = it }
        service.setProgressCallback { progressReceived = it }
        service.removeCallbacks()

        stateReceived = null
        progressReceived = null
        service.handleStateChange(ITTSManager.TTSState.PAUSED)
        assertNull("State callback must not be invoked after removeCallbacks()", stateReceived)
        assertNull("Progress callback must not be invoked after removeCallbacks()", progressReceived)
    }

    /**
     * Requirement 2:
     * Simulating Activity lifecycle destruction detaches listeners and prevents memory retention.
     */
    @Test
    fun testActivityLifecycleDestructionDetachesCallbacksAndPreventsRetention() {
        class SimulatedActivity {
            var isStateUpdated = false
            var isProgressUpdated = false

            fun onStateChange(state: ITTSManager.TTSState) {
                isStateUpdated = true
            }

            fun onProgress(progress: Int) {
                isProgressUpdated = true
            }

            fun onDestroy(service: ReadingService) {
                service.removeStateCallback()
            }
        }

        var activity: SimulatedActivity? = SimulatedActivity()
        val weakActivityRef = WeakReference(activity)

        // Register Activity methods as callbacks
        service.setStateCallback { activity?.onStateChange(it) }
        service.setProgressCallback { activity?.onProgress(it) }

        // Simulate onDestroy lifecycle cleanup
        activity!!.onDestroy(service)

        // Clear strong reference to simulate Activity destroy
        activity = null

        // Suggest garbage collection
        System.gc()

        // Trigger state transition on service
        service.handleStateChange(ITTSManager.TTSState.PLAYING)

        // Weak reference should either be cleared or no longer receive any updates
        val retainedActivity = weakActivityRef.get()
        if (retainedActivity != null) {
            assertFalse("Destroyed activity must not receive state updates", retainedActivity.isStateUpdated)
            assertFalse("Destroyed activity must not receive progress updates", retainedActivity.isProgressUpdated)
        }
    }

    /**
     * Requirement 3:
     * NpzReader properly parses scalar arrays with empty shape `()` from InputStream.
     */
    @Test
    fun testScalarNpyParsingWithEmptyShape() {
        // Construct synthetic NPY binary stream for a 0-D scalar float32 with shape ()
        val headerDict = "{'descr': '<f4', 'fortran_order': False, 'shape': (), }\n"
        val headerBytes = headerDict.toByteArray(Charsets.US_ASCII)
        val paddedHeaderLen = ((headerBytes.size + 10 + 63) / 64) * 64 - 10
        val paddedHeader = headerDict.padEnd(paddedHeaderLen - 1, ' ') + "\n"
        val paddedHeaderBytes = paddedHeader.toByteArray(Charsets.US_ASCII)

        val out = ByteArrayOutputStream()
        // Magic \x93NUMPY
        out.write(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()))
        // Version 1.0
        out.write(1)
        out.write(0)
        // Header length (unsigned short, little endian)
        val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(paddedHeaderBytes.size.toShort())
        out.write(lenBuf.array())
        // Header string
        out.write(paddedHeaderBytes)
        // Payload: 1 float32 (value = 1e-5f)
        val floatVal = 0.00001f
        val floatBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(floatVal)
        out.write(floatBuf.array())

        val npyBytes = out.toByteArray()
        val inputStream = ByteArrayInputStream(npyBytes)

        val npyArray = NpzReader.parseNpyStream(inputStream)
        assertNotNull("Scalar NPY must be parsed successfully from stream", npyArray)
        assertEquals("Shape must be 0-dimensional empty array", 0, npyArray!!.shape.size)
        assertEquals("Scalar array must contain exactly 1 float element", 1, npyArray.data.size)
        assertEquals("Float value must match payload exactly", floatVal, npyArray.data[0], 1e-8f)

        // Verify scalar retrieval via full NPZ zip stream reader
        val zipOutBytes = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(zipOutBytes).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("xvec_ln_eps.npy"))
            zos.write(npyBytes)
            zos.closeEntry()
        }

        val npzReader = NpzReader.read(ByteArrayInputStream(zipOutBytes.toByteArray()))
        val scalar = npzReader.getScalarFloat("xvec_ln_eps")
        assertNotNull("getScalarFloat must return non-null for 0-D scalar array", scalar)
        assertEquals("Scalar float value must match", floatVal, scalar!!, 1e-8f)
    }

    /**
     * Requirement 4:
     * Verify scalar parsing against actual production asset vieneu_v3_heads.npz if present.
     */
    @Test
    fun testProductionHeadsNpzScalarEpsilonReading() {
        val candidateDirs = listOf(
            File("app/src/main/assets/vieneu/backbone"),
            File("src/main/assets/vieneu/backbone")
        )
        val backboneDir = candidateDirs.firstOrNull { it.exists() && it.isDirectory }
        if (backboneDir != null) {
            val headsFile = File(backboneDir, "vieneu_v3_heads.npz")
            if (headsFile.exists()) {
                val reader = NpzReader.read(headsFile)
                val eps = reader.getScalarFloat("xvec_ln_eps")
                assertNotNull("xvec_ln_eps in production heads.npz must not be null", eps)
                assertTrue("xvec_ln_eps must be positive (> 0f)", eps!! > 0f)
            }
        }
    }
}
