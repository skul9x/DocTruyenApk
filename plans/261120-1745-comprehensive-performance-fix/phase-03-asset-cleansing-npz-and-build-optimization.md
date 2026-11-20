# Phase 03: Asset Cleansing, Memory-Efficient NPZ & Build Optimization

**Status:** ✅ Completed  
**Dependencies:** Phase 01  

## Objective
Reduce APK size and internal storage consumption by eliminating 84.8MB of unused AI model assets, eliminate the transient 250MB heap spike during model initialization in `NpzReader`, activate release-mode R8/ProGuard shrinking, and configure ABI packaging splits for modern 64-bit devices.

## Scope
- Remove dead assets: `denoiser.onnx` (41MB), `moss_audio_tokenizer_encode.onnx` (797KB), and `moss_audio_tokenizer_encode.data` (43MB).
- Refactor `NpzReader.kt` to stream and parse NPY data directly into contiguous flat buffers without unbounded `ByteArrayOutputStream` reallocations or multi-dimensional array object overhead.
- Update `app/build.gradle.kts` to enable R8 minification (`isMinifyEnabled = true`) with complete ProGuard keep rules for ONNX Runtime, JNI bindings, and GSON models.
- Configure ABI packaging options in Gradle to avoid bundling unused 32-bit and x86 native libraries into 64-bit production distributions.

## Requirements
### Functional
1. **Asset Pruning**: Completely remove `app/src/main/assets/vieneu/denoiser` and `app/src/main/assets/vieneu/codec/moss_audio_tokenizer_encode.*` without affecting any TTS synthesis functionality.
2. **Flat Npz Weight Representation**: Read embedding weights directly into contiguous single-dimensional arrays, eliminating 16,384 distinct object allocations for 3D tensor lookups.
3. **R8/ProGuard Integrity**: Code minification and resource shrinking must not strip JNI entry points (`SeaG2P`), ONNX Runtime native wrappers, or Retrofit/GSON serializable classes.
4. **Build Packaging**: Produce optimized APK/AAB outputs with separate ABI outputs or targeted 64-bit packages.

### Non-Functional
- APK download size reduced by >95MB.
- Initial model loading heap memory spike reduced from >250MB to <55MB.
- Code DEX size reduced by >25% via R8 optimization.

## Constraints
- Must not alter the mathematical values or dimensional indexing of `text_emb`, `audio_emb`, `xvec_w`, `xvec_b`, `xvec_ln_w`, or `xvec_ln_b`.
- Must pass all existing network parsing and TTS unit tests under minified release configurations.

## Acceptance Criteria
- APK size is verified under 270MB (down from ~370MB).
- `NpzReader` loads `vieneu_v3_heads.npz` with zero `ByteArrayOutputStream` buffering.
- Embedding lookup functions in `VieNeuOnnxEngine` return identical numerical vectors before and after flat buffer adoption.
- Release APK builds successfully with minification enabled and all unit tests passing.

## Comprehensive File-Based Test
- **File:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AssetCleansingAndBuildOptimizationTest.kt`
- **Verification Strategy:**
  1. `testDeadAssetAbsenceAndIntegrity`: Verifies that all required TTS assets remain present while verifying that deleted encoder/denoiser assets are no longer referenced or loaded.
  2. `testNpzReaderZeroBaosAllocation`: Asserts that `NpzReader` parses valid NumPy tensors directly without allocating intermediary growing byte buffers.
  3. `testFlatEmbeddingNumericalEquivalence`: Verifies that flat 1D embedding lookups match exact numerical values previously obtained via nested 3D array access.
  4. `testProGuardRulesCoverage`: Validates that reflection targets, JNI signatures, and GSON serialization models are correctly preserved in ProGuard configurations.

