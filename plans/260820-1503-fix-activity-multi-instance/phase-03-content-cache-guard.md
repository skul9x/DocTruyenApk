# Phase 03: StoryDetailActivity Content Cache & Network Deduplication Guard

Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective

Prevent `StoryDetailActivity` from firing duplicate network API calls (`loadStoryContent()`) when:
1. The Activity is recreated (configuration change, process death recovery).
2. The user navigates back and returns to the same story.
3. Multiple Activity instances exist (residual from pre-Phase-01 scenarios or edge cases).

## Root Cause Addressed

`loadStoryContent()` fires an unconditional network request every time it is called — there is no check for whether `storyContent` already holds valid data for the current `storyId`. In log5.txt, the same API call `GET /api.php?action=detail&id=119` was made **5 times** within 12 seconds, each response being identical 1389-char content.

Each network response triggers:
- JSON parsing on the main/IO thread
- HTML sanitization (`sanitizeHtml`)
- `Spanned` text rendering
- `triggerPrewarmIfReady()` → `prepareStory()` (addressed by Phase 02 but still wastes CPU cycles)

## Implementation Steps

### 1. Add content cache guard to `loadStoryContent()`

In `StoryDetailActivity.kt`, modify `loadStoryContent()` to skip the API call if content is already loaded for the current storyId:

```kotlin
private var loadedStoryId: Int = 0  // Track which storyId the content belongs to

private fun loadStoryContent() {
    DebugLogger.log(TAG, "📥 loadStoryContent: storyId=$storyId", LogType.REQUEST)

    // Guard: Skip API call if we already have content for this exact story
    if (storyContent.isNotEmpty() && loadedStoryId == storyId) {
        DebugLogger.log(TAG, 
            "⏭️ loadStoryContent SKIPPED: content already loaded for storyId=$storyId (${storyContent.length} chars)", 
            LogType.INFO)
        showContentState()
        triggerPrewarmIfReady()
        return
    }

    // Show loading state
    showLoadingState()
    
    // ... existing network call code ...
}
```

### 2. Set `loadedStoryId` when content is successfully loaded

Inside the network success callback (around line 285-308), after `storyContent` is populated:

```kotlin
// After: storyContent = contentText
loadedStoryId = storyId
```

### 3. Reset `loadedStoryId` on new story via `onNewIntent()` (from Phase 01)

In the `onNewIntent()` method added in Phase 01, ensure `loadedStoryId` is reset:

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)

    val newStoryId = intent.getIntExtra("story_id", 0)
    if (newStoryId != storyId && newStoryId != 0) {
        isPrewarmTriggered = false
        lastRenderedState = null
        storyContent = ""
        loadedStoryId = 0  // Force fresh load for new story
        loadStoryFromIntent()
    }
}
```

### 4. Add in-flight request guard (prevent concurrent duplicate requests)

Add a boolean flag to prevent overlapping network requests:

```kotlin
private var isLoadingContent = false

private fun loadStoryContent() {
    // ... cache guard from step 1 ...

    // Guard: Prevent overlapping requests
    if (isLoadingContent) {
        DebugLogger.log(TAG, "⏭️ loadStoryContent SKIPPED: request already in flight", LogType.INFO)
        return
    }
    isLoadingContent = true

    showLoadingState()

    lifecycleScope.launch(Dispatchers.IO) {
        try {
            // ... existing API call ...
        } finally {
            isLoadingContent = false  // Reset in finally block
        }
    }
}
```

## Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` | MODIFY | Add `loadedStoryId`, `isLoadingContent` guards to `loadStoryContent()` |

## Test

A single comprehensive test file: `StoryDetailContentCacheGuardTest.kt`

The test verifies:
1. First call to `loadStoryContent()` for storyId=119 triggers a network request (simulated).
2. Second call to `loadStoryContent()` for the SAME storyId=119 when content is already loaded is SKIPPED (no network call).
3. Call to `loadStoryContent()` for a DIFFERENT storyId=120 triggers a new network request.
4. Concurrent calls while a request is in-flight are SKIPPED (in-flight guard).

## Notes

- This phase completes the "defense-in-depth" strategy:
  - **Phase 01 (Manifest):** Prevents duplicate Activity instances → eliminates the trigger
  - **Phase 02 (Service):** Prevents duplicate `prepareStory` calls → protects ONNX engine
  - **Phase 03 (Activity):** Prevents duplicate API calls → saves bandwidth, CPU, and battery
- Together, these three layers ensure that even if one defense fails (e.g. Activity recreated by the system for legitimate reasons), the other two layers will prevent performance degradation.

---
Previous Phase: [Phase 02 - ReadingService prepareStory Guard](./phase-02-reading-service-prepare-guard.md)
