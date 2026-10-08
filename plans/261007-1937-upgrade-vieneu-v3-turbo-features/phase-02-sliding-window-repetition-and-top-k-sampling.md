# Phase 02: Sliding-Window Repetition Penalty & Top-K Sampling

Status: ✅ Completed
Dependencies: Phase 01

## Objective
Replace the unbounded `HashSet` repetition penalty history with a 64-frame sliding-window circular buffer (`RepetitionHistory`) in `VieNeuOnnxEngine.kt` to eliminate voice drift and tonal distortion in long sentences, and optimize the per-frame sampling loop with QuickSelect Top-K partitioning.

## Requirements
### Functional
- [x] Create `RepetitionHistory` data structure:
  - Supports `nChannels` (16 codebooks).
  - Each channel maintains a circular FIFO buffer of capacity `window = 64` frames (~2.5s audio).
  - Tracks token frequency using an array/map so tokens evicted from the window are no longer penalized.
  - Exposes fast lookup `contains(token)` or direct multiplier application.
- [x] Refactor `VieNeuOnnxEngine.kt`:
  - Replace `val hist = Array(config.nVq) { HashSet<Int>() }` with `RepetitionHistory(config.nVq, window = 64)`.
  - Update `sampleCode()` to query the channel's sliding window history instead of an unbounded set.
- [x] Implement QuickSelect / Partial Top-K Selection in Kotlin:
  - Implement a fast in-place `quickSelectTopK(logits, k = 256)` algorithm before sorting/softmax.
  - Sort and compute softmax only over the top 256 candidates instead of all 1024 vocabulary tokens.
  - Ensure sampled output probability distribution strictly mirrors the original top-K / nucleus behavior.

### Non-Functional
- [x] Prevent voice drift: Long utterances (100+ frames) retain consistent pitch and timbre without trailing whisper or vocal fatigue.
- [x] CPU Speedup: Per-frame sampling CPU execution time reduced by ~30% with zero heap allocations during the sampling loop.

## Implementation Steps
1. [x] Create `RepetitionHistory.kt` in `com.skul9x.doctruyen.tts.vieneu`.
2. [x] Integrate `RepetitionHistory` into `VieNeuOnnxEngine.kt`'s autoregressive loops (`inferStream` and chunk synthesis).
3. [x] Implement `partitionTopK` in `VieNeuOnnxEngine.kt` to filter working logits before sorting.
4. [x] Write and execute the single verification test: `SlidingWindowRepetitionAndSamplingTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/RepetitionHistory.kt` - Sliding window circular buffer per codebook channel.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` - Integration of RepetitionHistory and QuickSelect Top-K sampling.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/SlidingWindowRepetitionAndSamplingTest.kt` - Single comprehensive unit test.

## Single File-Based Test Criteria
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/SlidingWindowRepetitionAndSamplingTest.kt`
- Test Scenarios:
  1. `testRepetitionHistoryWindowEviction`: Assert that after pushing 65 tokens into a channel, the 1st token is evicted and no longer penalised.
  2. `testLongSentenceTonalStability`: Verify that over 120 frames of continuous synthesis, repetition penalty does not penalize valid recurring silence or vowel codes beyond 64 frames.
  3. `testQuickSelectTopKEquivalence`: Assert that `quickSelectTopK` yields identical top-256 elements compared to full sort.
  4. `testZeroAllocationSampling`: Assert that no arrays or objects are reallocated per step in the hot sampling loop.

## Review Checkpoint
Stop after running `SlidingWindowRepetitionAndSamplingTest.kt` and await user review before proceeding to Phase 03.
