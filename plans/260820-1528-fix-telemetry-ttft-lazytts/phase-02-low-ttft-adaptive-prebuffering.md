# Phase 02: Low TTFT Adaptive Pre-Buffering for INT8 & Fast SoCs
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Adaptively adjust the initial audio pre-buffering duration threshold in `TTSAudioPipeline.kt` based on quantization mode (`INT8` vs `FP32`) and device capability, dropping the initial pre-buffer duration requirement from `7,500ms` down to `2,000ms - 2,500ms` (or 1-2 chunks) for fast INT8 execution, thereby reducing Time-To-First-Audio (TTFT) from >9s to <2.5s while avoiding buffer underruns.

## Requirements
### Functional
- [x] Add adaptive pre-buffer calculation helper:
  - In INT8 mode or multi-core CPU (>=6 cores), initial target pre-buffer duration is lowered to `2,000ms - 2,500ms` (or 1 chunk if single chunk duration >= 1,500ms).
  - In FP32 mode or lower-end CPU (<4 cores), retain safe default (e.g. `5,000ms - 7,500ms`).
- [x] In `TTSAudioPipeline.kt`, calculate `targetDurationMs` dynamically using the active engine quantization and device profile.
- [x] Ensure seamless start of playback immediately once the adaptive duration or first qualified chunk is ready.
- [x] Retain existing anti-starvation logic for subsequent chunks in the stream.

### Non-Functional
- [x] Prevent audio stutter / buffer starvation (`underrunCount == 0`).
- [x] Sub-2.5s TTFT on Snapdragon 8 Gen 3 with INT8 models.

## Implementation Steps
1. [x] Update `TTSAudioPipeline.kt`:
   - Introduce `computeAdaptiveTargetDurationMs(quantizationType, cpuCores, remainingChunks)` function.
   - Adjust `minPreBufferDurationMs` and target pre-buffering condition to check either target buffered duration OR minimum buffered chunks (e.g., 1 chunk when RTF < 1.0x).
2. [x] Update `VieNeuTTSManager.kt` or `ReadingService.kt` to pass quantization info to `TTSAudioPipeline` during initialization and dynamic precision switching.
3. [x] Create a dedicated unit test `AdaptivePreBufferLowTtftTest.kt`.
4. [x] Run the single unit test to verify that:
   - For INT8 on multi-core SoC, pre-buffering completes after 1 or 2 chunks (duration ~2-3s) instead of waiting for 3+ chunks (7.5s+).
   - For FP32 or constrained environments, safe fallback duration is respected.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` - Implement adaptive low-TTFT pre-buffering thresholds
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Propagate active precision / prebuffer tuning
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AdaptivePreBufferLowTtftTest.kt` - [NEW] Verification test

## Test Criteria
- [x] Verify that an INT8 pipeline with 1 chunk of 2,440ms audio duration transitions from `Buffering` to `Playing` immediately without waiting for additional chunks.
- [x] Verify that fallback mode correctly enforces higher threshold when running in FP32 / low core profile.

---
Next Phase: [Phase 03: Lazy Google TTS Initialization in Settings](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1528-fix-telemetry-ttft-lazytts/phase-03-lazy-googletts-settings.md)
