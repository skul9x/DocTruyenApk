# Phase 03: Acoustic Threading & Matrix Acceleration

Status: ✅ Completed  
Dependencies: Phase 01, Phase 02  
Target Issues: `PERF-002` (2.5B FLOPs Kotlin Loop), `PERF-007` (Acoustic Threading & JNI Tensor Overhead)

---

## 1. Objective
Drastically reduce acoustic frame computation latency and CPU cycle consumption by optimizing the execution configuration of the single-token acoustic ONNX model, eliminating repetitive JNI tensor allocations across codebook channels, and accelerating the acoustic projection matrix-vector multiplication (`sampleCode` GEMV $[1024 \times 768]$).

---

## 2. Scope
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt` (lines ~159, ~203)
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` (lines ~545-575, ~625-647)
- Test Suite: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AcousticThreadingAndProjectionTest.kt`

---

## 3. Requirements

### 3.1. Functional Requirements
1. **Dedicated Session Options for Acoustic Model:**
   - Configure a dedicated `OrtSession.SessionOptions` instance for `acousticSession` configured for single-threaded or minimal-threaded execution (`intra_op_num_threads = 1`), avoiding multi-core barrier synchronization overhead for 1-token tensor inputs.
2. **Persistent Tensor Reuse in Acoustic Frame Loop:**
   - Eliminate the repeated instantiation and closing of `OnnxTensor` instances (such as `posTensor` and token embedding buffers) across the 15 codebook iterations of each frame step.
   - Reuse direct byte buffers or persistent preallocated tensor handles to minimize JNI traversal count.
3. **Acoustic Projection GEMV Acceleration:**
   - Optimize the $[1024 \times 768]$ matrix-vector multiplication in `sampleCode`:
     - Utilize SIMD-friendly vectorization, contiguous array layouts, loop unrolling optimized for ART compiler vectorization, or direct hardware acceleration kernels.
     - Ensure the computed logits preserve mathematical equivalence to maintain the exact distribution required by QuickSelect Top-256 and nucleus sampling.

### 3.2. Non-Functional Requirements
- **Latency Reduction:** Reduce the total time spent in acoustic frame processing by at least 30% to 50%, lowering overall sentence RTF (Real-Time Factor).
- **JNI Call Reduction:** Cut repetitive JNI tensor creation and disposal calls by thousands of invocations per spoken paragraph.
- **Thermal Efficiency:** Lower CPU core temperatures and prevent thermal throttling during extended listening sessions.
- **Sampling Equivalence:** Absolute difference in output logits compared to baseline must remain within float rounding tolerance ($< 10^{-5}$), guaranteeing zero vocal tone or pronunciation distortion.

---

## 4. Constraints
- The solution must remain compatible across ARM64 (`arm64-v8a`) and test/emulator x86_64 environments without causing binary incompatibility or native linking failures.
- Must not alter the public behavior or randomness characteristics of `topKSample`.

---

## 5. Dependencies
- Phase 01 (Cancellation safety) and Phase 02 (Memory and streaming foundation).

---

## 6. Acceptance Criteria
1. `acousticSession` operates under a dedicated single-threaded configuration without inheriting oversized multi-threaded pools.
2. The inner 15-channel acoustic loop executes without repeatedly allocating and closing new position tensors per iteration.
3. Benchmarking 100 consecutive calls to `sampleCode` demonstrates measurable speedup over the v1.17 4-accumulator baseline.
4. Logits computed by the optimized GEMV match baseline values within $10^{-5}$ absolute error across all 1024 vocabulary entries.
5. All existing acoustic and sampling tests pass.

---

## 7. Comprehensive File-Based Test

**Test File:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AcousticThreadingAndProjectionTest.kt`  
**Required Verifications:**
1. `testAcousticSessionConfiguredWithSingleThread()`: Inspect session configuration options for `acousticSession` to confirm `intra_op_num_threads` is set to 1.
2. `testAcousticFrameTensorReuseIntegrity()`: Execute a multi-channel acoustic frame step and verify that output vectors and sampled codes are generated correctly without JNI allocation leaks.
3. `testAcousticProjectionLogitEquivalence()`: Run the accelerated GEMV projection against known arbitrary input vectors ($H = 768$) and assert that resulting logits for all 1024 vocab indices match the unrolled reference implementation within $10^{-5}$.
4. `testAcousticSamplingDeterminismWithSeed()`: Execute sampling with fixed random seeds and verify identical token selection sequences between baseline and optimized projection pathways.

