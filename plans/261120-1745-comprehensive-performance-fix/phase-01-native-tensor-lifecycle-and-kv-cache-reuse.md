# Phase 01: Native Tensor Lifecycle & Zero-Copy KV Cache Reuse

**Status:** ✅ Completed  
**Dependencies:** None  

## Objective
Eliminate the creation and destruction of dozens of native `OnnxTensor` instances and Java `FloatArray` allocations per autoregressive decode frame. Keep the Key-Value (KV) cache resident within native ONNX memory across autoregressive steps, stop copying intermediate float buffers to the JVM heap, and remove explicit synchronous `System.gc()` pauses.

## Scope
- Modify `VieNeuOnnxEngine.kt` autoregressive decode loop (`synthesizePass`) and per-channel acoustic frame synthesis (`acousticFrame`).
- Refactor the KV cache retention mechanism to pass native tensors directly between successive decode steps without JVM heap round-trips.
- Remove all periodic calls to `System.gc()` inside inference loops.
- Re-architect `CacheTensor` and its pooling mechanism so that memory reuse is real and allocation-free.

## Requirements
### Functional
1. **Inference Output Equivalence**: Preserved exact acoustic token generation, EOS detection, syllable counts, and audio waveform synthesis identical to existing validated models.
2. **Native Tensor Handover**: In each autoregressive step, previous step output KV tensors must be supplied directly as inputs to the next step while retaining safe C++ lifecycle bounds without use-after-free or memory leaks.
3. **No Garbage Collection Stalls**: Ensure smooth, uninterrupted loop execution without any forced Stop-the-World `System.gc()` calls during decoding.
4. **Safe Resource Teardown**: All retained native tensors, temporary buffers, and ONNX results must be cleanly closed upon synthesis completion, cancellation, or error.

### Non-Functional
- Native Heap allocation delta over a 60-second synthesis session must remain bounded (delta < 20MB, eliminating the observed +314MB growth).
- Zero JVM array allocations (`FloatArray`) for KV cache persistence across decode steps.
- Zero Stop-the-World pauses during continuous multi-sentence chunk synthesis.

## Constraints
- Must maintain full compatibility with the existing 12-layer Transformer backbone architecture (`vieneu_decode_step.onnx`, `vieneu_prefill.onnx`, `vieneu_acoustic_cached.onnx`).
- Thread-safety of `VieNeuOnnxEngine` across concurrent read requests must be strictly preserved via existing synchronization locks.
- Do not modify ONNX model binaries or change model input/output tensor signature contracts.

## Acceptance Criteria
- All 12 layers of Key and Value tensors transition across decode iterations in native space without serializing into Java primitive float arrays.
- A 26-chunk story synthesis sequence executes without triggering explicit `System.gc()`.
- Total native heap growth remains flat across repeated synthesis passes.
- Audio synthesis output matches benchmark expectations for speech naturalness and phoneme articulation.

## Comprehensive File-Based Test
- **File:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/NativeTensorReuseAndZeroGcTest.kt`
- **Verification Strategy:**
  1. `testKvCacheNativeTensorRetention`: Validates that subsequent decode steps accept previous output tensors directly, producing valid logits without allocating intermediate `FloatArray`s per layer.
  2. `testZeroSystemGcInferenceLoop`: Verifies via execution profiling or bytecode inspection that no `System.gc()` is invoked during autoregressive loops.
  3. `testNativeTensorLifecycleCleanupOnCancel`: Verifies that aborting synthesis mid-stream properly closes all active native tensors without leaking memory or throwing native segment violations.
  4. `testAcousticFrameMemoryReuse`: Asserts that intermediate tensors across channels 0 to 15 reuse allocated memory slots without per-channel reallocation churn.

