# Phase 02: Native Memory Lifecycle Management & Safe Resource Teardown
Status: ✅ Completed
Dependencies: Phase 01

## Objective
Prevent native memory accumulation across sessions/precision switches and resolve `MEMORY_LEAK_SUSPECTED` warnings by implementing explicit session & `SessionOptions` disposal, recycling intermediate `CacheTensor` instances in `acousticFrame`, resetting working buffers, adding Android memory trim callbacks (`onTrimMemory`), and guaranteeing safe teardown during engine switches and service termination.

## Requirements
### Functional
- In `OnnxSessionManager.kt`:
  - Retain `sessionOptions: OrtSession.SessionOptions` reference.
  - Enhance `close()` to ensure all 4 sessions (`prefillSession`, `decodeSession`, `acousticSession`, `codecSession`) are safely closed first, followed by closing `sessionOptions` (in proper sequential order according to ONNX Runtime Java lifecycle specifications).
  - Track `isClosed: Boolean` to guard against use-after-close or duplicate closes.
  - Nullify session reference pointers to allow native ORT allocators to reclaim resources.
- In `VieNeuOnnxEngine.kt`:
  - In `acousticFrame()`, explicitly recycle intermediate `pk` and `pv` `CacheTensor` instances across the 16 channel iterations and after final channel completion to prevent accumulating thousands of un-recycled buffer instances per sentence.
  - Enhance `close()` to:
    - Close the underlying `sessionManager`.
    - Clear the singleton `CacheTensor.Pool.clear()`.
    - Reset pre-allocated working buffers (`workingLogitsBuffer`, `topKIndicesBuffer`, `topKLogitsBuffer`, `probsBuffer`).
  - Add `isClosed: Boolean` guard check before running `inferWithTiming`.
- In `VieNeuTTSManager.kt`:
  - Ensure `reloadEngine()` and `shutdown()` execute strict sequential teardown: cancel active pre-warming jobs, atomically stop active audio pipelines, release pipeline references, close existing engines, clear `PrewarmedChunkCache`, and request garbage collection (`System.gc()`).
- In `ReadingService.kt`:
  - Implement `onTrimMemory(level: Int)`: when `TRIM_MEMORY_RUNNING_CRITICAL`, `TRIM_MEMORY_RUNNING_LOW`, or `TRIM_MEMORY_UI_HIDDEN` occurs and the service is not actively playing, clear `PrewarmedChunkCache` and `SeaG2P.clearCache()`.
  - Guarantee thorough resource release in `onDestroy()` and `onTaskRemoved()`.

### Non-Functional
- Prevent native memory footprint from escalating across engine reloads (delta heap returns to baseline).
- Ensure no `SIGSEGV` or race conditions occur when closing sessions while stopping playback.
- Maintain thread safety across coroutine boundaries during engine transitions.

## Implementation Steps
1. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt`:
   - Store `sessionOptions` reference in `OnnxSessionManager`.
   - Update `close()` to sequentially close all 4 `OrtSession` instances, then `sessionOptions.close()`, and set `isClosed = true`.
   - Add `isClosed: Boolean` property.
2. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`:
   - In `acousticFrame()`, call `pk.recycle()` and `pv.recycle()` on intermediate channel results.
   - In `close()`, invoke `sessionManager.close()`, `CacheTensor.Pool.clear()`, and reset working buffers.
   - Add `isClosed: Boolean` guard check before running `inferWithTiming`.
3. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`:
   - Refine `reloadEngine()` and `shutdown()` to ensure sequential and complete disposal of previous engine/pipeline instances before allocating new ones.
4. In `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt`:
   - Implement `onTrimMemory(level: Int)` to prune caches during memory pressure when idle.
5. Create comprehensive verification unit test `NativeMemoryLifecycleManagementTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt` - Session & SessionOptions closing, reference nullification, and closed state check.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` - Engine disposal, acousticFrame CacheTensor recycling, buffer reset, and CacheTensor pool clearance.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Sequential engine teardown and resource release during hot reload.
- `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt` - Add `onTrimMemory` and clean service disposal.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/NativeMemoryLifecycleManagementTest.kt` - Verification test for lifecycle and memory release.

## Test Criteria
- [ ] Exactly one file-based unit test `NativeMemoryLifecycleManagementTest.kt` passes, verifying:
  - `OnnxSessionManager.close()` safely disposes all 4 sessions and `sessionOptions`, flagging `isClosed = true`.
  - `VieNeuOnnxEngine.close()` clears `CacheTensor.Pool` and prevents subsequent inference on closed engine.
  - `VieNeuOnnxEngine.acousticFrame()` recycles intermediate `CacheTensor` instances properly.
  - `VieNeuTTSManager.reloadEngine()` and `shutdown()` completely release existing resources without leaks.
  - `ReadingService.onTrimMemory` releases idle pre-warm caches under memory pressure.

---
Next Phase: None (Final Phase)
