# Phase 02: Adaptive Frame Capping (Anti-Hallucination) in ONNX Engine
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Port the `max_expected_frames` calculation from upstream `VieNeu-TTS-main` into `VieNeuOnnxEngine.kt` to prevent hallucination, tail runaway, and unwanted prolonged babbling on short sentences or single words when the model misses the EOS token.

## Requirements
### Functional
- Add a companion/helper function `maxExpectedFrames(phonemes: String): Int` that computes:
  ```
  effective_cap = min(maxNewFrames, maxExpectedFrames(phonemes))
  ```
  where `maxExpectedFrames(phonemes) = 24 + ceil(2.0 × effective_phoneme_len)`.
- Strip emotion cue tokens (`<|emotion_\d+|>`) and markup (`<en>`, `</en>`) when calculating effective phoneme length.
- If the phonemes represent a single word (≤ 24 phoneme chars and no emotion cues), cap hard at 13 frames (≈ 1.0s at 12.5 frames/s).
- Apply this frame ceiling **inside `inferWithTiming()` at the top of the autoregressive decode loop** (before line 251: `for (t in 0 until maxNewFrames)`), NOT in the outer `infer()` wrappers which only delegate.
- The existing `maxNewFrames: Int = 600` default parameter stays unchanged — it serves as the absolute upper bound. The adaptive cap further tightens it per-chunk via `min(maxNewFrames, maxExpectedFrames(phonemes))`.

> Note: Upstream Python uses `max_new_frames: int = 300`. DocTruyenApk uses `600` because the Android engine was originally designed without adaptive capping. With this change, the adaptive formula handles short inputs (≈13-144 frames), while 600 remains the safety ceiling for very long inputs.

### Non-Functional
- Zero overhead in inference hot loop; integer-only arithmetic where applicable.

## Implementation Steps
1. Add `maxExpectedFrames(phonemes: String): Int` companion function in `VieNeuOnnxEngine.kt`.
2. Inside `inferWithTiming()` (the overload at line 181 with the actual decode loop), compute `val effectiveMaxFrames = min(maxNewFrames, maxExpectedFrames(phonemes))` before the loop at line 251.
3. Replace `for (t in 0 until maxNewFrames)` with `for (t in 0 until effectiveMaxFrames)`.
4. Create and run exactly one comprehensive unit test `AdaptiveFrameCappingTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` — **[MODIFY]** Add `maxExpectedFrames()` function and apply adaptive cap in `inferWithTiming()` decode loop.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AdaptiveFrameCappingTest.kt` — **[NEW]** Comprehensive unit test verifying frame cap calculations across various input lengths and single words.

## Test Criteria
- [x] Exactly one file-based unit test `AdaptiveFrameCappingTest.kt` passes, validating single-word bounds (<=13 frames), short phrases, long texts, and emotion token exclusion from length calculations.

---
Next Phase: [Phase 03](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260821-1915-sync-vieneu-v3-turbo-upstream/phase-03-quote-awareness-and-punctuation-normalization.md)
