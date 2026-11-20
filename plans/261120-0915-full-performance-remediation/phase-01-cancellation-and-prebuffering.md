# Phase 01: Inference Cancellation & Audio Pre-buffering Stability

Status: ✅ Completed  
Dependencies: None  
Target Issues: `PERF-001` (Inference Cancellation), `PERF-005` (Pre-buffering Underrun)

---

## 1. Objective
Ensure the on-device neural text-to-speech inference pipeline can be cancelled immediately (< 50ms) when the user pauses, stops, seeks, or switches stories without leaking native ONNX tensors or blocking CPU threads, while guaranteeing continuous, glitch-free audio playback across short and long utterance boundaries by enforcing safe duration-based pre-buffering.

---

## 2. Scope
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` (lines ~388, ~479)
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` (lines ~322)
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` (lines ~284, ~378)
- Test Suite: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/InferenceCancellationAndPrebufferingTest.kt`

---

## 3. Requirements

### 3.1. Functional Requirements
1. **Cooperative Cancellation in Inference Loop:**
   - Neural synthesis in `VieNeuOnnxEngine` must check for cancellation cooperatively at each autoregressive frame step rather than executing the entire generation budget uninterruptibly.
   - When cancellation occurs, the autoregressive loop must terminate immediately, release all active native ONNX tensors, and rethrow or propagate cancellation appropriately.
   - Remove `withContext(NonCancellable)` wrapping around long-running `engine.inferWithTiming` and `eng.infer` invocations in `TTSAudioPipeline` and `VieNeuTTSManager`.
   - Ensure `inferWithTiming` safely unlocks its synchronization mechanism when aborted early without leaving corrupt engine state.
2. **Duration-Guaranteed Pre-buffering:**
   - Modify the pre-buffering condition in `TTSAudioPipeline` so that playback does not begin prematurely solely based on raw chunk count when the total accumulated audio duration is still below the safe playback margin.
   - Playback must only initiate when the accumulated audio duration meets or exceeds the target safe duration (`targetDurationMs`), or when all remaining chunks of the current story/utterance have already been buffered.

### 3.2. Non-Functional Requirements
- **Cancellation Latency:** Responsive stop/pause/seek turnaround under 50ms, eliminating the 2,000ms–4,000ms CPU freeze on playback control events.
- **Resource Safety:** Guarantee zero native memory leaks (`OrtValue` / `OrtSession.Result`) and no orphan thread utilization on `Dispatchers.Default` when cancellation triggers.
- **Audio Continuity:** Zero buffer underrun / audio starvation events during transitions from short dialogue utterances (e.g. 1-2 words) into long descriptive paragraphs.

---

## 4. Constraints
- Must not alter public API signatures of `ITTSManager` to maintain compatibility with existing controllers and mock tests.
- Must not invoke `OrtSession.close()` asynchronously across concurrent executing threads; use cooperative cancellation mechanisms (`isCancelled` checks and/or `OrtSession.RunOptions.setTerminate(true)`).
- Native tensors produced during in-flight steps must always be closed inside deterministic `try-finally` blocks.

---

## 5. Dependencies
- None. This is the foundational reliability phase.

---

## 6. Acceptance Criteria
1. When a synthesis coroutine is cancelled mid-utterance, execution in `VieNeuOnnxEngine` terminates within 1 frame step and CPU load returns to idle without waiting for the full 300+ frame ceiling.
2. A subsequent synthesis request invoked immediately after cancellation proceeds without deadlock or lock-contention stall.
3. Pre-buffering a series of short chunks (duration < 1.0s) does not prematurely start playback until the accumulated audio duration satisfies `targetDurationMs` (or all chunks are ingested), eliminating downstream buffer starvation.
4. All unit tests pass deterministically.

---

## 7. Comprehensive File-Based Test

**Test File:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/InferenceCancellationAndPrebufferingTest.kt`  
**Required Verifications:**
1. `testInferenceAbortsPromptlyOnCancellation()`: Launch `inferWithTiming` on a test dispatcher with a cancellation trigger after the 3rd frame, asserting that execution exits before completing the full max-frame ceiling and all intermediate tensors are closed cleanly.
2. `testRapidCancellationFollowedByImmediateReexecution()`: Cancel an ongoing synthesis pass and immediately execute a new synthesis call, verifying that lock acquisition succeeds without deadlock and yields valid PCM output.
3. `testPrebufferingEnforcesMinimumDurationThreshold()`: Feed sequential short audio chunks into `TTSAudioPipeline` and verify that the pipeline remains in the `Buffering` state until the accumulated duration threshold is reached, preventing premature streaming underrun.

