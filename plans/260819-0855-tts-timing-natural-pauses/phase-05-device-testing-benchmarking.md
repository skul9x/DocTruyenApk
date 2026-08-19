# Phase 05: Verification, Benchmarking & End-to-End Testing
Status: ✅ Completed
Dependencies: phase-01-timing-logger.md, phase-02-intelligent-segmentation.md, phase-03-natural-pause-audio-pipeline.md, phase-04-settings-configuration.md

## Objective
Validate the entire end-to-end reading flow with automated JVM tests and on-device instrumented tests. Measure and record accurate metrics for TTFT, pause length adherence, buffer health, and audio naturalness on multi-paragraph stories.

## Requirements

### Functional Requirements
- [x] Run automated JVM test suite covering all units:
  - `TtsMetricsLoggerTest.kt`
  - `SmartTextSegmenterTest.kt`
  - `TTSNaturalAudioPipelineTest.kt`
  - `UserConfigPauseTest.kt`
  - All existing `VieNeuOnnxEngineTest` & `VieNeuTokenizerTest`
- [x] Implement on-device instrumented test `TTSNaturalReadingDeviceTest.kt`:
  - Run full playback pipeline with actual ONNX model on Android test device.
  - Read story 118 ("Một trí khôn hơn trăm trí khôn") end-to-end.
  - Assert TTFT on physical device.
  - Assert minimal playback starvation/underrun events throughout entire story playback.
  - Verify inter-chunk pause lengths conform to configuration ($\pm 40\text{ms}$).
  - Verify all metrics are formatted and visible in `DebugLogger.getFormattedLogs()`.
- [x] Generate comprehensive benchmark report in `plans/260819-0855-tts-timing-natural-pauses/reports/benchmark_report.md`.

### Non-Functional Requirements
- 100% test pass rate across all new and existing test files.
- Zero crashes, memory leaks, or audio distortion during extended reading.

## Implementation Steps
1. [x] Create instrumented test `TTSNaturalReadingDeviceTest.kt` under `app/src/androidTest/java/com/skul9x/doctruyen/tts/vieneu/`.
2. [x] Execute unit test suite with `./gradlew testDebugUnitTest`.
3. [x] Run instrumented test on connected device via `adb` / `./gradlew connectedDebugAndroidTest`.
4. [x] Build and install release/debug APK on the phone and verify story reading live.
5. [x] Check and record the Debug Log output from Settings to verify formatted metrics.
6. [x] Save final benchmark summary report.

## Files to Create/Modify
- `app/src/androidTest/java/com/skul9x/doctruyen/tts/vieneu/TTSNaturalReadingDeviceTest.kt` - [NEW] Instrumented device test
- `plans/260819-0855-tts-timing-natural-pauses/reports/benchmark_report.md` - [NEW] Execution & benchmark results

## Test Criteria (File-Based Tests)
- [x] All unit tests in `app/src/test/java/...` pass with 0 failures.
- [x] `TTSNaturalReadingDeviceTest.kt` completes successfully on physical hardware.
- [x] Benchmark report confirms TTFT and underrun metrics.

---
Plan Complete.
