# Phase 02: Zero-Copy KV-Cache Ping-Pong & Forced GC Elimination

Status: ✅ Completed  
Dependencies: Phase 01  

## Objective
Permanently eliminate the massive ~3.2GB Java Heap allocation churn per generated sentence and eliminate Stop-The-World `System.gc()` latency spikes in `VieNeuOnnxEngine.synthesizePass()` by chaining native `OnnxTensor` objects directly between autoregressive decode steps and acoustic channels via a dual-buffer ping-pong lifecycle pattern.

## Scope
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`:
  - `synthesizePass()`: Refactor prefill step and autoregressive generation loop.
  - `acousticFrame()`: Refactor channels 1..15 KV tensor passing.
  - Remove forced `System.gc()` at line 335.
  - Deprecate/remove `CacheTensor` and `CacheTensor.Pool` (which previously discarded float arrays).

## Requirements

### Functional Requirements
- [x] In `VieNeuOnnxEngine.synthesizePass()`:
  - Do NOT extract `past_k_0..11` and `past_v_0..11` into `FloatArray` from the prefill result.
  - Maintain an active `currentDecodeResult: OrtSession.Result?` reference initialized with `preResult`.
  - In each autoregressive loop step $t \in [0, \text{effectiveMaxFrames}-1]$:
    - Bind `decodeInputs["past_k_$i"] = currentDecodeResult.get(1 + i) as OnnxTensor`.
    - Bind `decodeInputs["past_v_$i"] = currentDecodeResult.get(1 + L + i) as OnnxTensor`.
    - Create `inputs_embeds` and `position_ids` tensors for step $t$.
    - Execute `val nextResult = sessionManager.decodeSession.run(decodeInputs)`.
    - Close the per-step input tensors (`inputs_embeds`, `position_ids`).
    - Copy the new hidden state from `nextResult.get(0)` into a single reusable `h` array (`FloatArray(H)`).
    - Close `currentDecodeResult` (which releases the old native tensors in C++).
    - Set `currentDecodeResult = nextResult`.
  - Enclose the entire decode loop in a `try-finally` block ensuring `currentDecodeResult?.close()` is executed upon normal completion, early break (`eos`), or sudden coroutine cancellation/exception.
- [x] Remove forced `System.gc()`:
  - Delete `if ((t + 1) % 100 == 0) { System.gc() }` at line 334–337.
- [x] In `VieNeuOnnxEngine.acousticFrame()`:
  - Apply the exact same zero-copy ping-pong pattern for channels $ch \in [1, 15]$:
    - Retain `currentAcousticResult: OrtSession.Result? = prefillAcousticResult`.
    - Pass `currentAcousticResult.get(1) as OnnxTensor` and `currentAcousticResult.get(2) as OnnxTensor` into `feed["past_k_0"]` and `feed["past_v_0"]`.
    - Run `val nextResult = sessionManager.acousticSession.run(feed)`.
    - Close input tensors, close `currentAcousticResult`, update `currentAcousticResult = nextResult`.
    - Ensure `finally { currentAcousticResult?.close() }`.
- [x] Remove the fake `CacheTensor` object pool (`CacheTensor.Pool`) and redundant `toOnnxTensor()` / `fromOnnxTensor()` conversions.

### Non-Functional Requirements
- [x] Heap Allocation Churn: Reduce temporary float memory allocations in autoregressive loop from >3,000MB down to <10MB per sentence.
- [x] Garbage Collection Frequency: 0 `System.gc()` calls.
- [x] Latency & Throughput: 25%–40% reduction in per-frame decode overhead due to zero JNI float buffer copies.
- [x] Native Safety: Strict adherence to RAII/try-finally lifecycle guarantees 0 native memory leaks (`OrtValue` leaks).

## How to Implement

1. **Refactor Prefill to Initialize `currentDecodeResult`:**
   ```kotlin
   // In synthesizePass()
   var currentDecodeResult: OrtSession.Result? = sessionManager.prefillSession.run(prefillInputs)
   try {
       val hiddenTensor = currentDecodeResult!!.get(0) as OnnxTensor
       val hiddenFb = hiddenTensor.floatBuffer
       val hiddenFlat = FloatArray(hiddenFb.remaining())
       hiddenFb.get(hiddenFlat)
       System.arraycopy(hiddenFlat, (tPrompt - 1) * H, h, 0, H)

       // 2. Autoregressive Loop
       for (t in 0 until effectiveMaxFrames) {
           val (codes, eos) = acousticFrame(...)
           frames.add(codes)
           if (eos) break

           // Build decode step slot
           val slotRow = IntArray(config.nVq + 1)
           slotRow[0] = config.speechGenerationStartTokenId
           System.arraycopy(codes, 0, slotRow, 1, config.nVq)
           val slotEmbeds = embedRows(arrayOf(slotRow), anchor)

           val seTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(slotEmbeds), longArrayOf(1, 1, H.toLong()))
           val posTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf((tPrompt + t).toLong())), longArrayOf(1, 1))

           val decodeInputs = HashMap<String, OnnxTensor>(2 + 2 * L)
           decodeInputs["inputs_embeds"] = seTensor
           decodeInputs["position_ids"] = posTensor

           // Directly reference native tensors from currentDecodeResult
           for (i in 0 until L) {
               decodeInputs["past_k_$i"] = currentDecodeResult.get(1 + i) as OnnxTensor
               decodeInputs["past_v_$i"] = currentDecodeResult.get(1 + L + i) as OnnxTensor
           }

           val nextResult = sessionManager.decodeSession.run(decodeInputs)
           seTensor.close()
           posTensor.close()

           // Read new hidden representation: reuse pre-allocated FloatBuffer or bulk copy into reusable 'h' array
           val nextHiddenTensor = nextResult.get(0) as OnnxTensor
           val nextHiddenFb = nextHiddenTensor.floatBuffer
           nextHiddenFb.get(h) // Reusable h array (FloatArray(H)) without allocating

           // Free previous step native memory and advance ping-pong pointer
           currentDecodeResult.close()
           currentDecodeResult = nextResult
       }
   } finally {
       currentDecodeResult?.close()
       currentDecodeResult = null
   }
   ```
2. **Refactor `acousticFrame()` Similarly:**
   - In `acousticFrame()`, initialize `currentAcousticResult = sessionManager.acousticPrefillSession.run(...)`.
   - In loop `for (ch in 1 until config.nVq)`:
     - Pass `currentAcousticResult.get(1) as OnnxTensor` and `currentAcousticResult.get(2) as OnnxTensor` into feed.
     - Execute `val nextResult = sessionManager.acousticSession.run(feed)`.
     - Close `currentAcousticResult`, update pointer to `nextResult`.
   - Wrap in `try-finally` ensuring `currentAcousticResult?.close()`.
3. **Clean Up `CacheTensor`:**
   - Remove unused `CacheTensor` and `CacheTensor.Pool` classes or deprecate them with zero remaining usages.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` - Zero-copy Result ping-pong implementation in `synthesizePass()` and `acousticFrame()`, removal of `System.gc()`.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/ZeroCopyKVCacheAndEngineTest.kt` - Single comprehensive verification test.

## Dependencies
- Phase 01 completed.
- ONNX Runtime Android Java API (`OrtSession.Result`, `OnnxTensor`).

## Constraints
- Tensors passed to `session.run()` MUST remain valid until `session.run()` returns; do NOT close `currentDecodeResult` before `nextResult` is returned.
- A `try-finally` block is mandatory around any loop holding an unclosed `OrtSession.Result` to prevent native memory leaks during coroutine cancellation.

## Acceptance Criteria
- [x] Autoregressive decode loop runs without allocating `FloatArray` for KV-Cache tensors.
- [x] No occurrences of `System.gc()` in `VieNeuOnnxEngine.kt`.
- [x] Acoustic frame channel loop runs zero-copy across all 16 codebook channels.
- [x] Synthetic benchmark confirms heap allocation per frame decreases by >95%.
- [x] All scenarios in `ZeroCopyKVCacheAndEngineTest.kt` pass with 100% success rate.

## Single File-Based Test Criteria
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/ZeroCopyKVCacheAndEngineTest.kt`
- Test Scenarios:
  1. `testPingPongResultChainingLifecycle`: Mock or execute dual-step tensor run and assert predecessor result is closed only after successor produces valid outputs.
  2. `testZeroFloatArrayAllocationInHotLoop`: Track memory allocations during 20 steps of decode loop and assert zero `FloatArray` allocations for KV-cache tensors.
  3. `testFinallyBlockClosesResultOnException`: Throw an exception mid-generation and verify that the active `OrtSession.Result` is reliably closed.
  4. `testAcousticFrameChaining`: Verify that channels 1 through 15 chain tensors natively without invoking `CacheTensor.fromOnnxTensor`.

## Review Checkpoint
Stop after running `ZeroCopyKVCacheAndEngineTest.kt` and await user review before proceeding to Phase 03.

