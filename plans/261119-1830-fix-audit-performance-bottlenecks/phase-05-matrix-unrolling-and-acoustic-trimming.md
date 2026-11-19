# Phase 05: Matrix Unrolling (ILP) & Acoustic Tail Trimming

Status: ✅ Completed  
Dependencies: Phase 04  

## Objective
Accelerate the 2.5 billion FLOPs acoustic codebook matrix projection by 2.5x–3.5x using CPU Instruction-Level Parallelism (ILP) multi-accumulator loop unrolling in Kotlin, and eliminate 2x–3x latency spikes on short dialogue utterances by trimming trailing hallucination bursts directly in the PCM acoustic domain instead of executing full neural re-inference loops.

## Scope
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`:
  - `sampleCode()`: Unroll the inner loop of matrix-vector multiplication with 4 independent float accumulators.
  - `inferWithTiming()`: Integrate direct acoustic tail trimming before attempting heavy full-pipeline neural resynthesis.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/BabbleGuard.kt`:
  - Implement `trimBabbleTail(pcm: FloatArray, targetSyllables: Int, sampleRate: Int): FloatArray` using computed speech burst boundary markers and a 5ms micro-fade.

## Requirements

### Functional Requirements
- [x] In `VieNeuOnnxEngine.kt` (`sampleCode`):
  - Refactor the inner dot product loop over $H = 768$ (which is evenly divisible by 4, 8, 16):
    ```kotlin
    // Loop unrolled with 4 independent accumulators to break pipeline dependencies
    var d0 = 0f
    var d1 = 0f
    var d2 = 0f
    var d3 = 0f
    var h = 0
    while (h < H) {
        val o = offset + h
        d0 += vec[h] * flatEmb[o]
        d1 += vec[h + 1] * flatEmb[o + 1]
        d2 += vec[h + 2] * flatEmb[o + 2]
        d3 += vec[h + 3] * flatEmb[o + 3]
        h += 4
    }
    logits[v] = (d0 + d1) + (d2 + d3)
    ```
- [x] In `BabbleGuard.kt`:
  - Expose `findSpeechBursts(pcm: FloatArray, ...): List<Pair<Int, Int>>` returning the raw hop intervals `(startHop, endHop)` for active bursts.
  - Implement `trimBabbleTail()`:
    - If detected bursts exceed `expectedSyllables`:
      - Identify the end hop of the `expectedSyllables`-th burst.
      - Add a 100ms natural decay tail.
      - Apply a 5ms micro-fade down to 0 at the cut point.
      - Return the trimmed `FloatArray`.
- [x] In `VieNeuOnnxEngine.kt` (`inferWithTiming`):
  - When `isBabbleSuspect()` flags excess bursts on short utterances ($\le 2$ syllables):
    - First attempt acoustic tail trimming via `BabbleGuard.trimBabbleTail()`.
    - If the trimmed candidate is clean (burst count matches expected syllables without boundary clicks), accept it immediately.
    - Only trigger neural `synthesizePass()` retry if acoustic trimming cannot produce a valid utterance.

### Non-Functional Requirements
- [x] Compute Acceleration: 2.5x–3.5x speedup in `sampleCode()` computation time on ARM Cortex-A55 / A78 CPU cores.
- [x] Short Utterance Latency: 60%–70% reduction in synthesis latency for 1-2 word utterances (from ~4.5s down to ~1.4s) when hallucination is detected.
- [x] Audio Quality: Seamless acoustic cutoff via 5ms micro-fade with zero pops, clicks, or abrupt waveform clipping.
- [x] Numerical Precision: Unrolled dot product matches the scalar dot product within IEEE 754 float rounding tolerance ($\Delta < 10^{-5}$).

## How to Implement

