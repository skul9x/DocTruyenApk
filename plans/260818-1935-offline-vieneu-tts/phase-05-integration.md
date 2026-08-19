# Phase 05: Replace TTSManager & Integration with ReadingService
Status: ✅ Completed  
Dependencies: Phase 03, Phase 04

## Objective
Replace the existing `TTSManager.kt` (which uses Android's built-in `android.speech.tts.TextToSpeech`) with a new `VieNeuTTSManager.kt` that uses the VieNeu ONNX engine (Phase 03) and audio pipeline (Phase 04). Integrate with the existing `ReadingService.kt` so all features work: background playback, notification controls, pause/resume, and progress tracking.

## Background
The current DocTruyenApk has:
- `TTSManager.kt` — manages Android TTS, chunking, pause/resume, state callbacks
- `ReadingService.kt` — Foreground Service with notification controls, binds to `TTSManager`
- `StoryDetailActivity.kt` — UI that starts/stops reading via the service

The goal is a **drop-in replacement**: `ReadingService` and `StoryDetailActivity` should require minimal changes. The new `VieNeuTTSManager` must expose the same interface (`TTSState`, `play`, `pause`, `resume`, `stop`, callbacks).

## Requirements

### Functional
- [x] `VieNeuTTSManager.kt` implements the same `TTSState` enum and callback pattern as existing `TTSManager`
- [x] `ReadingService.kt` updated to use `VieNeuTTSManager` instead of `TTSManager`
- [x] Background playback works when screen is off or app is in background
- [x] Notification controls (Play/Pause/Stop) fully functional on Android 13+
- [x] Progress callback (`onProgressChange`) reports 0-100 based on chunks completed
- [x] `speakWithTitlePause()` preserved: reads title, pauses 1s, then reads content
- [x] Pause saves current chunk position; Resume continues from saved chunk
- [x] Voice selection setting in `SettingsActivity` allows choosing VieNeu preset voices

### Non-Functional
- [x] No regression in existing features (story browsing, search, image loading)
- [x] Service lifecycle properly managed (no leaked resources on destroy)
- [x] Memory cleanup: ONNX sessions released when service is destroyed

## Implementation Steps

1. [x] **Create `VieNeuTTSManager.kt`** — new TTS manager using VieNeu:
   ```kotlin
   class VieNeuTTSManager(
       context: Context,
       private val onStateChange: (TTSState) -> Unit,
       private val onProgressChange: ((Int) -> Unit)? = null
   ) {
       // Reuse the same TTSState enum from original TTSManager
       enum class TTSState { IDLE, INITIALIZING, READY, PLAYING, PAUSED, ERROR }

       private val engine: VieNeuOnnxEngine  // from Phase 03
       private val pipeline: TTSAudioPipeline // from Phase 04
       private var voiceName: String = "Trúc Ly"

       fun speakContent(content: String)
       fun speakWithTitlePause(title: String, content: String)
       fun pause()
       fun resume()
       fun stop()
       fun isPlaying(): Boolean
       fun isPaused(): Boolean
       fun setVoice(name: String) { voiceName = name }
       fun getAvailableVoices(): List<String> // returns preset voice names
       fun shutdown()
   }
   ```

2. [x] **Update `ReadingService.kt`**:
   - Change `ttsManager` type from `TTSManager` to `VieNeuTTSManager`
   - Update `initTTS()` to create `VieNeuTTSManager` instance
   - The state callback pattern is identical, so notification logic stays the same
   - Add voice name to `startReading()` intent extras

3. [x] **Update `StoryDetailActivity.kt`**:
   - Minimal changes: the service binding API stays the same
   - Add voice selector dropdown/spinner if desired

4. [x] **Update `SettingsActivity.kt`**:
   - Replace Android TTS voice picker with VieNeu voice list
   - Save selected voice name to SharedPreferences
   - Available voices: "Trúc Ly", "Minh Đức", "Quỳnh Như", "Duy Tân", etc.

5. [x] **Handle model initialization async**:
   - ONNX model loading takes 2-5 seconds on first call
   - Show "Đang khởi tạo giọng đọc AI..." state during init
   - Cache engine instance across service lifecycle (don't reload on every chapter)

6. [x] **Implement graceful fallback**:
   - If VieNeu engine fails to load (low memory, corrupted model), fall back to Android TTS
   - Log error via `DebugLogger`

7. [x] **Update `AndroidManifest.xml`** if needed:
   - Ensure `FOREGROUND_SERVICE_TYPE` includes `mediaPlayback` (Android 14+)

## Files to Create/Modify

- `app/.../tts/vieneu/VieNeuTTSManager.kt` — [NEW] New TTS manager
- `app/.../service/ReadingService.kt` — [MODIFY] Switch to VieNeuTTSManager
- `app/.../StoryDetailActivity.kt` — [MODIFY] Minor UI updates (optional)
- `app/.../SettingsActivity.kt` — [MODIFY] Voice selection UI
- `app/.../tts/TTSManager.kt` — [KEEP] Retain as fallback, rename to `AndroidTTSManager.kt`
- `AndroidManifest.xml` — [MODIFY] Add foreground service type if needed

## Verification Test

### Test: `VieNeuTTSManagerIntegrationTest.kt`
Android instrumented test:

```kotlin
package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class VieNeuTTSManagerIntegrationTest {

    private lateinit var context: Context
    private lateinit var manager: VieNeuTTSManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun teardown() {
        if (::manager.isInitialized) {
            manager.shutdown()
        }
    }

    @Test
    fun testInitializationLifecycle() {
        val states = mutableListOf<VieNeuTTSManager.TTSState>()
        val readyLatch = CountDownLatch(1)

        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { state ->
                states.add(state)
                if (state == VieNeuTTSManager.TTSState.READY) {
                    readyLatch.countDown()
                }
            }
        )

        // Wait up to 30 seconds for initialization
        val initialized = readyLatch.await(30, TimeUnit.SECONDS)

        assertTrue("Manager should reach READY state within 30s", initialized)
        assertTrue("States should include INITIALIZING", 
            states.contains(VieNeuTTSManager.TTSState.INITIALIZING))
        assertTrue("States should include READY", 
            states.contains(VieNeuTTSManager.TTSState.READY))
        println("✅ Initialization lifecycle: ${states.joinToString(" → ")}")
    }

    @Test
    fun testSpeakAndStateTransitions() = runBlocking {
        val states = mutableListOf<VieNeuTTSManager.TTSState>()
        val doneLatch = CountDownLatch(1)

        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { state ->
                states.add(state)
                if (state == VieNeuTTSManager.TTSState.READY && states.count { 
                    it == VieNeuTTSManager.TTSState.PLAYING 
                } > 0) {
                    doneLatch.countDown()
                }
            }
        )

        // Wait for READY
        delay(10000)

        // Speak a short sentence
        manager.speakContent("Xin chào.")

        // Wait for completion (max 30s)
        doneLatch.await(30, TimeUnit.SECONDS)

        assertTrue("Should have entered PLAYING state", 
            states.contains(VieNeuTTSManager.TTSState.PLAYING))
        println("✅ Speak lifecycle: ${states.joinToString(" → ")}")
    }

    @Test
    fun testPauseResume() = runBlocking {
        val states = mutableListOf<VieNeuTTSManager.TTSState>()

        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { state -> states.add(state) }
        )

        delay(10000) // wait for init

        // Start speaking a longer text
        manager.speakContent("Ngày xưa có một chàng trai nghèo sống bên bờ sông. Anh ta rất chăm chỉ.")

        delay(3000) // let it start playing

        // Pause
        manager.pause()
        assertTrue("Should be paused", manager.isPaused())
        assertFalse("Should not be playing", manager.isPlaying())

        delay(1000)

        // Resume
        manager.resume()
        delay(2000)

        assertTrue("Should have transitioned through PAUSED state", 
            states.contains(VieNeuTTSManager.TTSState.PAUSED))
        println("✅ Pause/Resume test passed")
    }

    @Test
    fun testVoiceSelection() = runBlocking {
        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { }
        )
        delay(10000)

        val voices = manager.getAvailableVoices()
        assertTrue("Should have at least 1 preset voice", voices.isNotEmpty())
        println("Available voices: $voices")

        // Set a different voice
        if (voices.size > 1) {
            manager.setVoice(voices[1])
            println("✅ Voice changed to: ${voices[1]}")
        }
    }

    @Test
    fun testSpeakWithTitlePause() = runBlocking {
        var progressValues = mutableListOf<Int>()

        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { },
            onProgressChange = { progress -> progressValues.add(progress) }
        )
        delay(10000)

        manager.speakWithTitlePause(
            title = "Chương Một",
            content = "Ngày xưa có một chàng trai."
        )

        delay(15000) // wait for title + pause + content

        assertTrue("Progress should have been reported", progressValues.isNotEmpty())
        println("✅ speakWithTitlePause completed. Progress values: $progressValues")
    }

    @Test
    fun testStopCleansUp() = runBlocking {
        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { }
        )
        delay(10000)

        manager.speakContent("Đây là một câu dài để test chức năng dừng giữa chừng.")
        delay(2000) // let it start

        manager.stop()

        assertFalse("Should not be playing after stop", manager.isPlaying())
        assertFalse("Should not be paused after stop", manager.isPaused())
        println("✅ Stop cleanup test passed")
    }
}
```

## Notes
- The key design decision is whether to initialize the ONNX engine once at Service creation and keep it alive, or create/destroy per reading session. **Recommendation: keep alive** — model loading is expensive (2-5s) and the ~500MB RAM is acceptable for a foreground service.
- The existing `TTSManager.kt` should be kept (renamed) as a fallback. If the device has < 3GB RAM, the app can detect this at runtime and use Android TTS instead of VieNeu.
- Voice presets come from VieNeu's bundled speaker embeddings. The current Python code stores them in `src/vieneu/_v3_turbo_engine/speaker/`. These need to be extracted and bundled as assets.

---
Previous Phase: [Phase 04 — Audio Pipeline](./phase-04-audio-pipeline.md)  
Next Phase: [Phase 06 — Optimization & Testing](./phase-06-optimization.md)
