# Phase 03: Static JNI Tensor Pooling & Native Memory Churn Optimization (AUD-003)
Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Eliminate 9,600 redundant JNI `OnnxTensor` allocations and deallocations per chunk in the acoustic frame generation loop (`acousticFrame()`) by pre-allocating static channel position tensors (`position_ids`) and initial step 0 position tensor in `VieNeuOnnxEngine`, reducing native memory heap thrashing, reducing garbage collection pressure, and lowering the CPU Real-Time Factor (RTF).

## Scope
1. Implement static tensor pooling for channel position IDs ($ch \in 1..15$) in `VieNeuOnnxEngine.kt`.
2. Implement static tensor pooling for initial channel 0 position tensor (`posTensor0`, shape `[1, 2]`, values `[0L, 1L]`) in `acousticFrame()`.
3. Pre-allocate 16 position tensors (`longArrayOf(ch.toLong())` with shape `[1, 1]`) and 1 step-0 tensor (`longArrayOf(0L, 1L)` with shape `[1, 2]`) once during engine initialization or lazily upon first use.
4. Reuse these pre-allocated tensors in `acousticFrame()` across all 600 frames instead of repeatedly calling `OnnxTensor.createTensor()` and `posTensor.close()`.
5. Integrate defensive `try-finally` handling for per-channel dynamic `embTensor` allocations to eliminate native leaks on abort/cancellation.
6. Integrate safe lifecycle deallocation of pooled tensors inside `performResourceTeardown()`.

## Requirements
### Functional
- Synthesis outputs (tokens, generated acoustic codes, PCM audio samples) must be bit-identical to unpooled execution.
- Pooled position tensors must remain valid, open, and accessible throughout the entire lifetime of the engine.
- All pooled native tensors must be cleanly closed and freed upon `engine.close()` without leaving orphaned native C++ structures or triggering `SIGSEGV`.

### Non-Functional & Performance
- Zero JNI allocations for `position_ids` inside the 600-frame acoustic loop (eliminating 9,000 allocations for channels 1..15 and 600 allocations for channel 0, totaling 9,600 JNI allocations per chunk).
- Measurable reduction in frame synthesis CPU overhead and native memory fragmentation.

## How to Implement
1. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`:
   - Add properties for pooled position tensors:
     ```kotlin
     private var cachedChannelPosTensors: Array<OnnxTensor>? = null
     private var cachedPosTensor0: OnnxTensor? = null
     ```
   - Add thread-safe initializers:
     ```kotlin
     @Synchronized
     private fun getOrCreateChannelPosTensors(): Array<OnnxTensor> {
         val existing = cachedChannelPosTensors
         if (existing != null) return existing
         val nVq = config.nVq // 16
         val tensors = Array(nVq + 1) { ch ->
             OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ch.toLong())), longArrayOf(1, 1))
         }
         cachedChannelPosTensors = tensors
         return tensors
     }

     @Synchronized
     private fun getOrCreatePosTensor0(): OnnxTensor {
         val existing = cachedPosTensor0
         if (existing != null) return existing
         val tensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L, 1L)), longArrayOf(1, 2))
         cachedPosTensor0 = tensor
         return tensor
     }
     ```
   - In `acousticFrame()`:
     - Replace dynamic `posTensor0` allocation at step 0 (around line 583):
       ```kotlin
       // Before:
       // val posTensor0 = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L, 1L)), longArrayOf(1, 2))
       // ...
       // posTensor0.close()

       // After:
       val posTensor0 = getOrCreatePosTensor0()
       feed0["position_ids"] = posTensor0
       // posTensor0.close() is REMOVED: tensor is pooled across frames
       ```
     - Replace dynamic tensor allocation in the channel loop ($ch \in 1..15$):
       ```kotlin
       val channelPosTensors = getOrCreateChannelPosTensors()
       for (ch in 1 until config.nVq) {
           ...
           feed["position_ids"] = channelPosTensors[ch + 1]
           ...
           // posTensor.close() is REMOVED: tensor is pooled across frames
           // embTensor is safely closed via try-finally:
           val embTensor = OnnxTensor.createTensor(
               env,
               FloatBuffer.wrap(flatEmb, prevCode * H, H),
               longArrayOf(1, 1, H.toLong())
           )
           try {
               feed["token_emb"] = embTensor
               val out = sessionManager.acousticSession.run(feed)
               ...
           } finally {
               embTensor.close()
           }
       }
       ```
   - In `performResourceTeardown()`:
     Safely close all pooled tensors:
     ```kotlin
     cachedChannelPosTensors?.forEach { tensor ->
         try { tensor.close() } catch (_: Throwable) {}
     }
     cachedChannelPosTensors = null
     try {
         cachedPosTensor0?.close()
     } catch (_: Throwable) {}
     cachedPosTensor0 = null
     ```

## Files to Create/Modify
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`

## Dependencies
- Phase 01, Phase 02

## Constraints
- Must not close or mutate pooled tensors during active inference passes.
- Must ensure idempotent teardown if `close()` is invoked multiple times.

## Acceptance Criteria
- [x] Exactly 0 `OnnxTensor.createTensor` calls for position IDs are executed during the 600-frame acoustic loop.
- [x] Synthesized audio PCM matches baseline quality without distortion or acoustic artifacts.
- [x] Calling `close()` frees all 16 pooled tensors cleanly without memory leaks or crashes.
- [x] Passes `StaticTensorPoolingPerformanceTest`.

## Test Implementation
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/StaticTensorPoolingPerformanceTest.kt`
- Exactly one comprehensive file-based test suite verifying:
  1. Acoustic frame generation executes successfully using pooled channel position tensors.
  2. All pooled tensors remain initialized across multiple consecutive chunk inferences.
  3. Engine teardown safely releases all pooled position tensors and clears references.
