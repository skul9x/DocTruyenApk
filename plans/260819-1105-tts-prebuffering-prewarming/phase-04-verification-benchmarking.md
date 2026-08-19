# Phase 04: Comprehensive Verification & Benchmarking
Status: 🟢 Completed
Dependencies: [Phase 01](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-01-adaptive-lookahead-prebuffering.md), [Phase 02](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-02-background-prewarming-engine.md), [Phase 03](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-03-lifecycle-and-ui-orchestration.md)

## Objective
Run end-to-end automated unit tests and execute on-device TTS benchmarks across representative Vietnamese story samples (short title + multi-paragraph stories) to prove 0 buffer underruns, sub-50ms TTFT for pre-warmed sessions, and generate a final benchmark report.

## Requirements
### Functional
- [x] Run full test suite:
  - `AdaptivePrebufferingTest.kt`
  - `PrewarmedChunkCacheTest.kt`
  - `StoryDetailPrewarmLifecycleTest.kt`
  - Existing `SmartTextSegmenterTest`, `SeaG2PTest`, `VieNeuOnnxEngineTest`.
- [x] Execute real story narration simulation via ADB logcat telemetry monitoring:
  - Benchmark cold playback vs. pre-warmed playback.
  - Verify `TtsMetricsLogger` outputs:
    - Pre-warmed TTFT: $\le 50\text{ms}$
    - Buffer Underruns: `0`
    - Mean RTF: $\le 0.50\text{x}$ (or realtime on mobile hardware)
    - Memory usage: $\le 530\text{MB}$ total app footprint.

### Non-Functional
- [x] Generate comprehensive Markdown benchmark report under `plans/260819-1105-tts-prebuffering-prewarming/reports/benchmark_report.md`.

## Implementation Steps
1. [x] Run `./gradlew testDebugUnitTest` to validate all unit test assertions pass.
2. [x] Build release APK with `./gradlew :app:assembleRelease` and install onto connected target device.
3. [x] Capture and parse logcat output during actual story reading.
4. [x] Format benchmark metrics comparison table before vs. after.

## Files to Create/Modify
- `plans/260819-1105-tts-prebuffering-prewarming/reports/benchmark_report.md` - [NEW] Verification results and metrics comparison.

## File-Based Test Criteria
- [x] `./gradlew testDebugUnitTest` exits with 0 failures.
- [x] Logcat confirms `[TTS Session Summary] Buffer Underruns: 0` for 100% of tested stories.
- [x] TTFT on pre-warmed story is verified $\le 50\text{ms}$.
