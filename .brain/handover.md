# Handover Document: Audit Performance Bottlenecks Remediation

**Date:** 2026-11-19 19:43  
**Project:** DocTruyen (VieNeu-TTS v3 Turbo On-Device Neural Reader)  
**Status:** All 6 Phases Completed & 100% Verified

---

## 📍 Final Completion Summary
All 13 performance bottlenecks identified in `audit_per.md` (PERF-001 through PERF-013) have been systematically eliminated and verified across 6 dedicated phases:

1. **Phase 01 (Hygiene, Dead Assets & UI/Network Cleanup)**:
   - `AndroidManifest.xml`: Declared `android:largeHeap="true"`.
   - Purged ~85MB of dead neural models (`denoiser.onnx` [41MB], `moss_audio_tokenizer_encode.onnx` [797KB], `moss_audio_tokenizer_encode.data` [43MB]).
   - Cleaned `VieNeuConfig.kt` (removed `DENOISER_DIR`).
   - `StoryDetailActivity.kt`: Precompiled static Regexes and offloaded chapter HTML sanitization & parsing to `Dispatchers.Default`.
   - `StoryAdapter.kt`: Migrated from XML `AnimationUtils.loadAnimation` to hardware-accelerated `ViewPropertyAnimator` with clean cancellation in `onViewDetachedFromWindow`.
   - `ApiService.kt`: Added caching for `ApiService` instances in `RetrofitClient` and configured release `HttpLoggingInterceptor` to `Level.NONE`.
   - Fully verified with `HygieneAndAssetCleanupTest.kt` (5/5 tests passing 100%).

2. **Phase 02 (Zero-Copy KV-Cache Ping-Pong & Forced GC Elimination)**:
   - `VieNeuOnnxEngine.kt`:
     - Refactored `synthesizePass()` to maintain native `currentDecodeResult: OrtSession.Result?`, passing output native `OnnxTensor` instances directly into `decodeInputs` without `FloatArray` extraction or conversion.
     - Wrapped decode loop and prefill in strict `try ... finally` guaranteeing 0 native memory leaks during normal exit, early EOS break, or exceptions/cancellation.
     - Completely eliminated forced `System.gc()` call inside the autoregressive hot loop.
     - Refactored `acousticFrame()` to ping-pong native tensors across channels 1..15, eliminating intermediate `CacheTensor` allocations and bulk float copying.
     - Deprecated `CacheTensor` and `CacheTensor.Pool`, eliminating all usages in engine hot paths.
   - Fully verified with `ZeroCopyKVCacheAndEngineTest.kt` (5/5 tests passing 100%).

3. **Phase 03 (Flat Embeddings & NpzReader Memory Optimization)**:
   - `NpzReader.kt`:
     - Added `getChannelArrays(key: String): Array<FloatArray>?` returning contiguous `Array(16) { FloatArray(1024 * 768) }` channels, eliminating 16,384 distinct inner `FloatArray` allocations.
     - Added `getFlat(key: String): FloatArray?` returning zero-copy backing `NpyArray.data`.
     - Deprecated `get3D(key: String)`.
   - `VieNeuOnnxEngine.kt`:
     - Changed `audioEmbeddings` property to `Array<FloatArray>`.
     - Removed redundant secondary `flatAudioEmbeddings` memory copy; alias returns `audioEmbeddings` directly.
     - Added `copyAudioEmbedding(ch, code, dest, destOffset)` helper and reusable `acousticEmbBuffer = FloatArray(config.hiddenSize)` to avoid per-channel allocations in `acousticFrame()`.
     - Refactored `embedRows()` to use contiguous 1D array indexing `chEmb[audioId * H + h]`.
     - Added overloaded secondary constructor for backwards compatibility with `Array<Array<FloatArray>>`.
   - `VieNeuTTSManager.kt`:
     - Updated engine initialization to call `headsReader.getChannelArrays("audio_emb")!!`.
   - Fully verified with `NpzReaderFlatEmbeddingsTest.kt` (4/4 tests passing 100%).

4. **Phase 04 (Dedicated Audio Playback Thread & Channel Backpressure)**:
   - `TTSAudioPipeline.kt`:
     - Introduced dedicated single-threaded `audioExecutor` and `audioDispatcher` running on `AudioTrackWorkerThread` with elevated Android Linux priority `THREAD_PRIORITY_URGENT_AUDIO` (-19).
     - Replaced `ConcurrentLinkedQueue` with `Channel<Pair<Int, ShortArray>>(capacity = 4)`.
     - Refactored `playbackJob` to run on `audioDispatcher` and suspend passively via `receive()` and `tryReceive()` without 50Hz `delay(20)` polling loops.
     - Refactored `synthesisJob` to push chunks via `chunkChannel.send()`, automatically applying natural backpressure when consumer buffer is saturated.
     - Structured lifecycle cleanup to cancel `chunkChannel` on pause/stop for sub-50ms unblocking, and shut down `audioExecutor` on `release()`.
   - `AudioPlayer.kt`:
     - Made `AudioPlayer` open for testing extensibility.
     - Added immediate exit checks for `needsReinit || audioTrack?.playState == AudioTrack.PLAYSTATE_STOPPED` inside `write()` loop.
   - Fully verified with `AudioPipelineChannelConcurrencyTest.kt` (6/6 tests passing 100%).

