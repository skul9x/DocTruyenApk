# Phase 01: Fix Utterance Progress Listener Reentrancy & Completion Deduplication
Status: ✅ Completed
Dependencies: None

## Objective
Prevent duplicate completion callbacks and silence utterance interference in GoogleTTSManager's `UtteranceProgressListener` to ensure exactly one clean transition to `READY` when all segments finish.

## Requirements
### Functional
- Handle both text segments (`seg_X`) and silent pause utterances (`silence_X`) cleanly without spurious re-triggering.
- Ensure that when the final chunk finishes, `GoogleTTSManager` resets its reading state and fires `onStateChange(READY)` exactly once.
- Ignore out-of-order `onStart` / `onDone` callbacks after `stop()`, `resetReadingState()`, or completion.

### Non-Functional
- Thread-safe state transition handling between Android TextToSpeech engine callbacks and main application state.

## Implementation Steps
1. In `GoogleTTSManager.kt`, refine `setupUtteranceListener()` to only advance on valid `seg_X` completion and ignore `silence_X` or stale utterance IDs.
2. Add a completion guard flag or atomic check in `GoogleTTSManager` to prevent duplicate `onDone` callbacks for the last segment.
3. Verify that `notifyStateChange` does not trigger redundant state broadcasts if already in `READY` or `IDLE`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/GoogleTTSManager.kt` - Enhance utterance listener logic and completion guard.
- `app/src/test/java/com/skul9x/doctruyen/tts/GoogleTTSCompletionTest.kt` - Comprehensive unit test verifying single completion event.

## Test Criteria
- [x] Exactly one file-based unit test `GoogleTTSCompletionTest.kt` passes, proving that completing all segments triggers `READY` once and ignores trailing callbacks.

---
Next Phase: [Phase 02](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1629-fix-googletts-completion-reentrancy/phase-02-service-state-guard-and-rebinding.md)
