# Phase 04: Foreground Service Callback Leak Elimination & Scalar Npz Parsing Hardening (AUD-004 & AUD-006)
Status: ✅ Completed
Dependencies: Phase 01, Phase 02, Phase 03

## Objective
Eliminate Activity context memory leaks (AUD-004) where long-running `ReadingService` retains destroyed `StoryDetailActivity` instances via unreleased UI progress callbacks, fix the 0-dimensional NumPy scalar parsing flaw in `NpzReader.kt` (AUD-006) that corrupted stream offsets and returned null for acoustic normalization parameters, resolve punctuation-first text segmentation regressions in `SmartTextSegmenter.kt`, and harmonize outdated unit test assertions to restore a 100% clean passing test suite (305/305 tests passing).

## Scope
1. Update `ReadingService.removeStateCallback()` to unconditionally nullify both `stateCallback` and `progressCallback`.
2. Add explicit deterministic callback cleanup inside `StoryDetailActivity.onDestroy()`.
3. Fix scalar NPY tensor parsing in `NpzReader.kt:168` so that arrays with empty shape `()` correctly calculate `totalElements = 1` and read their 4 bytes from `ZipInputStream`.
4. Fix prosody and punctuation regressions in `SmartTextSegmenter.kt`:
   - In `normalizePunctuation()`: preserve existing terminal punctuation (`!`, `?`, `…`) instead of blindly forcing short sentences (< 5 words) to end with `.`. Preserve natural story titles without forcefully appending periods.
   - In `splitSentenceIntoSubClauses()`: ensure clause combination honors caller-supplied `maxChars` (`<= minOf(75, maxChars)`) rather than hardcoding `<= 75`.
5. Harmonize outdated assertions in existing unit tests:
   - Update `BoundedAudioChannelPipelineTest.testMemoryCapUnderLongStoryPlayback` to verify all audio samples are fully written without assuming 1 chunk equals 1 write call (due to 100ms frame slicing).
   - Update `VoicePresetsSyncTest`:
     - Update preset count from 20 to 25.
     - Update `defaultVoiceName` expectation from `"Adam"` to `"Hải Đăng"`.
     - Add the 5 new preset voices (`"Adam bựa"`, `"Thiện Minh"`, `"Hải Đăng"`, `"Thiền Tâm Đức"`, `"Quốc Tuấn"`) to `expectedVoices` and `northVoices` (all 5 belong to region "Bắc").
     - Update unknown voice fallback expectation from `"Adam"` to `"Hải Đăng"`.

## Requirements
### Functional
- When `StoryDetailActivity` is finished, rotated, or destroyed, `ReadingService` must retain zero references to its callbacks or Views.
- `NpzReader.getScalarFloat("xvec_ln_eps")` must return the exact float value stored in `vieneu_v3_heads.npz` (value > 0f) without returning `null` or corrupting subsequent stream entries.
- Text segmentation preserves natural exclamation (`!`) and question (`?`) marks in dialogues and short sentences.
- All 305 project unit tests must pass with 0 failures.

### Non-Functional & Reliability
- Zero memory leakage of Activity context or View hierarchies over extended reading sessions.
- Clean stream parsing without buffer offsets or unread bytes remaining in compressed archive entries.
- Adherence to Android architecture best practices: deterministic unregistering in lifecycle methods (`onStop`/`onDestroy`) avoiding fragile `WeakReference` silent failures.

## How to Implement
1. In `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt`:
   - Enhance `removeStateCallback()`:
     ```kotlin
     fun removeCallbacks() {
         stateCallback = null
         progressCallback = null
     }

     fun removeStateCallback() {
         removeCallbacks()
     }
     ```
2. In `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt`:
   - Add/enhance `onDestroy()`:
     ```kotlin
     override fun onDestroy() {
         if (isBound) {
             try {
                 readingService?.removeStateCallback()
                 unbindService(serviceConnection)
             } catch (_: Exception) {}
             isBound = false
         }
         super.onDestroy()
     }
     ```
3. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/NpzReader.kt`:
   - In `parseNpyStream(inputStream: InputStream)` (around line 168):
     Fix `totalElements` calculation for 0-D scalar arrays:
     ```kotlin
     val shape = parseShape(headerStr)
     var totalElements = 1
     for (dim in shape) {
         totalElements *= dim
     }
     val floatData = FloatArray(totalElements)
     ```
4. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SmartTextSegmenter.kt`:
   - In `normalizePunctuation(text: String)`:
     Do not overwrite terminal exclamation mark `!` or question mark `?` with a period `.`. Preserve existing punctuation if already in `SENT_END_CHARS`:
     ```kotlin
     val normalized = if (words.size < 5) {
         if (lastChar in SENT_END_CHARS) {
             trimmed
         } else if (lastChar == ',') {
             var i = trimmed.length - 1
             while (i >= 0 && (trimmed[i] in SENT_END_CHARS || trimmed[i] == ',')) {
                 i--
             }
             trimmed.substring(0, i + 1) + "."
         } else {
             trimmed + "."
         }
     } else {
         ...
     }
     ```
   - In `segmentStory(title: String, content: String, ...)`:
     Avoid forcing trailing punctuation on clean title strings (`title.trim()`).
   - In `splitSentenceIntoSubClauses(sentence, maxChars, ...)`:
     Respect `maxChars` when checking combine thresholds:
     ```kotlin
     val canCombine = currentBuf.isNotEmpty() &&
             !currentBuf.endsWith(":") &&
             (currentBufLen + 1 + clauseLen <= minOf(75, maxChars))
     ```
5. In `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/BoundedAudioChannelPipelineTest.kt`:
   - In `testMemoryCapUnderLongStoryPlayback`:
     Since `AudioPlayer.writeSuspended` breaks each 48,000-sample chunk into 4,800-sample (100ms) slices, track `totalSamplesConsumed.addAndGet(size)` and assert `totalSamplesConsumed == totalStoryChunks * 48000`.
6. In `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/VoicePresetsSyncTest.kt`:
   - Update preset count expectation from 20 to 25.
   - Update `assertEquals("Hải Đăng", VoicePresets.defaultVoiceName)`.
   - Add `"Adam bựa"`, `"Thiện Minh"`, `"Hải Đăng"`, `"Thiền Tâm Đức"`, `"Quốc Tuấn"` to `expectedVoices` and `northVoices`.
   - Update fallback test assertion from `"Adam"` to `"Hải Đăng"`.

## Files to Create/Modify
- Modify: `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/NpzReader.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SmartTextSegmenter.kt`
- Modify: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/BoundedAudioChannelPipelineTest.kt`
- Modify: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/VoicePresetsSyncTest.kt`

## Dependencies
- Phase 01, Phase 02, Phase 03

## Constraints
- Must not interfere with background Notification updates or LockScreen controls while reading.
- Must remain compatible with Android API 24 through API 35.

## Acceptance Criteria
- [x] `ReadingService.removeStateCallback()` clears both `stateCallback` and `progressCallback`.
- [x] `NpzReaderTest.testReadHeadsNpz` passes with non-null scalar float > 0f.
- [x] `BoundedAudioChannelPipelineTest.testMemoryCapUnderLongStoryPlayback` passes.
- [x] `VoicePresetsSyncTest.testAllTwentyVoicesLoadingAndMetadataIntegrity` passes with 25 voices and "Hải Đăng" as default.
- [x] `PcmUtilsTest`, `SmartDialogueSegmentationTest`, `SmartTextSegmenterTest`, `UserConfigPauseTest` all pass cleanly.
- [x] Entire test suite `./gradlew testDebugUnitTest` runs with 305 tests passing and 0 failures.
- [x] Passes `ServiceCallbackLifecycleCleanupTest`.

## Test Implementation
- File: `app/src/test/java/com/skul9x/doctruyen/service/ServiceCallbackLifecycleCleanupTest.kt`
- Exactly one comprehensive file-based test suite verifying:
  1. Callback registration in `ReadingService` and subsequent invocation of `removeStateCallback()` cleanly sets both state and progress callbacks to `null`.
  2. Simulating Activity lifecycle destruction detaches listeners and prevents memory retention.
  3. `NpzReader` properly parses scalar arrays with empty shape `()`.
