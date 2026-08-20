# Phase 02: Prevent Post-Session Engine Re-binding & Story State Reset in ReadingService
Status: 🟢 Completed
Dependencies: Phase 01

## Objective
Ensure `ReadingService` resets `currentStoryId` upon natural playback completion and blocks unexpected auto-start/re-binding calls when a session has concluded.

## Requirements
### Functional
- In `ReadingService.kt`, when `TTSState.READY` or `TTSState.IDLE` is received after `PLAYING`, reset `currentStoryId = 0` so subsequent UI state syncs correctly show ready state and avoid re-trigger loops.
- Prevent duplicate `initTTS()` or `setVoice()` from re-binding Google TTS if the engine is already bound and active.
- Verify `StoryDetailActivity` UI receives clean single `READY` state without flip-flopping.

### Non-Functional
- Prevent memory churn and unnecessary Service wakeups after reading has naturally ended.

## Implementation Steps
1. In `ReadingService.kt`, update `onStateChange` listener to reset `currentStoryId = 0` and `preparingStoryId = 0` when transitioning from `PLAYING` to `READY` (natural end of story).
2. Ensure `SettingsActivity` or voice initialization checks don't re-instantiate `GoogleTTSManager` unnecessarily after playback finishes.
3. Validate that UI state in `StoryDetailActivity` stays stably on `PHÁT` (Ready) when audio ends.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt` - Reset currentStoryId on natural completion.
- `app/src/test/java/com/skul9x/doctruyen/service/ReadingServiceNaturalCompletionTest.kt` - Comprehensive unit test verifying natural completion lifecycle.

## Test Criteria
- [ ] Exactly one file-based unit test `ReadingServiceNaturalCompletionTest.kt` passes, verifying `currentStoryId` resets to 0 and state transitions cleanly to `READY`.

---
Done.
