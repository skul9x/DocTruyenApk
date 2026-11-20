# Phase 02: Storage & Memory Footprint Optimization

Status: ✅ Completed  
Dependencies: Phase 01  
Target Issues: `PERF-003` (50MB Unused Asset Extraction), `PERF-004` (150MB Transient Heap Spike)

---

## 1. Objective
Eliminate redundant persistent storage waste on user devices by preventing the extraction of asset files that are read directly from the APK, and eliminate transient 150MB Java heap allocation spikes during model initialization by streaming binary weight archives directly into final destination arrays.

---

## 2. Scope
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt` (lines ~351-366)
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/NpzReader.kt` (lines ~57-71, 105, 154-160)
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/DualAssetExtractionAndDetectionTest.kt` (test assertion alignment)
- Test Suite: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/StorageAndNpzStreamingTest.kt`

---

## 3. Requirements

### 3.1. Functional Requirements
1. **Selective Asset Extraction Whitelist:**
   - Refactor `ensureAssetsExtracted` in `OnnxSessionManager` so that it extracts only native binary files that strictly require POSIX filesystem paths for C++ runtimes (specifically `.onnx`, `.data`, and `.bin` files).
   - Exclude `.npz` weight archives and `.json` configuration files from extraction, as these are opened directly from APK assets via `context.assets.open()`.
   - Synchronize unit test assertions in `DualAssetExtractionAndDetectionTest.kt` to reflect that `.npz` files are intentionally omitted from internal disk storage.
2. **Direct-Stream NPZ Parsing:**
   - Refactor `NpzReader` to stream float data directly from `ZipInputStream` into the target multidimensional channel arrays (`Array(16) { FloatArray(1024 * 768) }`) without buffering the entire uncompressed `.npy` file into an intermediate 49MB `ByteArray` and an intermediate 49MB flat `FloatArray`.
   - Parse the small `.npy` ASCII header (magic string + shape + dtype) on the fly, then read bytes through a compact, reusable buffer (e.g. 16KB–64KB) directly into destination float channels.

### 3.2. Non-Functional Requirements
- **Flash Storage Savings:** Save 50.5MB of internal flash storage per install on the user's device.
- **Cold-Start Acceleration:** Reduce initial asset extraction time by at least 25% to 35% on first application launch.
- **Heap Peak Reduction:** Decrease the transient Java heap peak during `VieNeuOnnxEngine.create()` from ~198MB down to < 70MB, eliminating allocation-driven GC pauses and preventing `OutOfMemoryError` on 2GB–3GB RAM devices.
- **Numeric Equivalence:** Guarantee that parsed float values match existing `NpzReader` outputs bit-for-bit (exact IEEE 754 precision).

---

## 4. Constraints
- The engine must continue to support loading from filesystem directories in desktop JVM test environments where `Context.assets` is unavailable.
- Do not modify the binary format or structure of `vieneu_v3_heads.npz` in APK assets.
- Must preserve compatibility with all existing `NpzReader` query methods (`get2D`, `getChannelArrays`, `getFloatArray`, `getScalarFloat`).

---

## 5. Dependencies
- Phase 01 must be completed to ensure stable baseline pipeline execution.

---

## 6. Acceptance Criteria
1. After running `ensureAssetsExtracted`, the extracted backbone directory in `context.filesDir` does not contain `vieneu_v3_heads.npz`, `config.json`, or `tokenizer.json`, while engine initialization and playback succeed normally.
2. `NpzReader` parses `audio_emb` ($[16, 1024, 768]$) directly without allocating a 49MB `ByteArray`.
3. Memory profiling confirms that peak heap allocation during engine creation drops by > 100MB compared to the v1.17 baseline.
4. All existing asset extraction and NPZ tests pass.

---

## 7. Comprehensive File-Based Test

**Test File:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/StorageAndNpzStreamingTest.kt`  
**Required Verifications:**
1. `testEnsureAssetsExtractedExcludesNpzAndJson()`: Execute `ensureAssetsExtracted` with a mock or virtual asset directory and assert that `.onnx` and `.data` files exist on disk, while `.npz` and `.json` are strictly excluded.
2. `testStreamingNpzReaderDirectChannels()`: Parse `vieneu_v3_heads.npz` using the streaming reader and compare all 12,288,000 float values of `audio_emb` against the baseline array, verifying exact IEEE 754 equality ($0.0$ error).
3. `testDirectStreamingHeapEfficiency()`: Verify that reading a mock 10MB `.npy` stream does not allocate a monolithic intermediate byte array matching the entry size.

