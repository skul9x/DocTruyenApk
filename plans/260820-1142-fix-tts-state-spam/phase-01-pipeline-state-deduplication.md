# Phase 01: Pipeline State Deduplication & Buffering Loop Fix

Status: 🟢 Completed
Dependencies: None

## Objective
Prevent `TTSAudioPipeline.kt` from emitting repeated `PipelineState.Buffering` or `PipelineState.Playing` events on every iteration of the pre-buffering loop (every 20ms). Ensure `setState` only invokes callbacks when the pipeline state meaningfully changes or advances.

## Requirements
### Functional
- [x] Guard `TTSAudioPipeline.setState` against re-invoking `stateCallback` if `state == currentState`.
- [x] In the playback pre-buffering loop (`while (isActive && ...)`), emit `PipelineState.Buffering` only once upon entering buffering or when buffered chunk count changes, rather than unconditionally every 20ms loop tick.
- [x] Ensure `PipelineState.Playing` is not redundantly emitted for every chunk iteration if already in `Playing` state unless chunk metadata/progress changes through `notifyProgress`.

### Non-Functional
- [x] Eliminates CPU spinning and high-frequency dispatch on coroutine threads.
- [x] Zero audio playback regression or starvation latency.

## Implementation Steps
1. Modify `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`:
   - In `setState(state: PipelineState)`: Check if `currentState == state`. If identical, avoid redundant `stateCallback?.invoke(state)`.
   - In `playbackJob` pre-buffering loop: Only invoke `setState(PipelineState.Buffering(...))` if previous state was not buffering or if buffered chunks count actually changed.
   - Ensure transitions from `Synthesizing` -> `Buffering` -> `Playing` are atomic and clean.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` - Deduplicate state dispatches and debounce prebuffering loop.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PipelineStateDeduplicationTest.kt` - Single comprehensive test verifying deduplication.

## Test Criteria
- Single test: `PipelineStateDeduplicationTest.kt`
- Verifies that running pre-buffering loop for 200ms (10 loop iterations) results in exactly 1 buffering state emission rather than 10 spam callbacks.
- Verifies state transitions `Idle -> Synthesizing -> Buffering -> Playing -> Idle` trigger exactly the expected number of distinct state events.

---
Next Phase: [Phase 02: Service & UI Callback Deduplication & Notification Protection](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1142-fix-tts-state-spam/phase-02-service-ui-callback-debouncing.md)
