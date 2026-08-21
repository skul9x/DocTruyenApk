# Phase 02: Optimize Acoustic Engine Dummy Tensor Allocation & NPZ Stream I/O
Status: ✅ Completed
Dependencies: phase-01-fix-cancellation-and-cpu-threading.md

## Objective
Optimize acoustic frame synthesis and model asset extraction by:
1. Reusing pre-allocated empty dummy tensors (`emptyPastK`, `emptyPastV`) across frame iterations in `VieNeuOnnxEngine.kt` to eliminate repeated JNI tensor allocation/deallocation overhead.
2. Optimizing `NpzReader.kt` to allocate byte arrays directly when Zip entry size is known, reducing `ByteArrayOutputStream` resizing allocations.

## Requirements

### Functional Requirements
- [x] **Acoustic Dummy Tensor Reuse**: In `VieNeuOnnxEngine.kt`, provide reusable dummy tensors for empty past key/value states (`shape: [1, nHLoc, 0, hdLoc]`), avoiding recreating and destroying them on every frame iteration in `acousticFrame()`.
- [x] **Engine Lifecycle Cleanup**: Clean up any persistent tensors created for dummy states when the engine or its session manager is closed.
- [x] **Direct Buffer Allocation in NPZ Reader**: In `NpzReader.kt`, update `readAllBytes()` to accept an optional `expectedSize: Long = -1L`. If `expectedSize > 0`, allocate the exact `ByteArray(expectedSize.toInt())` directly and read from the stream without `ByteArrayOutputStream` resizing.

### Non-Functional Requirements
- [x] Reduce JNI bridge allocations by ~100-300 allocations per speech synthesis chunk.
- [x] Reduce GC churn and heap allocations during initial `.npz` head weights loading (`vieneu_v3_heads.npz`).

## Implementation Steps
1. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`:
   - Cache `emptyPastK` and `emptyPastV` tensors (or lazy-instantiated instances) that match `nHLoc` and `hdLoc` shape `[1, nHLoc, 0, hdLoc]`.
   - In `acousticFrame()` step 0, reuse the cached dummy tensors in `feed0` without closing them after every single step.
   - Implement `close()` / cleanup to dispose of the cached dummy tensors when the engine lifecycle ends.
2. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/NpzReader.kt`:
   - Update `readAllBytes(inputStream: InputStream, expectedSize: Long = -1L): ByteArray`:
     ```kotlin
     private fun readAllBytes(inputStream: InputStream, expectedSize: Long = -1L): ByteArray {
         if (expectedSize > 0 && expectedSize <= Int.MAX_VALUE) {
             val size = expectedSize.toInt()
             val bytes = ByteArray(size)
             var totalRead = 0
             while (totalRead < size) {
                 val read = inputStream.read(bytes, totalRead, size - totalRead)
                 if (read == -1) break
                 totalRead += read
             }
             return if (totalRead == size) bytes else bytes.copyOf(totalRead)
         }
         val buffer = ByteArray(8192)
         val baos = ByteArrayOutputStream()
         var len: Int
         while (inputStream.read(buffer).also { len = it } != -1) {
             baos.write(buffer, 0, len)
         }
         return baos.toByteArray()
     }
     ```
   - In `read(inputStream: InputStream)`, pass `entry.size` to `readAllBytes(zis, entry.size)`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` — Add dummy tensor caching and lifecycle cleanup.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/NpzReader.kt` — Update `readAllBytes()` with size-hinted buffer allocation.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AcousticEngineAndNpzOptimizationTest.kt` — [NEW] Single comprehensive test file.

## Test Criteria (Single Test File)
**File:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AcousticEngineAndNpzOptimizationTest.kt`
- [x] **Test 1**: Verify `NpzReader.readAllBytes()` correctly reads full stream contents when `expectedSize` matches, is larger/smaller, or is unknown (`-1`).
- [x] **Test 2**: Verify `NpzReader.read()` successfully parses a synthetic ZIP/NPZ archive containing 2D/3D float arrays and scalars.
- [x] **Test 3**: Verify dummy past tensor shape matches `[1, nHLoc, 0, hdLoc]` and can be safely accessed across multiple iterations.

## Verification Command
```bash
./gradlew test --tests "com.skul9x.doctruyen.tts.vieneu.AcousticEngineAndNpzOptimizationTest"
```

---
Next Phase: [phase-03-optimize-ui-and-debug-logging.md](./phase-03-optimize-ui-and-debug-logging.md)
