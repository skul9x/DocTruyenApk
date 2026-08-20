# Phase 02: Stage-by-Stage Latency Breakdown in Engine & Pipeline

Status: ✅ Completed
Dependencies: Phase 01

## Objective
Instrument `VieNeuOnnxEngine.kt` and `TTSAudioPipeline.kt` to measure the precise time spent in each distinct synthesis stage: G2P Phonemization, ONNX Backbone inference, ONNX Decoder Codec synthesis, and Audio/PCM post-processing (micro-fade, pause silence generation, and queue enqueue).

## Requirements
### Functional
- [x] In `VieNeuOnnxEngine.kt`:
  - Define `InferenceTiming(val backboneDurationMs: Long, val codecDurationMs: Long)`.
  - Define `EngineInferenceResult(val pcm: FloatArray, val timing: InferenceTiming)`.
  - Add/refactor `inferWithTiming(...)` to track prefill + acoustic auto-regressive frame loop duration (`backboneDurationMs`) vs MOSS codec decoding duration (`codecDurationMs`).
- [x] In `TTSAudioPipeline.kt`:
  - Measure `g2pDurationMs` (SeaG2P phonemize execution).
  - Measure `backboneDurationMs` and `codecDurationMs` from engine inference.
  - Measure `audioProcessDurationMs` (floatToShort conversion, applyMicroFade, and generateSilence concatenation).
  - Pass the detailed breakdown parameters to `TtsMetricsLogger.recordChunkSynthesis(...)`.

### Non-Functional
- [x] Maintain full backwards compatibility for existing callers of `engine.infer(...)`.
- [x] High-precision timestamps without introducing coroutine context switching delays.

## Implementation Steps
1. Update `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`:
   - Introduce `InferenceTiming` & `EngineInferenceResult`.
   - Measure `backboneDurationMs` (prefill + autoregressive loop) and `codecDurationMs` (MOSS codec `decodeCodes`).
   - Implement `inferDetailed(...)` / `inferWithTiming(...)`.
2. Update `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`:
   - Measure G2P time, Backbone time, Codec time, and Audio Process time per chunk.
   - Update `TtsMetricsLogger.recordChunkSynthesis(...)` invocation to include the new stage breakdown.
3. Create unit test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/StageLatencyBreakdownTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` - Separate backbone vs codec timing measurement.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` - Measure stage latency for G2P, Backbone, Codec, and Audio PCM processing.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/StageLatencyBreakdownTest.kt` - Single test verifying stage breakdown timing capture across pipeline synthesis.

## Test Criteria
- Exactly one test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/StageLatencyBreakdownTest.kt`
- Verifies that per-chunk latency metrics record non-zero durations for G2P, Backbone, Codec, and Audio Post-processing stages.
- Verifies that total inference duration equals backbone duration + codec duration.

---
Next Phase: [Phase 03: AI Diagnostic Flag Rules & Actionable Recommendation Matrix](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1205-ai-self-diagnostic-logging/phase-03-ai-diagnostic-engine.md)
