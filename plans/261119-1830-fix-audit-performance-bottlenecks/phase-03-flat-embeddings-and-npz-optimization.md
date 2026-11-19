# Phase 03: Flat Embeddings & NpzReader Memory Optimization

Status: ✅ Completed  
Dependencies: Phase 02  

## Objective
Eliminate 16,384 distinct `FloatArray` object allocations and triple duplicate copies of acoustic embedding weights on the Java Heap, saving over 100MB of resident RAM and ensuring safety on low-tier devices ($\le 3\text{GB}$ RAM).

## Scope
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/NpzReader.kt`: Refactor `get3D` and introduce direct contiguous flat / channel-wise readers (`getChannelArrays`, `getFlat`).
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`: Change `audioEmbeddings` from `Array<Array<FloatArray>>` to flat channel-based `Array<FloatArray>`, eliminating the secondary duplication into `flatAudioEmbeddings`.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`: Update engine initialization call site.

## Requirements

### Functional Requirements
- [x] In `NpzReader.kt`:
  - Provide `getChannelArrays(key: String): Array<FloatArray>?` for 3D tensors of shape `[nChannels, vocabSize, hiddenSize]`:
    - Instead of creating `Array(d0) { Array(d1) { FloatArray(d2) } }` (16,384 objects), create exactly `d0` (16) contiguous `FloatArray(d1 * d2)` buffers.
    - Copy data in bulk via `System.arraycopy(arr.data, ch * stride, channelArray[ch], 0, stride)`.
  - Provide `getFlat(key: String): FloatArray?` returning `arr.data` directly (zero-copy from `NpyArray`).
  - Deprecate or remove `get3D(key: String)`.
- [x] In `VieNeuOnnxEngine.kt`:
  - Change property `audioEmbeddings` from `Array<Array<FloatArray>>` to `val audioEmbeddings: Array<FloatArray>`.
    - Shape: 16 channels, each of length `audioVocabSize * hiddenSize` ($1024 \times 768 = 786,432$ floats).
  - Remove redundant property `flatAudioEmbeddings`: `audioEmbeddings` is ALREADY the contiguous flattened array!
  - Replace index lookups:
    - Previous: `val emb = audioEmbeddings[ch - 1][prevCode]` (size $H$)
    - New: Retrieve sub-slice with offset `prevCode * H`, or create a helper `getAudioEmbedding(ch: Int, code: Int, out: FloatArray)` that copies $H$ floats into a pre-allocated buffer without allocating a new array.
    - In `embedRows`: calculate vector offset using `(ch * vocabSize + audioId) * hiddenSize` or `audioEmbeddings[ch]` offset `audioId * H`.
- [x] In `VieNeuTTSManager.kt`:
  - Update `VieNeuOnnxEngine.create(...)` call to pass `headsReader.getChannelArrays("audio_emb")!!`.

### Non-Functional Requirements
- [x] Heap Footprint: Immediate reduction of **> 100MB** resident heap during TTS initialization.
- [x] Object Churn: Eliminate 16,384 JVM object instances from the heap.
- [x] Memory Locality: Contiguous 1D buffers improve CPU L1/L2 cache locality during matrix-vector dot product sampling.
- [x] Zero Regression: Numerical values extracted for embeddings match original 3D indices exactly ($\Delta = 0.0$).

## How to Implement

1. **Implement `getChannelArrays` in `NpzReader.kt`:**
   ```kotlin
   fun getChannelArrays(key: String): Array<FloatArray>? {
       val arr = arrays[key] ?: return null
       val shape = arr.shape
       if (shape.size != 3) return null
       val d0 = shape[0] // 16
       val d1 = shape[1] // 1024
       val d2 = shape[2] // 768
       val stride = d1 * d2
       val result = Array(d0) { FloatArray(stride) }
       for (ch in 0 until d0) {
           System.arraycopy(arr.data, ch * stride, result[ch], 0, stride)
       }
       return result
   }
   ```
2. **Update `VieNeuOnnxEngine` Constructor & Properties:**
   ```kotlin
   class VieNeuOnnxEngine private constructor(
       val sessionManager: OnnxSessionManager,
       val config: VieNeuConfig,
       val textEmbeddings: Array<FloatArray>,
       val audioEmbeddings: Array<FloatArray>, // Shape: Array(16) { FloatArray(1024 * 768) }
       ...
   ) {
       // flatAudioEmbeddings is no longer needed because audioEmbeddings is ALREADY flattened!
       // Direct reference alias for backwards compatibility:
       val flatAudioEmbeddings: Array<FloatArray> get() = audioEmbeddings

       // Helper to get slice without allocating:
       fun copyAudioEmbedding(ch: Int, code: Int, dest: FloatArray, destOffset: Int = 0) {
           val offset = code * config.hiddenSize
           System.arraycopy(audioEmbeddings[ch], offset, dest, destOffset, config.hiddenSize)
       }
   ```
3. **Update Slice Access in `acousticFrame()` & `embedRows()`:**
   - In `acousticFrame()`, instead of `audioEmbeddings[ch - 1][prevCode]`, write into a reusable `acousticEmbBuffer = FloatArray(H)` via `copyAudioEmbedding(ch - 1, prevCode, acousticEmbBuffer)`.
   - In `embedRows()`, copy directly from `audioEmbeddings[ch]` using offset `audioId * H`.
4. **Update `VieNeuTTSManager.kt` Engine Creation:**
   - Change `audioEmbeddings = headsReader.get3D("audio_emb")!!` to `audioEmbeddings = headsReader.getChannelArrays("audio_emb")!!`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/NpzReader.kt` - Add `getChannelArrays()` and `getFlat()`.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` - Refactor `audioEmbeddings` to 1D channel buffers, eliminate `flatAudioEmbeddings` duplication.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Update call site to pass channel arrays.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/NpzReaderFlatEmbeddingsTest.kt` - Single comprehensive verification test.

## Dependencies
- Phase 02 completed.

## Constraints
- Numerical equivalence: Every element accessed at `[ch][vocab][h]` MUST be identical to the original 3D layout.
- Thread-safety: `audioEmbeddings` buffers are read-only during inference and can be safely shared across coroutines.

## Acceptance Criteria
- [x] `NpzReader.getChannelArrays()` allocates only 16 arrays instead of 16,384 arrays.
- [x] `VieNeuOnnxEngine` does not allocate a secondary duplicate `flatAudioEmbeddings` array.
- [x] Embedding vector extraction in `acousticFrame()` uses reusable buffer copying without array allocation.
- [x] All scenarios in `NpzReaderFlatEmbeddingsTest.kt` pass with 100% success rate.

## Single File-Based Test Criteria
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/NpzReaderFlatEmbeddingsTest.kt`
- Test Scenarios:
  1. `testChannelArraysExtractionShapeAndValues`: Read mock 3D NPZ array `[16, 1024, 768]` and verify `getChannelArrays()` returns exactly 16 `FloatArray`s of size 786,432.
  2. `testIndexingEquivalence`: Assert that `audioEmbeddings[ch][v * H + h] == original3D[ch][v][h]` for all corner and random indices.
  3. `testCopyAudioEmbeddingIntoBuffer`: Verify `copyAudioEmbedding` copies the correct $H$-dimensional slice into the destination buffer without allocating memory.
  4. `testEngineInitializationWithFlatArrays`: Initialize `VieNeuOnnxEngine` with channel-flattened embeddings and verify inference passes produce identical output.

## Review Checkpoint
Stop after running `NpzReaderFlatEmbeddingsTest.kt` and await user review before proceeding to Phase 04.

