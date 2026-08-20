# Phase 01: Fix TtsMetricsLogger TotalChars & Telemetry Accuracy
Status: 🟢 Completed
Dependencies: None

## Objective
Fix the character accumulation bug in `TtsMetricsLogger.kt` so that `totalChars` reflects the complete sum of characters across all synthesized chunks (or whole story length), and ensure the AI Diagnostic report correctly calculates throughput (`chars/sec`) without locking to Chunk 1.

## Requirements
### Functional
- [x] Fix `updateSessionSnapshot()` in `TtsMetricsLogger.kt` so `totalChars` accumulates across all `chunkTimings` dynamically.
- [x] Support passing initial `totalChars` in `startSession` or dynamically resolving `chunkTimings.sumOf { it.charCount }`.
- [x] Correct the throughput formula in `formatAiDiagnosticReport()` to calculate `totalChars / totalInferenceTimeSec`.
- [x] Clean up any duplicate logging for button click interactions.

### Non-Functional
- [x] Thread safety: Ensure atomic and concurrent access to metrics via existing synchronization.
- [x] Zero performance overhead during TTS inference loop.

## Implementation Steps
1. [x] Inspect and update `TtsMetricsLogger.kt`:
   - Store `initialStoryChars` if provided in `startSession`.
   - In `updateSessionSnapshot()`, compute `val sumChars = chunkTimings.sumOf { it.charCount }` and set `totalChars = if (initialStoryChars > 0) initialStoryChars else sumChars`.
   - Update `formatAiDiagnosticReport()` to display exact total chars and correct throughput.
2. [x] In `StoryDetailActivity.kt`, remove redundant `DebugLogger.log` in `handlePlayPause()` that duplicates the click listener log.
3. [x] Create a dedicated unit test `MetricsLoggerTotalCharsAndDiagnosticTest.kt`.
4. [x] Run the single unit test to verify that `totalChars` correctly accumulates across 1, 2, ..., N chunks and that throughput is computed accurately.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TtsMetricsLogger.kt` - Update totalChars logic and diagnostic formatting
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` - Remove duplicate click log
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/MetricsLoggerTotalCharsAndDiagnosticTest.kt` - [NEW] Verification test

## Test Criteria
- [x] Test that adding Chunk 1 (19 chars) results in 19 chars, then adding Chunk 2 (67 chars) increases total to 86 chars, and adding Chunk 3 (67 chars) increases total to 153 chars.
- [x] Test that `formatAiDiagnosticReport()` outputs the correct total chars and non-zero accurate throughput.

---
Next Phase: [Phase 02: Low TTFT Adaptive Pre-Buffering](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1528-fix-telemetry-ttft-lazytts/phase-02-low-ttft-adaptive-prebuffering.md)
