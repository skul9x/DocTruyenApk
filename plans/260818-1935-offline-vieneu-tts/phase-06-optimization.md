# Phase 06: Optimization & Final Testing
Status: ⬜ Pending  
Dependencies: Phase 05

## Objective
Optimize the on-device VieNeu-TTS integration for real-world usage: reduce memory footprint, improve inference speed, handle edge cases, perform end-to-end testing on a physical device, and ensure the complete story reading experience works seamlessly.

## Requirements

### Functional
- [ ] App installs and runs correctly on physical Android device (API 24+)
- [ ] Full story reading flow: open story → press Play → hear AI voice reading → notification controls work → pause → resume → background playback → stop
- [ ] Settings: voice selection persists across app restarts
- [ ] Edge cases handled: empty chapter, very long chapter (5000+ words), special characters, mixed En-Vi text
- [ ] Graceful degradation: if ONNX engine fails, show meaningful error message

### Non-Functional  
- [ ] Cold start (first inference after app launch): < 8 seconds
- [ ] Warm inference (subsequent sentences): < 2 seconds per sentence
- [ ] RAM usage during playback: < 700 MB total app memory
- [ ] No ANR (Application Not Responding) — all inference on background thread
- [ ] Battery: < 5% per hour of continuous reading on mid-range device
- [ ] APK size: < 350 MB (or use APK splits / asset delivery for model files)

## Implementation Steps

### Optimization

1. [ ] **ONNX Runtime optimization flags**:
   - Enable `XNNPACK` execution provider for ARM NEON acceleration:
     ```kotlin
     sessionOptions.addXnnpack(mapOf("intra_op_num_threads" to "4"))
     ```
   - Test with `NNAPI` (Neural Networks API) on supported devices for potential GPU offload
   - Benchmark both and pick the fastest per-device

2. [ ] **Memory optimization**:
   - Use `MemoryMappedFile` for large `.data` weight blobs instead of reading into RAM
   - Reuse `OnnxTensor` objects via a pool instead of allocating new ones per inference
   - Clear KV cache tensors immediately after decode loop completes
   - Call `System.gc()` after engine initialization

3. [ ] **Inference speed optimization**:
   - Reduce `max_new_tokens` for short sentences (estimate needed tokens from text length)
   - Use early stopping: if EOS token is predicted, stop immediately
   - Pre-warm the engine with a dummy inference at service start (hides cold-start latency)

4. [ ] **APK size optimization** (choose one strategy):
   - **Option A: Bundle in APK** — use `android.bundle` with asset packs (Play Asset Delivery) for model files
   - **Option B: Download on first launch** — ship a slim APK (~15 MB), download models (~290 MB) on first run with progress bar
   - **Recommended for personal use: Option A** (simpler, works offline immediately)

5. [ ] **Thread management**:
   - Pin inference to a dedicated single-thread `Executor` (avoid contention)
   - AudioTrack playback on a separate high-priority thread
   - Use `android.os.Process.setThreadPriority(THREAD_PRIORITY_AUDIO)` for playback thread

### Edge Case Handling

6. [ ] **Empty or whitespace-only text**: Return immediately without error
7. [ ] **Very long text (5000+ words)**: Chunk at paragraph level, limit queue depth to 3 chunks to cap memory
8. [ ] **Unicode / emoji**: Strip unspeakable characters before phonemization
9. [ ] **HTML content from API**: Strip HTML tags (the app fetches story content from a web API with embedded HTML)
10. [ ] **Out of memory**: Catch `OOMError` in inference, release engine, notify user, offer Android TTS fallback

### Final Testing

11. [ ] **Device matrix testing**:
    - Test on at least 2 devices: one mid-range (Snapdragon 600-series) and one high-end
    - Test with screen off (background playback)
    - Test with headphones connected / Bluetooth audio

12. [ ] **Stress test**: Read an entire chapter (1000+ words) without interruption — verify no crash, no memory leak, smooth audio

13. [ ] **Regression test**: Verify all existing features still work:
    - Story list loading
    - Story detail with images
    - Search functionality
    - Swipe refresh
    - Debug log

## Files to Create/Modify

- `app/.../tts/vieneu/VieNeuOnnxEngine.kt` — [MODIFY] Add XNNPACK, memory optimizations
- `app/.../tts/vieneu/OnnxSessionManager.kt` — [MODIFY] Memory-mapped loading
- `app/.../tts/vieneu/TTSAudioPipeline.kt` — [MODIFY] Queue depth limit, edge cases
- `app/.../tts/vieneu/VieNeuTTSManager.kt` — [MODIFY] Pre-warm, fallback logic
- `app/.../utils/HtmlStripper.kt` — [NEW] Strip HTML tags from story content
- `app/build.gradle.kts` — [MODIFY] ProGuard rules for ONNX Runtime if using minification

## Verification Test

### Test: `EndToEndStoryReadingTest.kt`
Final acceptance test — runs on a physical device:

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
class EndToEndStoryReadingTest {