1. **Unroll Dot Product in `VieNeuOnnxEngine.kt`:**
   ```kotlin
   // In sampleCode()
   val flatEmb = flatAudioEmbeddings[ch]
   val H = config.hiddenSize // 768

   for (v in 0 until audioVocabSize) {
       val offset = v * H
       var d0 = 0f
       var d1 = 0f
       var d2 = 0f
       var d3 = 0f
       var h = 0
       while (h < H) {
           val o = offset + h
           d0 += vec[h] * flatEmb[o]
           d1 += vec[h + 1] * flatEmb[o + 1]
           d2 += vec[h + 2] * flatEmb[o + 2]
           d3 += vec[h + 3] * flatEmb[o + 3]
           h += 4
       }
       logits[v] = (d0 + d1) + (d2 + d3)
   }
   ```
2. **Implement `trimBabbleTail` in `BabbleGuard.kt`:**
   ```kotlin
   fun trimBabbleTail(
       pcm: FloatArray,
       targetSyllables: Int,
       sampleRate: Int = DEFAULT_SAMPLE_RATE,
       hopMs: Int = HOP_MS
   ): FloatArray? {
       val bursts = findActiveBursts(pcm, sampleRate, hopMs)
       if (bursts.size <= targetSyllables) return null // No excess bursts

       val targetBurst = bursts[targetSyllables - 1]
       val hopSize = (sampleRate * hopMs) / 1000
       val cutSample = min(pcm.size, (targetBurst.second * hopSize) + (sampleRate * 80) / 1000)

       val trimmed = pcm.copyOf(cutSample)
       // Apply 5ms micro-fade at end
       val fadeSamples = (sampleRate * 5) / 1000
       val fadeStart = max(0, trimmed.size - fadeSamples)
       for (i in fadeStart until trimmed.size) {
           val factor = 1.0f - (i - fadeStart).toFloat() / fadeSamples
           trimmed[i] *= factor
       }
       return trimmed
   }
   ```
3. **Integrate Trimming in `inferWithTiming`:**
   - Before the retry loop, test `BabbleGuard.trimBabbleTail(pass0.pcm, syllableCount)`.
   - If trimmed candidate resolves the babble suspect condition, return immediately without calling `synthesizePass()` again.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` - Unroll `sampleCode` dot product and integrate acoustic trimming.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/BabbleGuard.kt` - Add `findActiveBursts()` and `trimBabbleTail()`.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/MatrixOptimizationAndAcousticTrimmingTest.kt` - Single comprehensive verification test.

## Dependencies
- Phase 04 completed.

## Constraints
- Vector length $H$ must be checked to ensure it is a multiple of 4; handle any tail if $H \% 4 \ne 0$ gracefully.
- Acoustic trimming must only discard trailing excess bursts; never truncate initial or mid-speech syllables.

## Acceptance Criteria
- [x] Unrolled dot product produces identical logits within $10^{-5}$ float tolerance compared to sequential loop.
- [x] Micro-benchmarking proves $\ge 2\times$ throughput speedup for `sampleCode()`.
- [x] Utterances with trailing babble are trimmed to the correct syllable count without re-running neural pass.
- [x] All scenarios in `MatrixOptimizationAndAcousticTrimmingTest.kt` pass with 100% success rate.

## Single File-Based Test Criteria
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/MatrixOptimizationAndAcousticTrimmingTest.kt`
- Test Scenarios:
  1. `testUnrolledDotProductNumericalEquivalence`: Compare unrolled 4-accumulator dot product against unoptimized scalar loop over random 768-dim vectors across 1024 vocabulary rows.
  2. `testAcousticTailTrimmingOnSyntheticBabble`: Generate synthetic waveform with 1 main speech burst followed by 2 trailing babble bursts; assert `trimBabbleTail()` cuts after burst 1 and applies a smooth micro-fade.
  3. `testPreserveLegitimateBursts`: Assert that waveform matching exact syllable count is NOT altered by trimming.
  4. `testInferTimingTrimmingBypass`: Verify that when trimming succeeds, `TtsMetricsLogger` logs 0 neural retry passes.

## Review Checkpoint
Stop after running `MatrixOptimizationAndAcousticTrimmingTest.kt` and await user review before proceeding to Phase 06.