5. **Phase 05 (Matrix Unrolling [ILP] & Acoustic Tail Trimming)**:
   - `VieNeuOnnxEngine.kt`:
     - Unrolled inner dot product loop over $H = 768$ in both `sampleCode` overloads using 4 independent float accumulators (`d0, d1, d2, d3`) to break CPU pipeline dependency stalls.
     - Added clean tail handling for any dimension where $H \% 4 \ne 0$.
     - Added `computeUnrolledDot` and `computeScalarDot` companion functions for verification.
     - In `inferWithTiming()`, integrated direct acoustic tail trimming bypass: when short dialogue utterances ($\le 2$ syllables) exhibit excess burst hallucination, `BabbleGuard.trimBabbleTail` cuts trailing babble in the PCM domain; if clean, it returns immediately with 0 neural resynthesis passes.
   - `BabbleGuard.kt`:
     - Exposed `findSpeechBursts(...)` and `findActiveBursts(...)` returning precise hop frame boundary intervals.
     - Implemented `trimBabbleTail(pcm, targetSyllables)` applying a natural decay tail (~80ms) and a smooth 5ms micro-fade down to 0 at the cut point.
   - Fully verified with `MatrixOptimizationAndAcousticTrimmingTest.kt` (4/4 tests passing 100%).

6. **Phase 06 (Asset Streaming Buffer & Release R8 Minification)**:
   - `OnnxSessionManager.kt`:
     - Upgraded `copyAssetFile` streaming buffer from 8KB to 64KB (`ASSET_STREAM_BUFFER_SIZE = 64 * 1024`), reducing I/O syscall context switching by 8x.
     - Integrated version-code-driven asset invalidation in `ensureAssetsExtracted` checking `KEY_LAST_EXTRACTED_VERSION` in `SharedPreferences`, ensuring APK updates reliably overwrite on-disk neural models while skipping redundant copy on matching versions.
   - `SeaG2P.kt`:
     - Upgraded `sea_g2p.bin` extraction to use 64KB chunk buffer.
   - `app/build.gradle.kts`:
     - Configured `isMinifyEnabled = true` and `isShrinkResources = true` in `buildTypes.release`.
   - `app/proguard-rules.pro`:
     - Configured comprehensive keep-rules protecting ONNX Runtime (`ai.onnxruntime.**`), SeaG2P Rust JNI (`com.skul9x.doctruyen.tts.vieneu.SeaG2P`, `native <methods>;`), Retrofit & OkHttp (`retrofit2.**`, `com.skul9x.doctruyen.network.**`), Gson `@SerializedName`, Glide (`com.bumptech.glide.**`), and AndroidX Media (`androidx.media.**`).
   - Fully verified with `AssetExtractionAndProguardRulesTest.kt` (5/5 tests passing 100%).

---

## 📋 Plan Structure & Status (All Complete)
1. **Phase 01:** Hygiene, Dead Assets & UI/Network Cleanup  
   *Status:* ✅ Completed (Verified: `HygieneAndAssetCleanupTest.kt`)
2. **Phase 02:** Zero-Copy KV-Cache Ping-Pong & Forced GC Elimination  
   *Status:* ✅ Completed (Verified: `ZeroCopyKVCacheAndEngineTest.kt`)
3. **Phase 03:** Flat Embeddings & NpzReader Memory Optimization  
   *Status:* ✅ Completed (Verified: `NpzReaderFlatEmbeddingsTest.kt`)
4. **Phase 04:** Dedicated Audio Playback Thread & Channel Backpressure  
   *Status:* ✅ Completed (Verified: `AudioPipelineChannelConcurrencyTest.kt`)
5. **Phase 05:** Matrix Unrolling (ILP) & Acoustic Tail Trimming  
   *Status:* ✅ Completed (Verified: `MatrixOptimizationAndAcousticTrimmingTest.kt`)
6. **Phase 06:** Asset Streaming Buffer & Release R8 Minification  
   *Status:* ✅ Completed (Verified: `AssetExtractionAndProguardRulesTest.kt`)

---

## 📁 Key Files
- [phase-06-asset-streaming-buffer-and-release-r8-minify.md](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/plans/261119-1830-fix-audit-performance-bottlenecks/phase-06-asset-streaming-buffer-and-release-r8-minify.md)
- [plan.md](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/plans/261119-1830-fix-audit-performance-bottlenecks/plan.md)
- [OnnxSessionManager.kt](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt)
- [build.gradle.kts](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/app/build.gradle.kts)
- [proguard-rules.pro](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/app/proguard-rules.pro)
- [AssetExtractionAndProguardRulesTest.kt](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/app/src/test/java/com/skul9x/doctruyen/performance/AssetExtractionAndProguardRulesTest.kt)