    private lateinit var context: Context
    private lateinit var manager: VieNeuTTSManager

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        val readyLatch = CountDownLatch(1)
        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { state ->
                if (state == VieNeuTTSManager.TTSState.READY) readyLatch.countDown()
            }
        )
        assertTrue("Engine should initialize within 30s", readyLatch.await(30, TimeUnit.SECONDS))
    }

    @After
    fun teardown() {
        manager.shutdown()
    }

    @Test
    fun testFullChapterReading() = runBlocking {
        // Simulate a realistic story chapter (~500 words)
        val chapter = buildString {
            append("Ngày xưa, ở một vương quốc xa xôi, có một nàng công chúa xinh đẹp tên là Hạ Vy. ")
            append("Nàng sống trong một tòa lâu đài nguy nga nằm trên đỉnh ngọn đồi cao nhất. ")
            append("Mỗi sáng, nàng thức dậy và ngắm nhìn cánh đồng hoa trải dài bất tận. ")
            append("Cuộc sống của nàng tưởng chừng hoàn hảo, nhưng nàng luôn cảm thấy thiếu vắng điều gì đó. ")
            repeat(5) {
                append("Một ngày nọ, một chàng hiệp sĩ từ phương xa ghé qua vương quốc. ")
                append("Chàng mang theo thanh kiếm sáng lấp lánh và một trái tim dũng cảm. ")
                append("Chàng đã chiến đấu với con rồng hung dữ để bảo vệ dân làng. ")
                append("Công chúa nhìn từ xa và cảm thấy ngưỡng mộ lòng dũng cảm của chàng. ")
            }
            append("Và họ sống hạnh phúc mãi mãi về sau.")
        }

        var lastProgress = 0
        var hasPlayed = false

        // Read with title
        manager = VieNeuTTSManager(
            context = context,
            onStateChange = { state ->
                if (state == VieNeuTTSManager.TTSState.PLAYING) hasPlayed = true
            },
            onProgressChange = { progress -> lastProgress = progress }
        )
        delay(10000) // wait for init

        val startTime = System.currentTimeMillis()
        manager.speakWithTitlePause("Chương 1: Nàng Công Chúa", chapter)

        // Wait for at least some progress (max 60s)
        var waited = 0
        while (lastProgress < 20 && waited < 60000) {
            delay(1000)
            waited += 1000
        }

        val elapsed = System.currentTimeMillis() - startTime

        // Assertions
        assertTrue("Should have started playing", hasPlayed)
        assertTrue("Should have made progress (>= 10%)", lastProgress >= 10)
        
        // Check memory usage
        val runtime = Runtime.getRuntime()
        val usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        assertTrue("Memory usage should be < 700 MB, was ${usedMB} MB", usedMB < 700)

        // Stop and verify cleanup
        manager.stop()
        delay(1000)
        assertFalse("Should not be playing after stop", manager.isPlaying())

        println("✅ Full chapter test passed!")
        println("   Time to 20% progress: ${elapsed}ms")
        println("   Memory usage: ${usedMB} MB")
        println("   Last progress: ${lastProgress}%")
    }

    @Test
    fun testEmptyContentHandling() = runBlocking {
        manager.speakContent("")
        delay(1000)
        // Should not crash, should transition to READY
        assertFalse("Should not be playing empty content", manager.isPlaying())
    }

    @Test
    fun testHtmlStripping() {
        val htmlContent = "<p>Đây là <b>nội dung</b> truyện.</p><br/><img src='test.jpg'/>"
        val stripped = HtmlStripper.strip(htmlContent)
        
        assertFalse("Should not contain HTML tags", stripped.contains("<"))
        assertTrue("Should contain text content", stripped.contains("nội dung"))
        println("Stripped: '$stripped'")
    }

    @Test
    fun testMixedLanguageText() = runBlocking {
        // Vietnamese-English code-switching
        val text = "Anh ấy nói hello world rồi chạy function main."

        manager.speakContent(text)
        delay(5000)

        // Should not crash on mixed language
        assertTrue("Should handle mixed En-Vi text", 
            manager.isPlaying() || !manager.isPaused())
        manager.stop()
        println("✅ Mixed language test passed")
    }

    @Test
    fun testSpecialCharacters() = runBlocking {
        val text = "Giá: 50.000đ! Tỷ lệ: 99.9% 😊 Email: test@gmail.com"

        manager.speakContent(text)
        delay(5000)
        manager.stop()
        println("✅ Special characters test passed")
    }

    @Test
    fun testColdStartPerformance() {
        // Measure time from VieNeuTTSManager creation to READY state
        val startTime = System.currentTimeMillis()
        val readyLatch = CountDownLatch(1)

        val freshManager = VieNeuTTSManager(
            context = context,
            onStateChange = { state ->
                if (state == VieNeuTTSManager.TTSState.READY) readyLatch.countDown()
            }
        )

        readyLatch.await(30, TimeUnit.SECONDS)
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue("Cold start should be < 15 seconds, was ${elapsed}ms", elapsed < 15000)
        println("✅ Cold start time: ${elapsed}ms")

        freshManager.shutdown()
    }
}
```

## Notes
- For a personal app, **Option A (bundle in APK)** for model files is recommended. The 350MB APK is large but only installed once and works fully offline.
- If you later want to publish on Google Play Store, use **Play Asset Delivery** (PAD) with "install-time" delivery to stay under the 150MB base APK limit while still bundling the models.
- ProGuard/R8 minification must exclude ONNX Runtime classes. Add to `proguard-rules.pro`:
  ```
  -keep class ai.onnxruntime.** { *; }
  -keep class com.microsoft.onnxruntime.** { *; }
  ```

---
Previous Phase: [Phase 05 — Integration](./phase-05-integration.md)
