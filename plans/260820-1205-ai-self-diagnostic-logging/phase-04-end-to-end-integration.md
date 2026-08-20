# Phase 04: End-to-End Pipeline Integration & Session Diagnostic Log

Status: ✅ Completed
Dependencies: Phase 01, Phase 02, Phase 03

## Objective
Wire the complete telemetry lifecycle into `TTSAudioPipeline.kt` and `VieNeuTTSManager.kt`. Capture device profile, start memory/thermal snapshots at session start, record all stage-by-stage latencies during reading, and trigger end-of-session diagnostic report generation upon completion.

## Requirements
### Functional
- [ ] Connect `TtsMetricsLogger.startSession(storyId, storyTitle, context)` in `TTSAudioPipeline.startReadingChunks`:
  - Automatically queries `DevicePerformanceHelper` to populate `DeviceProfile`, `startMemory`, and `startThermal`.
- [ ] Ensure all chunk synthesis events record full stage metrics (G2P, Backbone, Codec, Audio Processing, Dwell time, and starvation delays).
- [ ] Connect `TtsMetricsLogger.endSession(context)` on playback completion or stop:
  - Captures `endMemory` and `endThermal` via `DevicePerformanceHelper`.
  - Computes memory and thermal deltas.
  - Outputs the full `========================== 📊 AI PERFORMANCE DIAGNOSTIC ==========================` diagnostic block into `DebugLogger`.
- [ ] Ensure existing tests in `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/` remain green without regressions.

### Non-Functional
- [ ] Seamless integration without adding GC pressure or audio stutter.
- [ ] Full resilience against null contexts in background service or test harness.

## Implementation Steps
1. Update `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`:
   - Pass context and engine configuration to `TtsMetricsLogger.startSession(...)`.
   - Pass context to `TtsMetricsLogger.endSession(...)`.
2. Update `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` if needed for session initialization parameters.
3. Create unit test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AiSelfDiagnosticIntegrationTest.kt`.
4. Run regression verification on existing test suite to ensure all unit tests pass cleanly.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` - Hook session start/end lifecycle with telemetry snapshots.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Forward runtime engine/quantization metadata.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AiSelfDiagnosticIntegrationTest.kt` - Single test verifying end-to-end telemetry lifecycle and formatted diagnostic report generation.

## Test Criteria
- Exactly one test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AiSelfDiagnosticIntegrationTest.kt`
- Verifies that initiating reading chunks, playing through audio, and completing playback logs a complete `AI PERFORMANCE DIAGNOSTIC` report containing:
  1. `[DEVICE PROFILE]`
  2. `[SESSION METRICS]`
  3. `[LATENCY PROFILING PER STAGE]`
  4. `[ACTIVE AI FLAGS & BOTTLENECK ANALYSIS]`
  5. `[🎯 AI ACTIONABLE RECOMMENDATIONS]`

---
End of Plan.
