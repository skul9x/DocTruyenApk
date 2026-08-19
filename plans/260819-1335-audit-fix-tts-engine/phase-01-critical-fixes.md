# Phase 01: Critical Audit Fixes (C1, C2, C3)

Status: ✅ Completed  
Dependencies: None

## Objective

Fix the 3 critical performance issues identified in the audit report that directly impact UX through excessive RAM usage, GC pressure, and CPU waste.

## Requirements

### C1 — Network Response Body Read Twice (RAM & CPU Waste)

**File:** [`ApiService.kt`](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/network/ApiService.kt)

**Problem:** `response.peekBody(Long.MAX_VALUE).string()` on line 127 duplicates the entire HTTP response body into RAM just for logging. Combined with `HttpLoggingInterceptor.Level.BODY`, the response body is read 3 times (peekBody + logging interceptor + Gson parser).

**Root Cause:** Debug logging left enabled in production configuration with no size limit.

**Implementation Steps:**
1. [x] Wrap `peekBody` call inside `if (BuildConfig.DEBUG)` guard
2. [x] Limit peek size to `peekBody(2048L)` even in debug (truncate long responses)
3. [x] Change `HttpLoggingInterceptor.Level.BODY` to `.HEADERS` for release builds
4. [x] Keep `.BODY` only when `BuildConfig.DEBUG` is true

**Files to Modify:**
- `app/src/main/java/com/skul9x/doctruyen/network/ApiService.kt` — Lines 90-135

---

### C2 — ONNX CacheTensor Not Released (Memory Leak)

**File:** [`VieNeuOnnxEngine.kt`](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt)

**Problem:** In the autoregressive loop (lines 124-189), each decode step creates new `pastK`/`pastV` lists of `CacheTensor`. The old lists are simply replaced (lost reference), creating ~600×24 CacheTensors per chunk waiting for GC. Each CacheTensor contains large FloatArrays (shape `[1, numHeads, seqLen, headDim]`) with seqLen growing each step.

**Root Cause:** No explicit memory management for old cache tensors before replacement.

**Implementation Steps:**
1. [x] Before `pastK = newKList` and `pastV = newVList`, explicitly clear old list references
2. [x] Create a `CacheTensor.recycle()` method that nullifies internal data reference
3. [x] Implement a reusable pre-allocated buffer approach:
   - Pre-allocate ArrayList with capacity L (12) outside the loop
   - Before each iteration, clear and reuse the same list objects
   - Copy new tensor data into existing CacheTensor objects using `System.arraycopy` where possible
4. [x] Add `@JvmStatic` `CacheTensor.Pool` with fixed-size object pool (2×L = 24 objects) for K/V caches
5. [x] Call `System.gc()` hint every 100 frames as a safety net (low priority)

**Files to Modify:**
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` — Lines 104-189, 611-640

---

### C3 — `topKSample` Full Sort O(V log V) Instead of Partial Sort

**File:** [`VieNeuOnnxEngine.kt`](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt)

**Problem:** `topKSample` creates 1024 `IndexedLogit` objects, performs full `sortByDescending` (O(V log V)), but only needs top 25 elements. Called 17 times per frame × ~50 frames/sentence = 850 full sorts + ~870K object allocations per sentence.

**Root Cause:** Naive implementation using boxing objects and full sort instead of partial selection and primitive arrays.

**Implementation Steps:**
1. [x] Replace `Array<IndexedLogit>` with two parallel primitive arrays:
   - `IntArray(V)` for indices
   - `FloatArray(V)` for logit values (reuse `workingLogits` directly)
2. [x] Implement partial selection using a min-heap (PriorityQueue) of size K:
   - Iterate through all V logits once O(V)
   - Maintain a min-heap of top K elements O(V log K)
   - Extract sorted top-K from heap O(K log K)
   - Total: O(V log K) instead of O(V log V)
3. [x] Make the heap and working arrays **reusable class-level fields** to eliminate per-call allocations:
   - Pre-allocate `topKIndices: IntArray(K)` and `topKLogits: FloatArray(K)` as instance fields
   - Pre-allocate `workingLogitsBuffer: FloatArray(V)` as instance field
4. [x] Add unit test verifying identical sampling distribution before/after optimization

**Files to Modify:**
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` — Lines 350-438

---

## Test Criteria

### Automated Tests

- [x] **C1 Test:** Create `NetworkLoggingTest.kt` — verify that in a non-debug build config:
  - `peekBody` is NOT called
  - Logging interceptor level is `.HEADERS` not `.BODY`
  - Verify by checking log output does not contain full response body

- [x] **C2 Test:** Create `CacheTensorMemoryTest.kt` — verify:
  - After a full `infer()` call, old CacheTensors are recycled/cleared
  - No references to old CacheTensor data arrays remain accessible
  - Memory usage after infer() + GC is within expected bounds (< 2× baseline)

- [x] **C3 Test:** Create `TopKSampleTest.kt` — verify:
  - Given identical logits and random seed, the old and new `topKSample` produce the same distribution (statistical test over 10,000 runs)
  - No `IndexedLogit` objects are allocated (check via allocation-free assertion or reflection)
  - Performance benchmark: new implementation is ≥ 2× faster than old on 1024-element arrays

### Manual Verification
- Build app and run on device
- Open a story and start TTS playback
- Monitor memory in Android Studio Profiler:
  - Confirm no `peekBody` memory spikes on API calls
  - Confirm GC pressure is reduced during TTS playback
  - Confirm no OOM on low-RAM devices (3GB test)

---

## Notes
- C2 fix should be very careful not to break the autoregressive loop logic
- C3 fix must preserve the exact same sampling behavior (same temperature, topP, repetitionPenalty semantics)
- All C1 changes should preserve debug logging capability when `BuildConfig.DEBUG` is true

---
Next Phase: [Phase 02 — Warning Audit Fixes](./phase-02-warning-fixes.md)
