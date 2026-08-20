# Phase 02: Service & UI Callback Deduplication & Notification Protection

Status: ✅ Completed
Dependencies: [Phase 01: Pipeline State Deduplication & Buffering Loop Fix](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1142-fix-tts-state-spam/phase-01-pipeline-state-deduplication.md)

## Objective
Ensure `VieNeuTTSManager`, `ReadingService`, and `StoryDetailActivity` properly guard against redundant state propagation. Specifically, prevent `NotificationManager.notify()` from being invoked when playback state is already active and unchanged, and prevent the Android UI thread from re-rendering the same UI buttons and icons in a hot loop.

## Requirements
### Functional
- [x] In `VieNeuTTSManager.notifyStateChange`: Deduplicate state transitions so `onStateChange` callback is only invoked if `currentState != state` (or during initial startup).
- [x] In `ReadingService.onStateChange`: Guard `updateNotification(state)` so that if `previousState == state` (e.g. `PLAYING -> PLAYING`), `NotificationManager.notify()` is skipped unless title/story metadata changed.
- [x] In `StoryDetailActivity.updateTTSUI`: Maintain a `lastRenderedState` cache to skip redundant widget updates if the activity is already displaying the target state.

### Non-Functional
- [ ] Eliminates excessive IPC calls to Android `system_server` via `NotificationManager`.
- [ ] Eliminates CPU cycles on Android Main Thread (UI thread).

## Implementation Steps
1. Modify `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`:
   - Enforce distinct state transition check before delegating to `onStateChange`.
2. Modify `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt`:
   - Only call `updateNotification(state)` on genuine state transitions (e.g. `READY -> PLAYING`, `PLAYING -> PAUSED`, `PAUSED -> PLAYING`).
   - Only notify `stateCallback` listeners if state has transitioned.
3. Modify `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt`:
   - Check `lastRenderedState` before re-executing `updateTTSUI(state)` to avoid layout invalidation churn.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`
- `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt`
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt`
- `app/src/test/java/com/skul9x/doctruyen/service/ReadingServiceStateDeduplicationTest.kt` - Single test verifying notification & callback suppression for duplicate states.

## Test Criteria
- Single test: `ReadingServiceStateDeduplicationTest.kt`
- Verifies that rapid duplicate `PLAYING` notifications from TTS manager result in only 1 notification build/dispatch and 1 UI callback invocation.

---
Next Phase: [Phase 03: End-to-End State Spam Regression Verification](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1142-fix-tts-state-spam/phase-03-end-to-end-verification.md)
