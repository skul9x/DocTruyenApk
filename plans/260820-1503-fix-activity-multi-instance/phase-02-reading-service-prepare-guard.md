# Phase 02: ReadingService prepareStory State Guard

Status: ✅ Completed
Dependencies: Phase 01

## Objective

Add a defense-in-depth guard inside `ReadingService.prepareStory()` so that even if multiple callers (Activity instances, lifecycle callbacks, or service reconnections) invoke `prepareStory()`, the Service only forwards the call to `TTSManager` when it is actually necessary.

## Root Cause Addressed

`ReadingService.prepareStory()` currently has **zero state checking**. It unconditionally forwards every call to `ttsManager?.prepareStory()`. When multiple Activity instances each call `prepareStory()` for the same story while ONNX is already synthesizing audio, the TTS engine may attempt to re-segment and re-queue chunks, causing thread contention on the CPU and degrading inference performance.

## Implementation Steps

### 1. Add state guard to `ReadingService.prepareStory()`

In `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt`, modify `prepareStory()`:

```kotlin
fun prepareStory(storyId: Int, title: String, content: String, voice: String? = null) {
    // Guard: Skip if already playing or preparing the exact same story
    val currentState = ttsManager?.currentState
    if (currentStoryId == storyId && 
        (currentState == ITTSManager.TTSState.PLAYING || 
         currentState == ITTSManager.TTSState.PAUSED)) {
        DebugLogger.log("ReadingService", 
            "⏭️ prepareStory SKIPPED: already active on storyId=$storyId (state=$currentState)", 
            LogType.INFO)
        return
    }

    val isGoogle = ttsManager?.engineType == ITTSManager.EngineType.GOOGLE_TTS
    val freshVoice = voice
        ?: (if (isGoogle) UserConfig.getGoogleTtsVoice(this) else UserConfig.getTtsVoice(this))
        ?: ttsManager?.currentVoiceName
        ?: ""
    DebugLogger.log("ReadingService", 
        "🔥 prepareStory: storyId=$storyId, title='$title', voice='$freshVoice'", 
        LogType.INFO)
    if (freshVoice.isNotEmpty()) {
        ttsManager?.setVoice(freshVoice)
    }
    ttsManager?.prepareStory(storyId, title, content, voice = freshVoice)
}
```

**Key design decisions:**
- We check `currentStoryId == storyId` combined with state being `PLAYING` or `PAUSED` to determine if the Service is already actively processing this story.
- We do NOT block `READY` or `IDLE` states — those indicate the previous session has finished and a new `prepareStory` is legitimate (e.g. pre-warming before the user hits Play).
- We log the skip event so it appears in diagnostic logs for debugging.

### 2. Ensure `currentStoryId` is set during `prepareStory` (not just `startReading`)

Currently, `currentStoryId` is only set inside `startReading()`. For the guard to work during pre-warming (before play), we should also track the "preparing" story ID. Add a `preparingStoryId` field:

```kotlin
private var preparingStoryId: Int = 0
```

And update the guard to check both:

```kotlin
if ((currentStoryId == storyId || preparingStoryId == storyId) && 
    (currentState == ITTSManager.TTSState.PLAYING || 
     currentState == ITTSManager.TTSState.PAUSED ||
     currentState == ITTSManager.TTSState.READY)) {
    // Skip — already preparing or playing this story
    return
}
preparingStoryId = storyId
```

Reset `preparingStoryId` when starting a different story or stopping:

```kotlin
// In startReading():
preparingStoryId = 0  // Clear — now tracked by currentStoryId

// In stopReading():
preparingStoryId = 0
```

## Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt` | MODIFY | Add state guard + `preparingStoryId` tracking |

## Test

A single comprehensive test file: `ReadingServicePrepareGuardTest.kt`

The test verifies:
1. First call to `prepareStory(storyId=1)` is forwarded to TTSManager (returns true / logs "🔥").
2. Second call to `prepareStory(storyId=1)` while state is PLAYING is SKIPPED (logs "⏭️").
3. Call to `prepareStory(storyId=2)` (different story) while state is PLAYING storyId=1 is ALLOWED.
4. Call to `prepareStory(storyId=1)` after `stopReading()` is ALLOWED (state reset to IDLE).

## Notes

- This guard is the most critical defense. Even without Phase 01 (singleTop), this guard alone would have prevented the CPU contention seen in log5.txt, because the 4 duplicate `prepareStory` calls would have been silently dropped.
- The guard is intentionally placed in `ReadingService` (not `StoryDetailActivity`) because the Service is the single source of truth for TTS state.

---
Next Phase: [Phase 03 - StoryDetailActivity Content Cache Guard](./phase-03-content-cache-guard.md)
