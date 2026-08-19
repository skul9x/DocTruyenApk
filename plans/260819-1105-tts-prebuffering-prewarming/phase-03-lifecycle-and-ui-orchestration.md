# Phase 03: UI & Lifecycle Orchestration in StoryDetailActivity
Status: ✅ Completed
Dependencies: [Phase 02](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-02-background-prewarming-engine.md)

## Objective
Hook background pre-warming into `StoryDetailActivity` so pre-synthesis triggers automatically upon fetching story text from the API, while ensuring clean lifecycle management (cancelling on back navigation, backgrounding, or story change) and updating UI buttons with seamless instant playback animations.

## Requirements
### Functional
- [x] In `StoryDetailActivity.kt`:
  - When `loadStoryContent()` succeeds and `content_text` is loaded: Call `readingService?.prepareStory(storyId, title, content_text)`.
  - If service is not bound yet at content load time: Queue the pre-warm call to execute immediately inside `onServiceConnected`.
  - When user taps `btnPlayPause` to play: If pre-warmed chunks exist, transition directly to `Playing` with 0ms visual delay.
  - When user exits screen (`onDestroy` / `onBackPressed`): Ensure background pre-warming job is promptly cancelled to prevent CPU drain or battery waste.
- [x] Visual indicators:
  - If user taps Play before pre-warming is complete, show subtle loading/buffering state for the remaining seconds without blocking user interactions.

### Non-Functional
- [x] **Battery & Thermal Efficiency:** Background pre-warming runs with low thread priority; strictly stops when activity is paused or destroyed.
- [x] **No UI Freezes:** 100% of G2P and inference executes off the main thread.

## Implementation Steps
1. [x] Update `StoryDetailActivity.kt`:
   - Add `triggerPrewarmIfReady()` helper called in `loadStoryContent` and `onServiceConnected`.
   - Update `handlePlayPause()` to handle instant pre-warmed playback.
   - Clean up pre-warm requests in `onDestroy()` and `onStop()`.
2. [x] Test UI state consistency across fast clicks, configuration changes, and backgrounding.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` - Pre-warm triggering, instant play handling, lifecycle bounds.
- `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt` - Lifecycle callbacks for pre-warming cancellation.

## File-Based Test Criteria
Create instrumentation / unit test `app/src/test/java/com/skul9x/doctruyen/StoryDetailPrewarmLifecycleTest.kt`:
- [x] `testStoryContentLoaded_triggersServicePrewarm`: Verify `prepareStory` is dispatched when network response is received.
- [x] `testActivityDestroy_cancelsActivePrewarmJob`: Verify destroying activity halts pre-warm task without resource leaks.

---
Next Phase: [Phase 04: Comprehensive Verification & Benchmarks](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-04-verification-benchmarking.md)
