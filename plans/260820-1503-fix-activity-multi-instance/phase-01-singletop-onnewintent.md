# Phase 01: Manifest singleTop + onNewIntent Handler

Status: ✅ Completed
Dependencies: None

## Objective

Prevent Android from creating multiple `StoryDetailActivity` instances when the user taps the same story repeatedly from the list. Instead, reuse the existing Activity at the top of the stack and route new intents through `onNewIntent()`.

## Root Cause Addressed

`StoryDetailActivity` in `AndroidManifest.xml` has no `launchMode` attribute (defaults to `standard`), meaning every `startActivity()` call from `MainActivity.openStoryDetail()` creates a brand new Activity instance. Each instance independently runs the full lifecycle: `onCreate` → `loadStoryFromIntent` → `loadStoryContent` → API call → `onStart` → `bindService` → `onServiceConnected` → `triggerPrewarmIfReady` → `prepareStory`.

## Implementation Steps

### 1. Add `singleTop` launchMode to AndroidManifest.xml

In `app/src/main/AndroidManifest.xml`, modify the `StoryDetailActivity` declaration:

```xml
<activity
    android:name=".StoryDetailActivity"
    android:exported="false"
    android:launchMode="singleTop"
    android:parentActivityName=".MainActivity" />
```

**Why `singleTop` and not `singleTask` or `singleInstance`:**
- `singleTop` only prevents duplicate instances when the Activity is already at the **top** of the stack (which is our exact scenario — user is viewing StoryDetail and taps again).
- `singleTask` / `singleInstance` have aggressive side effects (clearing the back stack, creating new task affinity) that would break normal navigation.

### 2. Add `onNewIntent()` handler to StoryDetailActivity.kt

When the Activity is already at the top and a new intent arrives (e.g. user tapped a DIFFERENT story from a notification or deep link), we must handle the new intent gracefully.

```kotlin
override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent) // CRITICAL: update the activity's intent reference

    val newStoryId = intent.getIntExtra("story_id", 0)

    // If the incoming intent is for a DIFFERENT story, reload
    if (newStoryId != storyId && newStoryId != 0) {
        // Reset state for new story
        isPrewarmTriggered = false
        lastRenderedState = null
        storyContent = ""

        loadStoryFromIntent()
    }
    // If same story → do nothing (Activity is already showing it)
}
```

### 3. Refactor `loadStoryFromIntent()` — No code change needed

The existing `loadStoryFromIntent()` already reads from `intent` extras and calls `loadStoryContent()`. Since we call `setIntent(intent)` in `onNewIntent()`, the existing code will correctly read the new story's data.

## Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `app/src/main/AndroidManifest.xml` | MODIFY | Add `android:launchMode="singleTop"` |
| `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` | MODIFY | Add `onNewIntent()` override |

## Test

A single comprehensive test file: `StoryDetailSingleTopTest.kt`

The test verifies:
1. `singleTop` is declared in the manifest for `StoryDetailActivity`.
2. `onNewIntent()` with the same `storyId` does NOT reset `isPrewarmTriggered` or `storyContent`.
3. `onNewIntent()` with a different `storyId` DOES reset state and triggers reload.

## Notes

- This phase alone will NOT fully prevent `prepareStory` duplication (that's Phase 02's job). But it eliminates the root cause of multiple Activity instances being created from rapid taps.
- The `singleTop` behavior only applies when the Activity is at the top of the back stack. If the user navigates away and comes back, a new instance is still created (which is correct behavior).

---
Next Phase: [Phase 02 - ReadingService prepareStory Guard](./phase-02-reading-service-prepare-guard.md)
