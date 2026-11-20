# Phase 04: Runtime Threading, Execution Providers & Engine Tuning

**Status:** ✅ Completed  
**Dependencies:** Phase 01, Phase 02, Phase 03  

## Objective
Optimize runtime thread pool configurations for ONNX Execution Providers, rectify fallback thread starvation in XNNPACK, accelerate hot matrix-vector computations in acoustic sampling, streamline G2P phoneme caching, and disable debug HTTP header logging in production builds.

## Scope
- Modify `OnnxSessionManager.kt`: Configure `intraOpNumThreads` before registering XNNPACK to prevent single-threaded fallback for unsupported operators.
- Align precision configuration and asset detection between UI, `UserConfig`, and `OnnxSessionManager` to eliminate deceptive INT8/FP32 fallback warnings.
- Optimize `sampleCode` in `VieNeuOnnxEngine.kt` to streamline the $1024 \times 768$ matrix-vector multiply loop.
- Streamline `SeaG2P.kt` caching to avoid wasteful synchronized hashing of unique multi-sentence paragraphs.
- Update `ApiService.kt` to disable `HttpLoggingInterceptor` header logging in non-debug releases.

## Requirements
### Functional
1. **Thread Pool Optimization**: Ensure both XNNPACK and standard CPU Execution Providers allocate the optimal number of big-core threads across all graph operations.
2. **Quantization Integrity**: Ensure precision configuration accurately reflects available model assets and provides truthful diagnostics in `TtsMetricsLogger`.
3. **Secure Network Interception**: Enforce `HttpLoggingInterceptor.Level.NONE` for non-debug builds to eliminate sensitive header leaks and string allocations.
4. **Phoneme Cache Utility**: Refactor caching to avoid useless whole-sentence hashing while accelerating frequently repeated lexical items.
5. **Sample Code Efficiency**: Accelerate acoustic frame projection without compromising token sampling probability distributions.

### Non-Functional
- Reduce CPU time spent in `sampleCode` by at least 25%.
- Eliminate single-threaded CPU fallback bottlenecks for non-XNNPACK operations.
- Zero network header logging in production logcat.

## Constraints
- Maintain identical audio pronunciation, tone accents, and prosody across all preset voices.
- Preserve backward compatibility with existing `UserConfig` settings.

## Acceptance Criteria
- When XNNPACK is active, operators falling back to CPU execute across the configured big-core thread count rather than a single thread.
- Release builds produce zero HTTP header logs in logcat.
- Sampling probability distributions and selected acoustic codes match reference outputs within standard stochastic tolerances.
- End-to-end inference RTF improves measurably across short and long utterance benchmarks.

## Comprehensive File-Based Test
- **File:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/RuntimeOptimizationAndPrecisionVerificationTest.kt`
- **Verification Strategy:**
  1. `testSessionOptionsThreadConfiguration`: Validates that intra-op thread counts are properly set on fallback CPU pools when XNNPACK is enabled.
  2. `testPrecisionConfigurationTruthfulness`: Verifies that precision settings and telemetry reporting accurately match actual on-disk asset availability.
  3. `testReleaseLoggingInterceptorDisabled`: Asserts that `HttpLoggingInterceptor` level resolves to `NONE` when `BuildConfig.DEBUG` is false.
  4. `testSampleCodeAcousticProjectionEquivalence`: Verifies that optimized matrix-vector projection produces identical numerical logits and valid sampled codebook tokens.

