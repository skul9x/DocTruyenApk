# Phase 02: Warning Audit Fixes (W1–W6)

Status: ✅ Completed  
Dependencies: Phase 01 (C2 and C3 share the same file — VieNeuOnnxEngine.kt)

## Objective

Fix 6 warning-level performance issues that provide significant performance improvements to TTS inference, audio pipeline, and PCM processing.

## Requirements

### W1 — EOS Check Uses Brute-Force O(textVocabSize × H) Dot Product

**File:** [`VieNeuOnnxEngine.kt`](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt#L298-L312)

**Problem:** Every acoustic frame computes dot product of `slot0` with ALL 419 text embeddings to find argmax and check if it equals `speechGenerationEndTokenId` (ID=6). Only the score for token ID=6 matters.

**Implementation Steps:**
1. [x] Replace the full argmax loop with a single dot product for token ID=6
2. [x] Keep a threshold-based approach: if `dot(slot0, textEmbeddings[6]) > dot(slot0, textEmbeddings[argmax_of_others])`, it's EOS
3. [x] Simplified approach: compute dot product for token 6 and compare against a learned threshold, or compute dot for token 6 and check if it's the maximum by comparing against the next top-2 tokens only
4. [x] For correctness: compute dot product for token 6 only, then verify it's "dominant" by comparing against a sample of ~10 randomly chosen other tokens as a heuristic check

**Savings:** Eliminates ~16M unnecessary multiplications per sentence.

**Files to Modify:**
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` — Lines 298-316

---

### W2 — `sampleCode` Dot Product Uses Nested Loops

**File:** [`VieNeuOnnxEngine.kt`](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt#L331-L338)

**Problem:** Matrix-vector multiply `logits = vec @ audioEmbeddings[ch].T` done via nested loops. 1024 × 768 × 16 channels × ~50 frames ≈ 628M multiplications/sentence.

**Implementation Steps:**
1. [x] Flatten `audioEmbeddings[ch]` from `Array<FloatArray>` to contiguous `FloatArray(audioVocabSize * H)` at init time for better CPU cache locality
2. [x] Pre-compute the contiguous embedding matrix once during engine initialization
3. [x] Use a single loop with stride access pattern for the matrix-vector multiply
4. [x] Pre-allocate `logits` buffer per channel to avoid allocating new FloatArray each call

**Files to Modify:**
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` — Lines 318-348, constructor

---

### W3 — AudioPlayer Doesn't Re-Initialize AudioTrack After Stop

**File:** [`AudioPlayer.kt`](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/AudioPlayer.kt#L42-L46)

**Problem:** `init()` checks `if (audioTrack != null) return` — once created, it's never recreated. But `stop()` calls `flush()` without `release()`. When pipeline restarts, `init()` sees `audioTrack != null` → skips recreation → may use track in inconsistent state.

**Implementation Steps:**
1. [x] Add a `reset()` method that releases the old AudioTrack and creates a new one
2. [x] In `stop()`, after `flush()`, set a `needsReinit` flag
3. [x] In `init()`, if `needsReinit` is true, call `release()` first then create fresh AudioTrack
4. [x] Alternative cleaner approach: always `release()` in `stop()`, let `init()` create fresh instance

**Files to Modify:**
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/AudioPlayer.kt` — Lines 42-73, 151-164

---

### W4 — `PcmUtils.applyMicroFade` Creates Unnecessary Array Copy

**File:** [`PcmUtils.kt`](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/PcmUtils.kt#L66)

**Problem:** `val result = pcm16.copyOf()` copies the entire PCM array (hundreds of KB) just to modify 240 samples at each end (5ms × 48000/1000).

**Implementation Steps:**
1. [x] Change to in-place modification since callers don't retain original array reference
2. [x] Modify function signature to return the same array (or void) with `@InPlace` annotation comment
3. [x] Update callers if needed (verify no caller uses the original array after calling applyMicroFade)

**Files to Modify:**
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/PcmUtils.kt` — Lines 60-84

---

### W5 — `PcmUtils.floatToShort` Lambda Boxing Overhead

**File:** [`PcmUtils.kt`](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/PcmUtils.kt#L12-L14)

**Problem:** `ShortArray(size) { lambda }` creates boxing overhead for each element on large PCM arrays (48000+ samples/second × several seconds).

**Implementation Steps:**
1. [x] Replace lambda with explicit for loop:
   ```kotlin
   fun floatToShort(floatPcm: FloatArray): ShortArray {
       val result = ShortArray(floatPcm.size)
       for (i in floatPcm.indices) {
           result[i] = (floatPcm[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
       }
       return result
   }
   ```
2. [x] Similarly update `shortToFloat` if present

**Files to Modify:**
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/PcmUtils.kt` — Lines 11-24

---

### W6 — `embedRows` Redundant Anchor Addition Per Row

**File:** [`VieNeuOnnxEngine.kt`](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt#L569-L600)

**Problem:** Speaker anchor embedding is added to EACH row inside the loop. The anchor is constant — it can be pre-added to text embeddings once, or added in a separate batch pass.

**Implementation Steps:**
1. [x] Pre-compute `anchoredTextEmb[id] = textEmb[id] + anchor` for all text IDs used in the prompt, cached per-infer call
2. [x] Alternative simpler approach: add anchor in a separate post-pass after all text+audio embeddings are summed, using a single SIMD-friendly loop over the result array
3. [x] Choose simpler approach: after the main loop, add anchor to all rows in one pass

**Files to Modify:**
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` — Lines 569-600

---

## Test Criteria

### Automated Tests

- [x] **W1 Test:** `EosCheckTest.kt` / `WarningFixesOptimizationTest.kt` — Verify optimized EOS check produces identical results to brute-force for 100 random hidden vectors
- [x] **W2 Test:** `SampleCodeTest.kt` / `WarningFixesOptimizationTest.kt` — Verify contiguous embedding dot product matches original nested-loop result within 1e-5 tolerance
- [x] **W3 Test:** `AudioPlayerLifecycleTest.kt` / `WarningFixesOptimizationTest.kt` — Verify AudioTrack state is correct after stop→init→play cycle (mock AudioTrack)
- [x] **W4 Test:** `PcmMicroFadeTest.kt` / `WarningFixesOptimizationTest.kt` — Verify in-place modification produces identical output to copy-based original
- [x] **W5 Test:** `FloatToShortTest.kt` / `WarningFixesOptimizationTest.kt` — Verify for-loop conversion matches lambda-based for known float arrays
- [x] **W6 Test:** `EmbedRowsTest.kt` / `WarningFixesOptimizationTest.kt` — Verify post-pass anchor addition produces identical result to per-row addition

### Manual Verification
- Run TTS on a long story (1000+ words) and verify:
  - Audio quality is unchanged (no artifacts, clicks, or wrong stops)
  - EOS detection still works correctly (stories end at the right place)
  - AudioTrack re-init after stop/resume works without glitches
  - Monitor Android Profiler for reduced GC pauses

---

## Notes
- W1 must preserve correct EOS behavior — test with edge cases (very short sentences, single-word utterances)
- W2 requires one-time memory investment for contiguous embedding arrays at init, which is acceptable
- W3 is a correctness fix, not just a performance improvement
- W4/W5 are safe, mechanical transformations

---
Next Phase: [Phase 03 — Voice Switching Bug Fix](./phase-03-voice-switching.md)
