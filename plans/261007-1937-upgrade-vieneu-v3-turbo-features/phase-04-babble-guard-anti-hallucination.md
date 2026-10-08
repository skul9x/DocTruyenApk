# Phase 04: Babble Guard Anti-Hallucination & Burst Detection

Status: ✅ Completed
Dependencies: Phase 03

## Objective
Implement `BabbleGuard` in the Kotlin audio synthesis pipeline to detect and discard hallucinations, trailing babble, or boundary static in ultra-short dialogue utterances (1–2 syllables like *"Vâng."*, *"Hả?"*, *"Ừ."*).

## Requirements
### Functional
- [x] Implement audio energy envelope analyzer in `BabbleGuard.kt`:
  - Calculate RMS envelope with 10ms hops (`sr = 48000`).
  - Compute active speech bursts using energy threshold (`_BURST_THRESH_DB = -24dB` relative to peak).
  - Count speech bursts with minimum duration (60ms) and minimum inter-burst gap (80ms).
- [x] Implement `isBabbleSuspect()` heuristic:
  - Triggered only on short utterances (`syllableCount <= 2`).
  - Suspect if `burstCount > syllableCount` (model hallucinated extra syllables/trailing noises).
  - Suspect if `syllableCount <= 2` and generated frames reached the frame ceiling (`frames >= capFrames - 1`).
- [x] Add retry loop in `VieNeuOnnxEngine.kt`:
  - When `isBabbleSuspect` is true, resynthesize up to 2 times (`maxRetries = 2`) with a varied seed/temperature.
  - Employ `babblePrefer` comparator: select the candidate with fewer excess bursts and shorter duration.

### Non-Functional
- [x] Execution efficiency: Burst analysis takes < 2ms on typical short PCM chunks (< 2.0s).
- [x] Guard activation scope: Long chunks (> 3 syllables) bypass energy envelope checks to maintain zero overhead on narrative paragraphs.

## Implementation Steps
1. [x] Create `BabbleGuard.kt` in `com.skul9x.doctruyen.tts.vieneu`.
2. [x] Integrate energy burst detection and retry loop inside `VieNeuOnnxEngine.kt`'s chunk synthesis method.
3. [x] Add metrics logging to `TtsMetricsLogger.kt` when a babble retry occurs.
4. [x] Write and execute the single verification test: `BabbleGuardAntiHallucinationTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/BabbleGuard.kt` - Burst detection & babble heuristic engine.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` - Integration of retry synthesis loop.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TtsMetricsLogger.kt` - Telemetry tracking for babble retries.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/BabbleGuardAntiHallucinationTest.kt` - Single comprehensive unit test.

## Single File-Based Test Criteria
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/BabbleGuardAntiHallucinationTest.kt`
- Test Scenarios:
  1. `testBurstCountCalculation`: Verify burst counting on synthetic 1-burst vs 2-burst audio signals.
  2. `testBabbleSuspectDetection`: Assert that 1-syllable utterance with 2 speech bursts is flagged as suspect.
  3. `testFrameCapCeilingTrigger`: Assert that 1-syllable utterance hitting frame cap (13 frames) triggers babble suspect.
  4. `testCandidateSelectionPreference`: Verify `babblePrefer` correctly selects clean 1-burst candidate over 2-burst noisy candidate.

## Review Checkpoint
Stop after running `BabbleGuardAntiHallucinationTest.kt` and await user review.
