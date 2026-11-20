# Phase 04: Lifecycle, UI, G2P Caching & Packaging Hygiene

Status: ✅ Completed  
Dependencies: Phase 01, Phase 02, Phase 03  
Target Issues: `PERF-006` (Unconstrained Pre-warming), `PERF-008` (Ineffective G2P Cache), `PERF-009` (Glide & Logging Churn), `PERF-010` (Unused ABI Bundling)

---

## 1. Objective
Finalize application performance hygiene by adding lifecycle-safe debouncing to background TTS prewarming, optimizing G2P caching granularity, eliminating UI thread allocations and unconditional logging during RecyclerView scrolling, and excluding unused native ABI architectures to reduce APK distribution size.

---

## 2. Scope
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` (lines ~348, ~391-400)
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SeaG2P.kt` (lines ~13-19, ~144-165)
- `app/src/main/java/com/skul9x/doctruyen/adapter/StoryAdapter.kt` (lines ~111-132)
- `app/src/main/java/com/skul9x/doctruyen/utils/DebugLogger.kt` (lines ~29-39)
- `app/build.gradle.kts` (ABI packaging filters)
- Test Suite: `app/src/test/java/com/skul9x/doctruyen/LifecycleUiAndPackagingHygieneTest.kt`

---

## 3. Requirements

### 3.1. Functional Requirements
1. **Debounced & Lifecycle-Aware Pre-warming:**
   - In `StoryDetailActivity`, do not trigger immediate background neural prewarming on screen open.
   - Introduce a 3-second debounce window or tie prewarming to user engagement, and immediately cancel any pending prewarming job if the user navigates away (`onStop()` / `onDestroy()`).
   - Allow users to disable background prewarming via a `UserConfig` setting.
2. **Granular G2P Caching:**
   - Re-architect `SeaG2P` caching: replace the whole-sentence cache (up to 75 characters, ~0% hit rate in prose) with word/syllable-level caching where Vietnamese syllables exhibit > 85% repetition, or remove the whole-sentence map to free heap memory.
3. **UI Scroll Garbage & Logging Cleanup:**
   - In `StoryAdapter`, eliminate per-item anonymous `RequestListener` allocations inside `bind()`. Rely on standard Glide placeholder/error mechanisms.
   - In `DebugLogger.log()`, introduce an early exit guard checking `BuildConfig.DEBUG` and user debug flags before allocating `LogEntry` objects and acquiring `synchronized(logBuffer)` mutex locks.
4. **Targeted ABI Packaging:**
   - Configure Gradle `ndk.abiFilters` in `app/build.gradle.kts` to package only `arm64-v8a` native shared libraries for release builds, while retaining `x86_64` support for debug builds on emulators.

### 3.2. Non-Functional Requirements
- **APK Size Reduction:** Reduce the final release APK download size by ~10.5MB (stripping `armeabi-v7a` and `x86_64` `.so` files).
- **UI Smoothness:** Maintain stable 60fps/120fps scrolling on the main story list with zero listener object allocations during fling gestures.
- **Battery & Thermal Protection:** Eliminate 80% of unnecessary on-device neural synthesis work triggered by users who merely browse story summaries without playing audio.

---

## 4. Constraints
- Story image loading must continue to display error and placeholder drawables smoothly without visual regression.
- Debug builds must remain runnable on standard Android Studio x86_64 emulators.
- Normal text-to-phoneme conversion accuracy must remain 100% identical for all Vietnamese text inputs.

---

## 5. Dependencies
- Phases 01, 02, and 03 must be completed.

---

## 6. Acceptance Criteria
1. Opening `StoryDetailActivity` and pressing Back within 2 seconds initiates zero neural synthesis operations on `Dispatchers.Default`.
2. Fling-scrolling `StoryAdapter` across 100 items generates zero per-bind `RequestListener` instances.
3. In release builds (`BuildConfig.DEBUG == false`), calls to `DebugLogger.log` return immediately without populating in-memory log entries.
4. The release APK contains only `lib/arm64-v8a/libsea_g2p_android.so` and saves ~10.5MB.
5. All lifecycle, adapter, and G2P unit tests pass.

---

## 7. Comprehensive File-Based Test

**Test File:** `app/src/test/java/com/skul9x/doctruyen/LifecycleUiAndPackagingHygieneTest.kt`  
**Required Verifications:**
1. `testPrewarmDebounceAndCancellationOnExit()`: Simulate activity entry and exit within the debounce duration, asserting that prewarming is cancelled before invocation.
2. `testG2PCachingAccuracyAndHitRate()`: Phonemize paragraph text with repeated Vietnamese words/syllables and verify that converted phonemes match baseline outputs 100%.
3. `testDebugLoggerSuppressionInReleaseMode()`: Verify that `DebugLogger.log` bypasses `logBuffer` synchronization when debug flags are disabled.
4. `testStoryAdapterZeroPerBindListenerAllocation()`: Inspect adapter binding logic to confirm no anonymous listener objects are allocated per item.

