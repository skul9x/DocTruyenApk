# Phase 03: AI Diagnostic Flag Rules & Actionable Recommendation Matrix

Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Upgrade `TtsMetricsLogger.kt` with a self-diagnostic AI rules engine. Automatically evaluate performance metrics against defined thresholds to generate standardized `⚠️ [AI_FLAG: ...]` tags and synthesize `🎯 AI ACTIONABLE RECOMMENDATIONS` alongside stage latency percentages.

## Requirements
### Functional
- [x] Upgrade `TtsChunkTiming` data structure:
  - Add fields: `backboneDurationMs: Long = 0L`, `codecDurationMs: Long = 0L`, `audioProcessDurationMs: Long = 0L`.
- [x] Upgrade `TtsSessionMetrics` data structure:
  - Add fields: `deviceProfile: DeviceProfile? = null`, `startMemory: MemorySnapshot? = null`, `endMemory: MemorySnapshot? = null`, `startThermal: ThermalSnapshot? = null`, `endThermal: ThermalSnapshot? = null`, `totalChars: Int = 0`.
- [x] Implement AI Flag Evaluation Matrix in `TtsMetricsLogger`:
  - `[AI_FLAG: SLOW_TTFT]`: Triggered when `timeToFirstAudioMs > 3500L`. Explains pre-warming or chunk sizing cause.
  - `[AI_FLAG: BUFFER_UNDERRUN]`: Triggered when `underrunCount > 0`. Explains chunk delay and starvation duration.
  - `[AI_FLAG: CPU_BOUND_RTF_HIGH]`: Triggered when `averageRtf > 1.0f`. Explains device throughput limitation.
  - `[AI_FLAG: THERMAL_THROTTLED]`: Triggered when `endThermal.thermalStatus >= SEVERE` or thermal throttling is detected.
  - `[AI_FLAG: MEMORY_LEAK_SUSPECTED]`: Triggered when native heap delta exceeds +50MB without baseline plateau.
  - `[AI_FLAG: CHUNK_TOO_LONG]`: Triggered when any chunk length exceeds 120 characters causing RTF spikes.
  - `[AI_FLAG: G2P_OVERHEAD]`: Triggered when G2P processing duration exceeds 50ms for any chunk.
- [x] Implement Actionable Recommendation Generator:
  - `1. [OPTIMIZE_PREWARM]`: Suggest calling `prepareStory()` on detail screen open.
  - `2. [OPTIMIZE_SEGMENTATION]`: Suggest splitting long sentences or tuning segmenter delimiters.
  - `3. [OPTIMIZE_PREBUFFER]`: Suggest adjusting `requiredDurationMs` / `requiredChunks` for mid-range SoCs.
  - `4. [OPTIMIZE_THREADS_QUANTIZATION]`: Suggest reducing ONNX threads or enabling INT8 quantization.
  - `5. [OPTIMIZE_COOLING]`: Suggest idle sleep between chunk inference if thermal throttling occurs.
  - `6. [OPTIMIZE_G2P_CACHE]`: Suggest vocabulary lookup caching.
- [x] Implement formatted string generator `formatAiDiagnosticReport(session: TtsSessionMetrics)`:
  - Matches the exact diagnostic format defined in `per.md`:
    - `[DEVICE PROFILE]`
    - `[SESSION METRICS]`
    - `[LATENCY PROFILING PER STAGE]`
    - `[ACTIVE AI FLAGS & BOTTLENECK ANALYSIS]`
    - `[🎯 AI ACTIONABLE RECOMMENDATIONS]`

### Non-Functional
- [x] Deterministic and structured output that any LLM can easily parse to identify bottlenecks and code changes.
- [x] Zero crash risk if thermal or memory stats are unavailable (gracefully handles null snapshots).

## Implementation Steps
1. Update `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TtsMetricsLogger.kt`:
   - Add telemetry models and snapshot fields.
   - Implement flag detection rule methods: `detectAiFlags(session: TtsSessionMetrics): List<AiDiagnosticFlag>`.
   - Implement recommendation rule methods: `generateRecommendations(flags: List<AiDiagnosticFlag>): List<String>`.
   - Implement stage latency percentage calculations: `calculateStagePercentages(session: TtsSessionMetrics)`.
   - Implement `formatAiDiagnosticReport(session: TtsSessionMetrics): String`.
   - Update `endSession()` to log the complete AI diagnostic report to `DebugLogger.log(TAG, report, LogType.TTS_TIMING)`.
2. Create unit test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AiDiagnosticMetricsTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TtsMetricsLogger.kt` - AI Diagnostic engine, flags, recommendations, and report formatting.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AiDiagnosticMetricsTest.kt` - Single test verifying all 7 diagnostic flag rules, recommendations generation, and report layout.

## Test Criteria
- Exactly one test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AiDiagnosticMetricsTest.kt`
- Verifies that simulating SLOW_TTFT, BUFFER_UNDERRUN, CPU_BOUND_RTF_HIGH, THERMAL_THROTTLED, MEMORY_LEAK_SUSPECTED, CHUNK_TOO_LONG, and G2P_OVERHEAD triggers their respective flags.
- Verifies that stage percentage breakdown correctly sums up to 100%.
- Verifies that actionable recommendations are appropriately generated for triggered flags.

---
Next Phase: [Phase 04: End-to-End Pipeline Integration & Session Diagnostic Log](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1205-ai-self-diagnostic-logging/phase-04-end-to-end-integration.md)
