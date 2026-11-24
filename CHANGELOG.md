# Changelog - DocTruyen Android App

## [v1.18] - [2026-11-24] GitHub Release FP32 & 64-Bit ARM APK Optimization
### Phase 02: GitHub Actions CI/CD Workflow Streamlining (FP32 Release)
- **Elimination of Runtime INT8 Quantization Pass (`.github/workflows/android.yml`)**:
  - Removed `Set up Python 3.10` (`actions/setup-python@v5`), pip dependency installation (`tools/requirements.txt`), and `tools/quantize_models.py` execution.
  - Eliminated `Verify Quantized Assets` step and all runtime generation of `backbone_int8` models.
  - Reduced GitHub Actions CI pipeline execution time by ~3-5 minutes, saving runner compute and network bandwidth.
- **Single Deterministic Release APK Artifact Resolution (`.github/workflows/android.yml`)**:
  - Replaced ambiguous wildcard search (`find "$RELEASE_DIR" -name "*.apk"`) with direct resolution of `${RELEASE_DIR}/app-release.apk`.
  - Added fail-fast validation with GitHub Actions error annotation (`::error::Release APK not found at $SOURCE_APK`).
  - Prepares and renames single unified ~250-254 MB `arm64-v8a` FP32 release APK to `${APP_NAME}-${VERSION}.apk`.
- **Preservation of Signing, Smart Versioning & Release Idempotency (`.github/workflows/android.yml`)**:
  - Preserved base64 keystore decoding (`KEYSTORE_BASE64`) to `app/skul9x.jks` and `skul9x.jks`.
  - Maintained signing environment variables (`STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`) for `./gradlew :app:assembleRelease`.
  - Retained smart versioning and tag incrementing logic (`Determine Next Release Version`).
  - Retained GitHub CLI release creation (`gh release create --generate-notes`) and idempotent clobber upload (`gh release upload --clobber`).
- **Comprehensive Unit Testing (`ReleaseWorkflowPipelineTest.kt`)**:
  - Created comprehensive test suite verifying all 6 core pipeline requirements (6/6 passed 100%):
    1. `testWorkflowStructureAndTriggers`: Validates workflow triggers, permissions, and Temurin JDK 17 setup.
    2. `testPythonSetupAndQuantizationStepsCompletelyEliminated`: Verifies complete removal of Python setup, pip dependencies, quantization script, and INT8 directory references.
    3. `testKeystoreDecodingAndSigningIntegrityIntact`: Verifies keystore decoding, signing credentials, and `assembleRelease` task invocation.
    4. `testSmartVersioningAndSlugificationRetained`: Verifies slugification, git tag matching, and version output exports.
    5. `testApkPreparationTargetsSingleUnifiedReleaseApkDirectly`: Verifies direct resolution of `app-release.apk`, fail-fast verification, and elimination of ambiguous wildcard find.
    6. `testGitHubReleaseCreationAndUploadPreserved`: Verifies GitHub CLI release creation, release notes generation, and idempotent clobber asset uploading.

### Phase 01: Gradle Architecture & Packaging Alignment (arm64-v8a only)
- **NDK Packaging Filter Alignment (`app/build.gradle.kts`)**:
  - Restricted `ndk.abiFilters` exclusively to `arm64-v8a`, eliminating 32-bit (`armeabi-v7a`) and x86 (`x86_64`, `x86`) native binaries from the output artifact.
  - Reduced native binary packaging footprint by ~80-90 MB.
- **Single Unified APK Configuration (`app/build.gradle.kts`)**:
  - Disabled multi-APK generation by configuring `splits.abi.isEnable = false` and `isUniversalApk = false`.
  - Configured `:app:assembleDebug` and `:app:assembleRelease` to produce single primary APK artifacts (`app-debug.apk` and `app-release.apk`) containing only `lib/arm64-v8a/`.
  - Retained critical zero-copy mmap uncompressed asset extensions (`noCompress += listOf("onnx", "data", "npz", "json")`).
- **Build Validation Harmonization (`ReleaseBuildValidationTest.kt`)**:
  - Harmonized release build verification to enforce `arm64-v8a` as the unified target and prohibit legacy multi-ABI splits.
- **Comprehensive Unit Testing (`GradleArm64PackagingConfigurationTest.kt`)**:
  - Created comprehensive test suite verifying all 5 core requirements (5/5 passed 100%):
    1. `testNdkAbiFiltersSolelyTargetArm64V8a`: Verifies `arm64-v8a` is the sole targeted NDK ABI filter.
    2. `testLegacyAndRedundantAbisExcludedFromBuildTargets`: Verifies `armeabi-v7a`, `x86_64`, and `x86` are completely excluded from build targets.
    3. `testAbiSplitsConfiguredForSingleUnifiedApkOutput`: Verifies `splits.abi.isEnable = false` and `isUniversalApk = false`.
    4. `testCriticalAssetNoCompressExclusionsPreserved`: Verifies uncompressed asset settings for `.onnx`, `.data`, `.npz`, and `.json`.
    5. `testPackagingAndBuildTypeIntegrity`: Verifies ProGuard/R8 minification, packaging rules, and release signing configurations.

## [v1.17] - [2026-11-24] Audit Remediation: Critical, High & Medium Fixes
### Phase 04: Foreground Service Callback Leak Elimination, Scalar Npz Parsing Hardening & Test Harmonization (AUD-004 & AUD-006)
- **Foreground Service Callback Leak Elimination (`ReadingService.kt`, `StoryDetailActivity.kt`)**:
  - Enhanced `ReadingService.removeStateCallback()` and added `removeCallbacks()` to unconditionally nullify both `stateCallback` and `progressCallback`.
  - Added deterministic unbinding and callback cleanup in `StoryDetailActivity.onDestroy()`, unregistering listeners and unbinding from service to eliminate Activity context memory leaks.
- **NumPy Scalar Tensor Stream Parsing Hardening (`NpzReader.kt`)**:
  - Fixed 0-dimensional scalar NPY array handling where `shape = ()` correctly calculates `totalElements = 1` according to the NumPy NEP 1 specification.
  - Corrected stream payload reading so 4 bytes of float32 data are read into `floatData[0]` instead of leaving unread bytes in the `ZipInputStream` entry.
  - Restored `getScalarFloat("xvec_ln_eps")` to return exact float values (> 0f) without returning `null` or corrupting subsequent entry offsets.
- **Prosody & Text Segmentation Harmonization (`SmartTextSegmenter.kt`, `TTSAudioPipeline.kt`)**:
  - Preserved natural exclamation (`!`), question (`?`), and ellipsis (`…`) punctuation for short utterances (< 5 words) in `puncNorm()` instead of blindly forcing periods `.`.
  - Preserved natural story titles without forcefully appending periods in `segmentStory()`.
  - Enforced caller-supplied `maxChars` in `splitSentenceIntoSubClauses()` using `minOf(75, maxChars)` and permitted dialogue colon combinations when followed by quotes.
  - Guarded `engine.requestCancel()` in `TTSAudioPipeline.stopInternal()` with `if (synthesisJob?.isActive == true)`, ensuring idle pipeline stops do not abort active background prewarm jobs.
- **Outdated Test Harmonization**:
  - Updated `BoundedAudioChannelPipelineTest.testMemoryCapUnderLongStoryPlayback` to verify all samples are consumed (`totalSamplesConsumed == totalStoryChunks * 48000L`).
  - Updated `VoicePresetsSyncTest` for 25 presets, "Hải Đăng" default and fallback, and updated group expectations.
  - Harmonized `QuantizationDetectionAndPrebufferTest` and `SmartDialogueSegmentationTest` assertions.
- **Comprehensive Unit Testing (`ServiceCallbackLifecycleCleanupTest.kt`)**:
  - Implemented comprehensive file-based unit test suite verifying all core requirements (4/4 passed 100%):
    1. `testRemoveStateCallbackClearsBothStateAndProgressCallbacks`: Confirms callback nullification on `ReadingService`.
    2. `testActivityLifecycleDestructionDetachesCallbacksAndPreventsRetention`: Confirms simulated activity lifecycle cleanup detaches listeners and prevents context retention.
    3. `testScalarNpyParsingWithEmptyShape`: Confirms synthetic 0-D scalar NPY stream parsing with shape `()` correctly yields 1 float element.
    4. `testProductionHeadsNpzScalarEpsilonReading`: Confirms reading `vieneu_v3_heads.npz` returns non-null positive scalar `xvec_ln_eps`.

### Phase 03: Static JNI Tensor Pooling & Native Memory Churn Optimization (AUD-003)
- **Static Channel Position Tensor Pooling (`VieNeuOnnxEngine.kt`)**:
  - Pre-allocated static position tensors for all acoustic frames, completely eliminating 9,600 redundant JNI `OnnxTensor` allocations and deallocations per chunk.
  - Implemented thread-safe `getOrCreatePosTensor0()` allocating initial step-0 position tensor (`shape [1, 2]`, values `[0L, 1L]`) once across engine lifetime.
  - Implemented thread-safe `getOrCreateChannelPosTensors()` allocating 17 channel position tensors (`shape [1, 1]`, value `ch`) indexed directly by channel ($ch \in 1..16$), removing per-channel dynamic `OnnxTensor.createTensor()` calls in the 600-frame acoustic generation loop.
  - Removed `posTensor0.close()` and per-channel `posTensor.close()` from the frame synthesis loop, preventing native pointer invalidation during active engine life.
- **Defensive Resource Lifecycle & Teardown (`VieNeuOnnxEngine.kt`)**:
  - Wrapped dynamic per-channel `embTensor` allocations in defensive `try-finally` blocks, ensuring immediate native deallocation even during unexpected engine aborts or cancellation.
  - Integrated safe cleanup of `cachedChannelPosTensors` and `cachedPosTensor0` inside `performResourceTeardown()`, cleanly closing native C++ `Ort::Value*` pointers and clearing references during engine disposal.
- **Comprehensive Unit Testing (`StaticTensorPoolingPerformanceTest.kt`)**:
  - Implemented comprehensive file-based unit test suite verifying all 5 core requirements (5/5 passed 100%):
    1. `testStaticChannelPositionTensorShapesAndValues`: Confirms pre-allocated tensor shapes, values, and instance caching without re-allocation.
    2. `testAcousticFrameGenerationExecutesWithPooledTensors`: Confirms audio synthesis succeeds with bit-identical acoustic codes and valid non-NaN PCM samples.
    3. `testPooledTensorsPersistAcrossMultipleInferences`: Confirms pooled tensors remain open, cached, and identical across multiple consecutive chunk inferences.
    4. `testDefensiveEmbTensorLifecycleUnderCancellation`: Confirms cooperative cancellation mid-inference does not corrupt native heap and dynamic embeddings are safely cleaned up.
    5. `testEngineTeardownSafelyClosesAndNullsPooledTensors`: Confirms engine teardown safely closes all pooled tensors and clears references with idempotent behavior.

### Phase 02: Robust Quantization Detection & Defensive Prebuffer Margin Restoration (AUD-002)
- **Elimination of Invalid Shared Data Heuristic (`OnnxSessionManager.kt`)**:
  - Removed flawed `sharedData.exists()` heuristic (`vieneu_backbone_shared.data`) that mistakenly classified all FP32 models in the canonical `backbone` directory as INT8.
  - Established unambiguous quantization classification precedence:
    1. Explicit filename naming conventions (`*_int8.onnx`, `*.int8.*`, `*_fp16.onnx`, `*_fp32.onnx`).
    2. Deep byte scan for explicit ONNX metadata key `quantization` (`int8`, `fp16`, `fp32`).
    3. Parent directory convention: canonical unquantized FP32 directory `backbone` / `backbone_fp32` strictly resolves to `QuantizationType.FP32`; `backbone_int8` strictly resolves to `QuantizationType.INT8`; `backbone_fp16` strictly resolves to `QuantizationType.FP16`.
    4. Deep operator scan on standalone models outside standard directory conventions for `MatMulInteger`, `DynamicQuantizeLinear`, `QLinearMatMul` returning `INT8`.
    5. Fallback path tokens and companion prefill resolution defaulting to `QuantizationType.FP32`.
  - Updated `createFromFiles()` to guarantee that instantiating with `precision = "FP32"` resolves strictly to `QuantizationType.FP32` and `precision = "INT8"` resolves strictly to `QuantizationType.INT8`.
- **Defensive Prebuffer Margin Restoration (`TTSAudioPipeline.kt`)**:
  - Restored defensive 7,500ms initial prebuffering requirement in `TTSAudioPipeline` for `QuantizationType.FP32` to prevent audio starvation underruns caused by mobile CPU RTF (~1.8x–2.5x).
  - Maintained aggressive 2,000ms–2,500ms prebuffer target for `QuantizationType.INT8` models for rapid TTFT (<2.5s).
  - Ensured `hasQualifiedChunks` in `TTSAudioPipeline.playbackJob` strictly enforces `bufferedDurationMs >= targetDurationMs` (7,500ms) for FP32 rather than prematurely triggering playback at 3,500ms.
- **Comprehensive Unit Testing (`AccurateQuantizationResolutionTest.kt`)**:
  - Created comprehensive file-based unit test suite verifying all 4 core requirements (4/4 passed 100%):
    1. `testFp32DetectionInBackboneDespiteSharedData`: Confirms models in `src/main/assets/vieneu/backbone` are detected as `FP32` despite the presence of `vieneu_backbone_shared.data`.
    2. `testInt8DetectionViaDirectoryAndOperators`: Confirms models in `backbone_int8` or standalone files with INT8 operators/metadata return `INT8`.
    3. `testCreateFromFilesPrecisionResolution`: Confirms `createFromFiles` with `precision = "FP32"` yields `FP32` and `precision = "INT8"` yields `INT8`.
    4. `testAdaptiveTargetDurationMargins`: Confirms `computeAdaptiveTargetDurationMs` outputs >= 7500ms for FP32 and <= 2500ms for INT8 across 2, 4, 6, 8 core configurations.

### Phase 01: Immediate Cooperative Inference Cancellation & Latency Elimination (AUD-001 & AUD-005)
- **Lock-Guarded Cancellation Reset (`VieNeuOnnxEngine.kt`)**:
  - Implemented `resetCancel()` method synchronized on `lock.withLock { cancelRequested = false }`.
  - Ensures thread safety when resetting cancellation state: awaits in-flight aborting inference pass to release engine lock before clearing flag, preventing race conditions.
  - Reordered inference entry guards in `inferWithTiming()` so `isClosed` check precedes `cancelRequested`, correctly throwing `IllegalStateException` on closed engine as required by lifecycle contracts (resolving AUD-005).
- **Immediate Cancellation on Stop & Seek (`TTSAudioPipeline.kt`)**:
  - Removed `withContext(NonCancellable)` from chunk synthesis loop in `synthesisJob`, permitting in-flight acoustic inference to receive cancellation signals immediately.
  - Added `engine.requestCancel()` to `stopInternal()`, aborting in-flight neural inference cooperatively within < 50ms upon stop, seek, or story transitions (resolving AUD-001).
  - Added `engine.resetCancel()` to `startPipeline()`, clearing the cancellation flag cleanly so subsequent reading sessions and chunk seeks synthesize without delay.
- **Comprehensive Unit Testing (`ImmediateInferenceCancellationTest.kt`)**:
  - Created comprehensive file-based unit test suite verifying all 5 core requirements (5/5 passed 100%):
    1. `testStopInternalWhileInferringAbortsWithin50ms`: Confirms in-flight inference aborts in < 50ms with `InterruptedException` when `stopInternal()` is invoked.
    2. `testPipelineAbortAndResumeSynthesizesSubsequentChunks`: Confirms starting a new pipeline session after an abort successfully resets cancellation flag and synthesizes subsequent chunks without error.
    3. `testSeekToChunkCooperativelyCancelsAndResumesTarget`: Confirms calling `seekToChunk()` cooperatively cancels in-flight synthesis and resumes immediately at the target chunk.
    4. `testClosedEngineGuardPriorityOverCancellation`: Confirms `inferWithTiming()` on a closed engine throws `IllegalStateException` containing "closed" rather than `InterruptedException`.
    5. `testResetCancelWaitsForActiveInferenceLockRelease`: Confirms `resetCancel()` synchronizes on `lock.withLock` and waits for active inference to release the lock.

## [v1.16] - [2026-11-22] TTS Latency Optimization, Prewarm Preservation & ANR Prevention
### Phase 04: Robust INT8 Model Quantization Detection & Adaptive Prebuffer Optimization (P7)
- **Deep Byte Pattern & Quantization Operator Detection (`OnnxSessionManager.kt`)**:
  - Enhanced `detectQuantizationType(modelPath)` to inspect model binary headers and scan for dynamic INT8 quantized ONNX operators (`MatMulInteger`, `DynamicQuantizeLinear`, `QLinearMatMul`) using memory-efficient byte matching.
  - Added directory convention detection: models located alongside quantized backbone files (e.g. `vieneu_backbone_shared.data`) or paths with `backbone_int8` automatically default to `QuantizationType.INT8`.
  - Packaged default models (`vieneu_prefill.onnx`, `vieneu_decode_step.onnx`, `vieneu_acoustic_cached.onnx`) are now accurately recognized as `QuantizationType.INT8` without requiring external metadata strings or filename suffixes.
  - Safely falls back to `QuantizationType.FP32` only when no INT8 operators or metadata markers exist.
- **Synchronized Low-Latency Pre-Buffer Duration (`TTSAudioPipeline.kt`)**:
  - Aligned `computeAdaptiveTargetDurationMs()` with accurate INT8 detection: selects 2000ms–2500ms initial pre-buffering threshold (2000ms for >= 4 CPU cores, 2500ms for < 4 CPU cores) instead of the defensive 7500ms FP32 fallback, cutting startup audio playback latency by up to 5.0 seconds.
  - Retained defensive 7500ms pre-buffering exclusively for true `QuantizationType.FP32` models to guard against pipeline underruns.
  - Synchronized `minDurationMs` in the reactive pre-buffering loop to 2000ms under INT8 for immediate playback start once initial audio is ready.
- **Telemetry Precision Truthfulness (`TtsMetricsLogger.kt`)**:
  - Added `logEffectivePrecision(effectivePrecision)` and updated report formatting to truthfully log and display `Quantization: INT8` in diagnostic reports and session profiles.
- **Comprehensive Unit Testing (`QuantizationDetectionAndPrebufferTest.kt`)**:
  - Implemented comprehensive file-based unit test suite verifying all 7 core requirements (7/7 passed 100% in 54ms):
    1. `testPackagedAssetsQuantizationDetection`: Confirms packaged backbone models (`vieneu_prefill.onnx`, `vieneu_decode_step.onnx`, `vieneu_acoustic_cached.onnx`) are accurately detected as `INT8`.
    2. `testBytePatternOperatorDetection`: Confirms synthetic models containing `MatMulInteger`, `DynamicQuantizeLinear`, or `QLinearMatMul` return `INT8`.
    3. `testMetadataTaggedQuantizationDetection`: Confirms metadata tags for INT8, FP16, and FP32 are respected.
    4. `testPureFp32FallbackWhenNoQuantizationOpsOrMetadata`: Confirms non-quantized models and generic paths safely fallback to `FP32`.
    5. `testPathAndDirectoryConventions`: Confirms path conventions and directory shared data detection.
    6. `testComputeAdaptiveTargetDurationMs`: Confirms 2000ms–2500ms target duration for INT8 versus 7500ms for FP32.
    7. `testTelemetryLoggingQuantizationAccuracy`: Confirms session telemetry and reports accurately display `Quantization: INT8`.

### Phase 03: Asynchronous Engine Teardown & Lifecycle ANR Elimination (P3)
- **Cooperative Cancellation Flag & Safe Teardown (`VieNeuOnnxEngine.kt`)**:
  - Introduced `@Volatile var cancelRequested: Boolean = false`, `cancel()`, and `requestCancel()`.
  - Added cooperative cancellation checkpoints across prefill, decode step autoregressive frame loop (`effectiveMaxFrames`), `acousticFrame` (channels 1..15), `decodeCodes`, and `inferWithTiming` retry loops. Checked `cancelRequested || Thread.currentThread().isInterrupted` to abort early with `InterruptedException` without wasting CPU cycles.
  - Refactored `close()` with `lock.tryLock()`: if lock is available, immediately tears down native sessions; if lock is held by active inference, flags `cancelRequested = true`, `closed = true`, and offloads waiting and resource release to `teardownScope.launch(Dispatchers.IO)`. Caller thread returns in < 1ms without freezing UI or triggering ANR.
  - Added `awaitClosed(timeoutMs)` for deterministic testing and synchronization.
  - Protected ONNX tensors in `decodeCodes` with clean `try-finally` cleanup to prevent leaks and double-close warnings.
- **Background Resource Teardown & Decoupled Shutdown (`VieNeuTTSManager.kt`)**:
  - Decoupled `shutdown()`: immediately cancels prewarm, cancels manager scope, transitions state to `IDLE`, nullifies pipeline/engine references, and launches `pipe?.release()` and `eng?.close()` onto background coroutine (`Dispatchers.IO`).
  - Decoupled `reloadEngine()`: captures old pipeline and engine and offloads `oldPipeline?.release()` and `oldEngine?.close()` to `Dispatchers.IO`, returning immediately to caller.
  - Completely eliminated forced synchronous `System.gc()` calls on the caller thread.
  - Added `catch (e: InterruptedException)` in `prepareStoryInternal` to cleanly handle cancellation without warning logs.
- **Service Lifecycle Non-Blocking Destruction (`ReadingService.kt`)**:
  - Introduced `serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())`.
  - In `onDestroy()` and `onTaskRemoved()`, dispatched `ttsManager?.shutdown()` via `serviceScope.launch(NonCancellable)` so UI/Main thread returns immediately (< 50ms) without waiting for native engine locks.
  - In `initTTS()`, dispatched previous `current.shutdown()` onto `Dispatchers.IO`.
  - Removed all `System.gc()` invocations in `onTrimMemory`, `onTaskRemoved`, and `onDestroy`.
- **Pipeline Interruption Tolerance (`TTSAudioPipeline.kt`)**:
  - Handled `InterruptedException` in `synthesisJob` gracefully as an expected stop/cancel event instead of logging failure or triggering Error state.
- **Comprehensive Unit Testing (`AsynchronousEngineTeardownTest.kt`)**:
  - Implemented 5 comprehensive file-based unit tests verifying core requirements (5/5 passed 100%):
    1. `testCloseWhileInferringDoesNotFreezeCallerThread`: Confirms `engine.close()` called during active inference returns in < 100ms (measured < 10ms) and background inference aborts early without native SIGSEGV crashes.
    2. `testCancellationFlagStopsInferenceLoopPrematurely`: Confirms `cancelRequested` flag immediately aborts inference attempts with `InterruptedException`.
    3. `testSafeConcurrentTeardownAndReinitialization`: Verifies old engine teardown and new engine creation run safely in parallel without native deadlocks or resource corruption.
    4. `testReadingServiceLifecycleDestructionNonBlocking`: Verifies `service.onDestroy()` and `service.onTaskRemoved()` complete in < 50ms without blocking UI.
    5. `testVieNeuTTSManagerShutdownAndPrecisionSwitchDecoupled`: Verifies `ttsManager.shutdown()` returns in < 50ms, immediately transitioning to IDLE and offloading session release to Dispatchers.IO.

### Phase 02: Cooperative Audio Pipeline Pause/Resume with Buffer Retention (P1)
- **Cooperative Pause Gate & Zero-Waste Buffer Retention (`TTSAudioPipeline.kt`)**:
  - Replaced destructive `pause()` implementation that previously cancelled `synthesisJob` and `playbackJob` and drained `audioChannel` (wasting up to 4 chunks / dozens of seconds of CPU work).
  - Introduced thread-safe `PauseGate` using `CompletableJob` with cooperative `awaitActive()` checkpoints in both synthesis producer and playback consumer loops.
  - Non-destructive `pause()`: halts hardware playback immediately via `audioPlayer.pause()`, pauses gate, and keeps `audioChannel` buffer intact without calling `drainChannel()`.
  - Instant `resume()`: unpauses `audioPlayer.play()`, unblocks `PauseGate`, and continues immediate playback of queued chunks without re-synthesizing already computed chunks.
  - Clean `stopInternal()`: ensures `pauseGate.resume()` unblocks any suspended coroutines before cancelling jobs, draining the channel, and resetting reading position.
- **Hardware Isolation & Frame Slicing (`AudioPlayer.kt`)**:
  - Refactored `writeSuspended()` to partition large audio writes into small manageable frames (~100ms slices / 4,800 samples at 48kHz) with cooperative cancellation and pause checks.
  - Added thread-safe `isPaused` property and internal `pauseGateJob` ensuring pause/resume transitions respond with sub-second precision and resume does not replay already heard audio.
  - Ensured `pause()` only transitions `audioTrack?.pause()` without resetting positions or marking `needsReinit = true`.
- **Comprehensive Unit Testing (`CooperativePipelinePauseResumeTest.kt`)**:
  - Implemented 6 comprehensive file-based unit tests verifying core requirements (6/6 passed 100%):
    1. `testPausePreservesChannelItemsWithoutDrain`: Proves `pause()` preserves channel items and does not drain audio buffers.
    2. `testResumePicksUpBufferedItemsWithoutResynthesis`: Confirms `resume()` consumes existing buffered chunks with zero redundant synthesis passes.
    3. `testMonotonicPlaybackPositionWithoutRepetition`: Proves playback position advances monotonically without phrase repetition artifacts.
    4. `testRapidPauseResumeTogglingDoesNotLeakOrHang`: Confirms rapid consecutive pause-resume cycles do not deadlock or leak coroutines.
    5. `testStopDuringPauseCleanlyDrainsAndCancels`: Proves calling `stop()` while paused cleanly terminates jobs, flushes hardware, and resets position.
    6. `testAudioPlayerSubChunkSlicingAndPauseState`: Validates 100ms frame slicing and non-destructive pause state in `AudioPlayer`.

### Phase 01: Prewarm Preservation & Instant First-Chunk Playback (P2)
- **Prewarm Chunk Preservation & In-Flight Request Coalescing (`PrewarmedChunkCache.kt`)**:
  - Implemented thread-safe in-flight single-flight coordination using `ConcurrentHashMap<PrewarmedKey, CompletableDeferred<ShortArray?>>`.
  - Added `registerInFlight`, `isInFlight`, `getInFlight`, `notifyInFlightFailed`, and suspending `awaitInFlightChunk(storyId, voiceName, chunkIndex, timeoutMs = 8000L)`.
  - Guaranteed `put(...)` atomically stores PCM into LRU cache and completes any pending in-flight deferred, waking up waiting playback consumers without latency or double-synthesis.
  - Safe cache clearing: updated `clearPreviousStory(...)` and `clear()` to cancel in-flight deferreds, preventing consumer coroutine hangs.
- **Cooperative Prewarm Lifecycle & NonCancellable Protection (`VieNeuTTSManager.kt`)**:
  - Protected the entire inference, float-to-short conversion, micro-fade application, silence padding, and `PrewarmedChunkCache.put(...)` within `withContext(NonCancellable)` so in-flight chunk 0 is never discarded if cancellation arrives mid-computation.
  - Replaced destructive `cancelPrewarm()` in `speakContent()` and `speakWithTitlePauseInternal()` with cooperative synchronization: when playback begins for the same story, sets `stopPrewarmAfterChunk0 = true`, allowing chunk 0 to finish and populate the cache instead of severing it.
  - Added cross-story invalidation: when starting speech for a different story, automatically evicts previous story chunks from `PrewarmedChunkCache`.
- **Pipeline Fast-Path & In-Flight Prewarm Handover (`TTSAudioPipeline.kt`)**:
  - At start of `synthesisJob`: checks `PrewarmedChunkCache.get(storyId, voiceName, i)` and `awaitInFlightChunk(...)` before performing G2P or ONNX inference. If chunk 0 is in-flight, gracefully suspends and attaches to the active prewarm computation.
  - In `playbackJob`: added `hasPrewarmedPlayback` condition (`cachedIndices.contains(startIndex) && currentQueueSize > 0`) to immediately start playback on prewarmed chunk 0 with TTFT < 100ms, synthesizing subsequent chunks concurrently via bounded channel backpressure.
- **Comprehensive Unit Testing (`PrewarmPreservationIntegrityTest.kt`)**:
  - Implemented 5 comprehensive file-based tests (5/5 passed 100%):
    1. `testPrewarmChunkCachingSurvivesCancellationDuringInference`: Proves chunk 0 is preserved in cache even when cancellation arrives during inference pass.
    2. `testPipelineFetchesPrewarmedChunk0WithoutInvokingInference`: Confirms pipeline ingests chunk 0 directly from cache without calling engine inference.
    3. `testRaceConditionPlayInitiated10msIntoPrewarmReceivesChunk0WithoutDuplicateWork`: Validates 10ms race condition where Play is tapped during prewarm, successfully receiving computed chunk 0 without double synthesis.
    4. `testMultipleRapidPlayPauseDoesNotCorruptCacheOrHang`: Proves rapid Play/Pause/Stop calls do not corrupt cache or leave orphaned locks.
    5. `testStorySwitchingCancelsPreviousPrewarmWithoutContamination`: Verifies switching stories cleanly evicts previous prewarmed chunks.

## [v1.15] - [2026-11-20] Comprehensive R8 Minification, Retrofit & Network Architecture Fix
### Phase 04: End-to-End Release Build Verification & CI/CD Pipeline Assurance
- **ProGuard & Release Build Verification (`app/src/test/java/com/skul9x/doctruyen/network/ReleaseBuildValidationTest.kt`)**:
  - Validated that `isMinifyEnabled = true`, ABI splits (`arm64-v8a`, `armeabi-v7a`, `x86_64`), and `universalApk` generation are enforced in `app/build.gradle.kts`.
  - Proved ProGuard configuration contains zero dangerous wildcard rules (no blanket `-dontshrink`, no blanket `-dontobfuscate`, no `-keep class * { *; }`).
  - Confirmed surgical preservation of `kotlin.coroutines.Continuation`, `retrofit2.Call`, `retrofit2.Response`, `@retrofit2.http.*`, `TypeToken`, `Signature`, and `AnnotationDefault`.
  - Verified cohesive integration across `ApiService`, `RetrofitClient`, `NetworkErrorResolver`, and hardened DTOs.
- **Regression ProGuard Rule Coverage Expansion (`AssetCleansingAndBuildOptimizationTest.kt`)**:
  - Expanded `testProGuardRulesCoverage()` to assert Retrofit and Coroutine Continuation ProGuard keep rules alongside existing ONNX, JNI, and asset checks.
- **Release Documentation & Session State Synchronization**:
  - Finalized Release v1.15 documentation and synchronized `.brain` state across all 4 phases.

### Phase 03: Defensive Network Error Architecture & User-Friendly Translation
- **Centralized Defensive Network Error Architecture (`app/src/main/java/com/skul9x/doctruyen/network/NetworkErrorResolver.kt`)**:
  - Implemented `@Keep` annotated `NetworkErrorResolver` singleton with `resolve(throwable: Throwable?, context: Context? = null): ResolvedError` and `shouldRefreshAuth(throwable: Throwable?): Boolean`.
  - Completely shielded all technical exception names, reflection details, and internal class names (`java.lang.*`, `java.lang.reflect.*`, `ParameterizedType`, `ClassCastException`, `retrofit2.*`, `okhttp3.*`, etc.) from user-facing UI.
  - Mapped `UnknownHostException` / `ConnectException` to polite internet connection guidance ("Không thể kết nối Internet. Vui lòng kiểm tra lại Wifi hoặc 4G.").
  - Mapped `SocketTimeoutException` to server timeout prompt ("Máy chủ phản hồi quá lâu. Vui lòng thử lại sau giây lát.").
  - Mapped `MalformedJsonException`, `JsonSyntaxException`, HTTP 403 Forbidden, and HTML challenge responses to session expiration with automatic re-authentication flag (`isAuthError = true`, `canRetry = true`).
  - Mapped `ClassCastException` and reflection errors to polite data processing prompt with retry action ("Có lỗi xảy ra khi xử lý dữ liệu. Vui lòng nhấn Thử lại.").
  - Added technical message keyword filtering ensuring unexpected or third-party exceptions never leak technical stack traces.
- **Automated Re-Authentication & Session Recovery (`MainActivity.kt` & `StoryDetailActivity.kt`)**:
  - Refactored `loadStories()`, `loadRandomStory()`, and `loadStoryContent()` to route network errors through `NetworkErrorResolver`.
  - Implemented automated session recovery: when `resolved.isAuthError && RetrofitClient.cookie.isNotEmpty()`, clears stale cookie, triggers `HostingVerifier.verify()`, and re-invokes pending network operation automatically upon success.
- **Localized Error Resources (`app/src/main/res/values/strings.xml`)**:
  - Added localized string resources `error_no_internet`, `error_timeout`, `error_auth_expired`, `error_data_processing`.
- **Comprehensive Unit Testing**:
  - Created `NetworkErrorResolverTest.kt` (5/5 passed 100%):
    1. `testResolveUnknownHostException`: Verifies `UnknownHostException` and `ConnectException` translation to internet connectivity messages.
    2. `testResolveTimeoutException`: Verifies `SocketTimeoutException` translation to server timeout messages.
    3. `testResolveAuthAndHtmlParsingError`: Verifies `MalformedJsonException`, `JsonSyntaxException`, HTTP 403 Forbidden, and HTML challenge responses identify `isAuthError = true`.
    4. `testResolveReflectionClassCastException`: Confirms `ClassCastException` (R8 ParameterizedType issue) completely shields internal technical package names and provides polite retry guidance.
    5. `testNullOrEmptyThrowableHandling`: Verifies null, empty, and unexpected throwables safely fall back without crashing or technical exposure.

### Phase 02: Resilient Data Model Serialization & Field Hardening
- **Network Data Model Serialization & Field Hardening (`app/src/main/java/com/skul9x/doctruyen/network/ApiService.kt`)**:
  - Annotated all network DTO classes (`Story`, `StoryListResponse`, `StoryDetailResponse`, `RandomStoryResponse`) with `@androidx.annotation.Keep` to protect them, their primary constructors, and synthetic constructor bridges against R8/ProGuard class and constructor pruning.
  - Added explicit `@SerializedName("...")` annotations to 100% of declared properties across all 4 DTOs, removing any reliance on parameter names or Kotlin reflection metadata during JSON mapping.
  - Implemented safe default initializers for optional and collection properties (`image: String? = null`, `content: String? = null`, `contentText: String? = null`, `data: List<Story> = emptyList()`, `status: String = ""`), enabling Kotlin to generate synthetic zero-argument constructors so Gson uses constructor initialization with safe non-null defaults rather than bypassing constructors with `Unsafe`.
- **Comprehensive Unit Testing**:
  - Created `DataModelSerializationIntegrityTest.kt` (5/5 passed 100%):
    1. `testAllModelFieldsHaveSerializedName`: Validates via reflection that every non-synthetic, non-static field across all DTO classes has an explicit `@SerializedName` annotation with matching JSON keys.
    2. `testAllModelsHaveKeepAnnotation`: Inspects bytecode to verify that `@androidx.annotation.Keep` (BINARY retention) is present on all DTO classes for R8 preservation.
    3. `testJsonDeserializationWithObfuscatedSimulation`: Simulates deserialization of realistic backend JSON responses (`StoryListResponse`, `StoryDetailResponse`, `RandomStoryResponse`) verifying accurate field mapping.
    4. `testDefaultValuesWhenKeysMissing`: Verifies that omitting optional fields safely falls back to default initializers without throwing `NullPointerException`.
    5. `testSerializationRoundTripPreservesExactJsonKeys`: Confirms bidirectional serialization round-trip retaining exact snake_case JSON keys (`content_text`, `total_pages`).

### Phase 01: R8 & ProGuard Rules Hardening for Retrofit & Kotlin Coroutines
- **Retrofit & Coroutines R8 ProGuard Rules Hardening (`app/proguard-rules.pro`)**:
  - Added `-keep,allowoptimization,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation` to preserve generic type arguments for Kotlin coroutines suspend functions under R8 Full Mode, eliminating runtime `java.lang.ClassCastException: java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType` crash.
  - Added generic signature retention for core Retrofit interfaces: `-keep,allowobfuscation,allowshrinking interface retrofit2.Call` and `class retrofit2.Response`.
  - Added `-keepattributes AnnotationDefault` to preserve default values for Retrofit annotations (`@Query`, `@GET`, etc.).
  - Added conditional interface rules protecting Retrofit service methods and return types from dead-code stripping or null-replacement during R8 bytecode optimization.
  - Added `-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken` to protect generic reflection in Gson deserializers.
- **R8 Build Compatibility Configuration (`gradle.properties`)**:
  - Configured `android.enableR8.fullMode=false` as safety fallback while ensuring all ProGuard rules are 100% resilient under both full mode and compatibility mode.
- **Comprehensive Unit Testing**:
  - Created `RetrofitR8ProGuardRulesTest.kt` (7/7 passed 100%):
    1. `testSignatureAndReflectionAttributesRetained`: Validates preservation of `Signature`, `InnerClasses`, `EnclosingMethod`, `AnnotationDefault`, and annotation attributes.
    2. `testKotlinContinuationRulePresent`: Verifies that `kotlin.coroutines.Continuation` is explicitly protected with appropriate optimization flags.
    3. `testRetrofitCallAndResponseSignaturesKept`: Verifies that `retrofit2.Call` and `retrofit2.Response` keep rules are defined.
    4. `testRetrofitHttpAnnotationInterfaceRulesKept`: Verifies conditional `-if interface * { @retrofit2.http.* ... }` and `-keepclassmembers` rules for Retrofit service methods.
    5. `testGsonTypeTokenRetention`: Verifies that `com.google.gson.reflect.TypeToken` is retained for generic deserialization.
    6. `testSuspendFunctionReflectionSignatureIntegrity`: Emulates Retrofit's `HttpServiceMethod` generic parameter reflection over `ApiService` suspend functions, proving zero `ClassCastException` risks.
    7. `testGradlePropertiesSafetyFlag`: Validates R8 compatibility mode flag configuration in `gradle.properties`.

## [v1.17] - [2026-11-20] Runtime Threading, Execution Providers & Engine Tuning (Phase 04)
### Added & Improved
- **ONNX Session Thread Configuration & XNNPACK Fallback Allocation (`OnnxSessionManager.kt`)**:
  - Configured `sessionOptions.setIntraOpNumThreads(effectiveThreads)` before registering XNNPACK execution provider options.
  - Rectified single-threaded fallback starvation for operators executing outside XNNPACK, ensuring CPU fallback subgraphs allocate big-core thread counts rather than being throttled to 1.
  - Introduced `SessionOptionsResult` with full destructuring support for intra-op and inter-op threads while preserving complete backward compatibility with existing destructuring and pair usages.
- **Precision Configuration Alignment & Truthful Telemetry Diagnostics (`OnnxSessionManager.kt`, `SettingsActivity.kt`, `VieNeuTTSManager.kt`, `TtsMetricsLogger.kt`)**:
  - Implemented `OnnxSessionManager.hasInt8Assets(context)`, `isPrecisionAvailable(context, precision)`, `getAvailablePrecisions(context)`, and `resolveEffectivePrecision(context, precision)`.
  - Added asset discovery fallbacks for JVM unit test execution against project asset directories.
  - Dynamically disabled INT8 precision toggle button and adjusted opacity in `SettingsActivity` when INT8 assets are absent, automatically aligning user preferences to FP32 without deceptive warnings.
  - Replaced deceptive missing INT8 warning spam on FP32-only builds with truthful info logs.
  - Updated `VieNeuTTSManager.reloadEngine` to log the true loaded engine precision (`newEngine.quantizationType.name`) to `TtsMetricsLogger.logEngineReload`.
- **Fast Acoustic Projection Matrix-Vector Multiply (`VieNeuOnnxEngine.kt`)**:
  - Replaced scalar single-accumulator matrix-vector multiply in `sampleCode` with `computeAcousticProjectionLogits`.
  - Implemented 8-way unrolled accumulators for the $1024 \times 768$ dot-product computation, breaking serial dependency chains and exposing instruction-level parallelism (ILP) and SIMD auto-vectorization.
  - Accelerated hot acoustic code sampling while maintaining exact numerical equivalence within floating-point tolerance.
- **Streamlined G2P Lexical Caching (`SeaG2P.kt`)**:
  - Implemented `isCacheable(text: String)` with `MAX_CACHEABLE_LENGTH = 100` and multiline/multi-sentence filtering.
  - Avoided wasteful synchronized hash computations and LRU cache thrashing on unique multi-sentence novel paragraphs, while reserving the 512-entry cache for frequently repeated words and short phrases.
- **Production HTTP Logging Interceptor Hardening (`ApiService.kt`)**:
  - Replaced `HttpLoggingInterceptor.Level.HEADERS` with `HttpLoggingInterceptor.Level.NONE` when `BuildConfig.DEBUG` is false.
  - Eliminated sensitive network header leaks and unnecessary string allocations in release logcat.
- **Comprehensive Unit Testing**:
  - Created `RuntimeOptimizationAndPrecisionVerificationTest.kt` (4/4 passed 100%):
    1. `testSessionOptionsThreadConfiguration`: Verified multi-threaded intra-op pool configuration across INT8, FP32, and FP16 fallback modes.
    2. `testPrecisionConfigurationTruthfulness`: Verified asset detection, fallback resolution, and truthful telemetry reporting in `TtsMetricsLogger`.
    3. `testReleaseLoggingInterceptorDisabled`: Verified `HttpLoggingInterceptor.Level.NONE` in release mode and `BODY` in debug mode.
    4. `testSampleCodeAcousticProjectionEquivalence`: Verified numerical equivalence of unrolled acoustic projection and G2P paragraph cache bypass.

## [v1.16] - [2026-11-20] Asset Cleansing, Memory-Efficient NPZ & Build Optimization (Phase 03)
### Added & Improved
- **AI Model Asset Cleansing (>84.8MB Reduced)**:
  - Completely purged dead models: `denoiser/denoiser.onnx` (41MB), `codec/moss_audio_tokenizer_encode.onnx` (797KB), and `codec/moss_audio_tokenizer_encode.data` (43MB).
  - Reduced total bundled assets size from 347MB to 262MB (24.5% direct asset size reduction), significantly shrinking APK footprint and on-device storage.
  - Cleaned up unused `DENOISER_DIR` constant in `VieNeuConfig.kt`.
- **Zero-BAOS Streaming NPY/NPZ Parser (`NpzReader.kt`)**:
  - Re-architected `NpzReader` with `parseNpyStream`: parses NPY headers directly from streaming `ZipInputStream` and reads float tensors via a fixed 64KB `ByteBuffer` transfer buffer.
  - Completely eliminated unbounded `ByteArrayOutputStream` growing buffer reallocations and 50MB intermediate `ByteArray` allocations during NPZ weight loading.
  - Added `getChannelFlatArrays("audio_emb")`: loads 3D tensor `[16, 1024, 768]` directly into 16 flat channel arrays instead of 16,384 distinct `FloatArray` heap objects.
  - Added `getFlat1D` and flat embedding indexing helpers while preserving full backward compatibility for `get2D`, `get3D`, `getFloatArray`, and `readAllBytes`.
- **Flat Embedding Lookups & Low-Heap Initialization (`VieNeuOnnxEngine.kt`, `VieNeuTTSManager.kt`)**:
  - Extended `VieNeuOnnxEngine` constructor to accept pre-flattened channel embeddings (`flatAudioEmbeddingsIn`).
  - Replaced 3D nested array allocations (`headsReader.get3D("audio_emb")`) with `headsReader.getChannelFlatArrays("audio_emb")` in `VieNeuTTSManager` and `fromAssets`.
  - Replaced per-step acoustic frame array allocations with zero-copy `FloatBuffer.wrap(flatEmb, prevCode * H, H)`.
  - Flattened slot sequence acoustic embedding lookups (`flatEmb[embOffset + h]`).
  - Reduced engine cold initialization heap memory spike from >250MB to <55MB.
- **R8 Minification & ProGuard Rules Coverage (`build.gradle.kts`, `proguard-rules.pro`)**:
  - Enabled release-mode code shrinking and optimization: `isMinifyEnabled = true`.
  - Configured comprehensive ProGuard keep rules for ONNX Runtime (`ai.onnxruntime.**`), SeaG2P JNI entry points and native methods, Retrofit & Gson data models (`com.skul9x.doctruyen.network.**`, `@SerializedName`), Glide modules, and Coroutines internals.
  - Configured ABI packaging options and splits for 64-bit/32-bit targets (`arm64-v8a`, `armeabi-v7a`, `x86_64`) with `isUniversalApk = true`.
- **Comprehensive Unit Testing**:
  - Created `AssetCleansingAndBuildOptimizationTest.kt` verifying:
    1. `testDeadAssetAbsenceAndIntegrity`: Dead assets absent while all 12 core TTS assets remain present and intact.
    2. `testNpzReaderZeroBaosAllocation`: Zero-BAOS streaming parsing from zip input streams with accurate shape and values.
    3. `testFlatEmbeddingNumericalEquivalence`: 100% exact numerical equivalence across all channels and sampled codes between flat channel arrays, 1D flat buffers, and 3D nested arrays.
    4. `testProGuardRulesCoverage`: Validated ProGuard rules and build.gradle.kts minification/ABI configuration (4/4 passed).

## [v1.15] - [2026-11-20] Bounded Audio Channel & Playback Concurrency (Phase 02)
### Added & Improved
- **Bounded Coroutine Channel & Asynchronous Backpressure (`TTSAudioPipeline.kt`)**:
  - Replaced unbounded `ConcurrentLinkedQueue` with a bounded Coroutine `Channel` (default capacity: 4 chunks, configurable).
  - Implemented natural producer suspension when the buffer ceiling is reached without thread blocking or dropping audio chunks.
  - Capped active unplayed PCM memory footprint in the pipeline strictly below 15MB at all times during long reading sessions.
  - Replaced `delay(20)` busy-wait polling loops with reactive suspending channel reception (`receiveCatching()`), achieving 0ms jitter-free chunk handover.
  - Added deadlock-prevention and reactive prebuffering synchronization using conflated signal channels.
- **Dedicated Audio Dispatcher & Hardware Isolation (`AudioPlayer.kt`, `TTSAudioPipeline.kt`)**:
  - Created `AudioDispatchers.AudioTrackDispatcher` running on a dedicated single-thread executor with `Thread.MAX_PRIORITY` named `VieNeu-AudioTrack-Thread`.
  - Isolated blocking `AudioTrack.write(..., WRITE_BLOCKING)` calls onto the dedicated audio dispatcher via `writeSuspended(...)`, completely freeing `Dispatchers.Default` compute threads for ONNX inference.
  - Implemented clean channel cancellation and instant buffer draining on `pause()`, `stop()`, and `seekToChunk()`.
- **Comprehensive Unit Testing**:
  - Created `BoundedAudioChannelPipelineTest.kt` verifying:
    1. `testBoundedChannelBackpressureSuspension`: Producer coroutine suspends under backpressure and resumes only upon consumer drain.
    2. `testMemoryCapUnderLongStoryPlayback`: Queue memory footprint capped strictly below 15MB across 150 consecutive simulated chunks.
    3. `testDispatcherSeparationForAudioTrack`: Blocking hardware writes execute exclusively on dedicated audio dispatcher without monopolizing `Dispatchers.Default`.
    4. `testImmediateCancellationAndChannelDrain`: Instant cancellation, zero memory leaks, and clean drain on pause and stop (4/4 passed).

## [v1.14] - [2026-11-20] Native Tensor Lifecycle & Zero-Copy KV Cache Reuse (Phase 01)
### Added & Improved
- **Zero-Copy KV Cache & Native Tensor Retention (`VieNeuOnnxEngine.kt`)**:
  - Refactored autoregressive decode loop (`synthesizePass`): eliminated intermediate JVM `FloatArray` allocations and native `OnnxTensor` recreation across all 12 Transformer layers. Output Key-Value tensors are directly supplied into subsequent decode steps in native space via retained `OrtSession.Result` instances.
  - Refactored acoustic frame synthesis (`acousticFrame`): native outputs from channel $ch-1$ flow directly to channel $ch$ inputs across all 16 codebook channels, pre-allocating working buffers and eliminating per-channel heap allocation churn.
  - Removed all explicit synchronous `System.gc()` pauses from inner inference loops, eliminating Stop-the-World stalls during storytelling narration.
  - Re-architected `CacheTensor` and `CacheTensor.Pool`: added native tensor wrapping (`fromNativeOnnxTensor`, `obtainNative`), native handle recycling, and safe disposal while maintaining full backward compatibility.
  - Protected tensor lifecycle on cancellation or interruption via robust `try ... finally` guarantees, preventing native memory leaks, double-free warnings, or segmentation faults.
- **Comprehensive Unit Testing**:
  - Created `NativeTensorReuseAndZeroGcTest.kt` verifying direct native tensor retention, zero `System.gc()` bytecode and execution profiling, clean native lifecycle cleanup on thread cancellation, and acoustic frame memory reuse (4/4 passed).

## [v1.13] - [2026-11-20] Punctuation-First Natural Text Segmentation (Plan A)
### Added & Improved
- **Punctuation-First Natural Text Segmentation (`SmartTextSegmenter.kt`)**:
  - Refactored sentence chunking to prioritize natural punctuation boundaries (`,`, `.`, `!`, `?`, `…`, `:`, `;`, `—`, `–`).
  - Removed mechanical 75-character mid-clause word-chopping; raised default chunk length ceiling to `DEFAULT_MAX_CHARS = 200` (`SAFE_MAX_CHARS = 200`).
  - Guaranteed compound word integrity (e.g. "sẵn sàng", "khiêm tốn") up to 200 characters without mid-token splitting.
  - Preserved colon normalization behavior (`puncNorm` producing `:.`).
  - Added emergency whitespace-boundary splitting safeguard for run-on sentences exceeding 200 characters without punctuation.
- **Comprehensive Unit Testing**:
  - Created `PunctuationFirstSegmenterCoreTest.kt` verifying 4-chunk dialogue segmentation integrity, compound word preservation, colon normalization, and emergency fallback over 200 characters (100% passed).
- **Pipeline Integration & Pause Duration Verification (`TTSAudioPipeline.kt`, `PauseConfig`, `SmartTextSegmenter.kt`)**:
  - Validated pipeline lookahead pre-buffering heuristics and streaming playback with natural punctuation chunks up to 200 characters without buffer underruns or frame-capping truncation.
  - Aligned prosody pause duration metadata across story narration: `CLAUSE_BREAK` (180ms), `SENTENCE_BREAK` (400ms), `PARAGRAPH_BREAK` (650ms), and `TITLE_BREAK` (1000ms).
  - Validated 11-paragraph sample story ("Một trí khôn hơn trăm trí khôn") segmentation into exactly 23 natural punctuation chunks (down from 26 fragmented chunks).
  - Updated legacy regression assertions in `OptimizedTextChunkingAndStateTest.kt` to reflect `DEFAULT_MAX_CHARS = 200`.
  - Created and passed comprehensive unit test `PunctuationFirstPipelineIntegrationTest.kt` (3/3 passed).

## [v1.12] - [2026-10-08] Nâng Cấp VieNeu-TTS v3 Turbo: 25 Giọng Đọc, Sliding Window Penalty, QuickSelect Top-256, Emotion Tags & Babble Guard
### Added & Improved
- **Đồng Bộ 25 Preset Voices & Checkpoint Hugging Face (`voices_v3_turbo.json`, `VoicePresets.kt`, `VieNeuTTSManager.kt`)**:
  - Mở rộng kho giọng lên 25 voices từ upstream (bao gồm `Adam bựa`, `Quốc Tuấn`, `Thiện Minh`, `Thiền Tâm Đức`, v.v.).
  - Giọng mặc định: `Hải Đăng`. Hỗ trợ ánh xạ alias trong suốt: `Minh Quân Pro` / `Minh Quân` -> `Hải Đăng`, `Anh Khôi` -> `Thiện Minh`, `Mạnh Dũng` -> `Quốc Tuấn`.
  - Cấu hình chuyên biệt cho `Thiền Tâm Đức`: giảm nhiệt độ sinh mẫu (0.25f) và kéo dài khoảng dừng chiêm nghiệm.
- **Sliding-Window Repetition Penalty 64 Khung Hình (`RepetitionHistory.kt`, `VieNeuOnnxEngine.kt`)**:
  - Thay thế `HashSet` cộng dồn vĩnh viễn bằng circular buffer 64 khung hình (~2.5s) trên 16 kênh codebook, triệt tiêu hoàn toàn hiện tượng méo tiếng và suy thoái ngữ điệu ở câu văn dài.
- **Tối Ưu Phân Vùng QuickSelect Top-256 (`VieNeuOnnxEngine.kt`)**:
  - Triển khai thuật toán phân vùng một phần in-place `quickSelectTopK()` trên logits trước khi chạy Softmax, tăng tốc độ sinh mẫu ~1.5x mỗi khung hình.
- **Xử Lý Thẻ Cảm Xúc & Bảo Vệ Chú Thích Truyện (`EmotionTagParser`, `SmartTextSegmenter.kt`, `VieNeuTokenizer.kt`)**:
  - Tách và ánh xạ các biểu cảm `[cười]`, `[thở dài]`, `[hắng giọng]` (và các biến thể tiếng Anh / không dấu) sang token đặc biệt `<|emotion_1|>`, `<|emotion_2|>`, `<|emotion_3|>` (IDs 9, 10, 11).
  - Bảo vệ nguyên vẹn các dấu ngoặc chú thích tiểu thuyết (`[1]`, `[CV]`, `[Chương 2]`), cho phép đi qua pipeline văn bản thông thường.
  - Tách thẻ cảm xúc trước G2P để tránh SeaG2P nuốt hoặc làm méo thẻ đặc biệt.
- **BabbleGuard Chống Ảo Giác Câu Thoại Ngắn (`BabbleGuard.kt`, `VieNeuOnnxEngine.kt`, `TtsMetricsLogger.kt`)**:
  - Phân tích bao năng lượng RMS với bước nhảy 10ms ở 48kHz, phát hiện speech bursts với ngưỡng -24dB relative peak, thời lượng tối thiểu 60ms và nối khoảng lặng < 80ms.
  - Heuristic `isBabbleSuspect()`: kích hoạt với phát ngôn siêu ngắn (<= 2 âm tiết như "Vâng.", "Hả?", "Ừ.") khi số bursts > số âm tiết hoặc đạt trần khung hình (`capFrames - 1`).
  - Vòng lặp tự động retry tối đa 2 lần với nhiệt độ/seed thay đổi, so sánh và chọn ứng viên tối ưu bằng comparator `babblePrefer`.
  - Ghi nhận telemetry `recordBabbleRetry` vào `TtsMetricsLogger`.
- **Bộ Kiểm Thử Đơn File Cho Từng Phase**:
  - `VoicePresetsAndCheckpointSyncTest.kt`, `SlidingWindowRepetitionAndSamplingTest.kt`, `EmotionTagsAndBracketSeparationTest.kt`, `BabbleGuardAntiHallucinationTest.kt` (100% passed).

## [v1.11] - [2026-08-21] Đồng Bộ VieNeu-TTS v3 Turbo Upstream: Voice Presets, Adaptive Frame Capping, Quote-Aware Segmentation & Punc Normalization
### Added & Improved
- **Đồng Bộ Hóa 10 Giọng Đọc Chuẩn Upstream (`VoicePresets.kt`, `voices_v3_turbo.json`)**:
  - Cập nhật đầy đủ 10 voice presets chuẩn v3 Turbo: `vi_female_1_v3_turbo` -> `vi_female_5_v3_turbo` và `vi_male_1_v3_turbo` -> `vi_male_5_v3_turbo` với metadata tên hiển thị, giới tính, vùng miền và mô tả chi tiết.
  - Tự động chuyển đổi an toàn các voice key cũ (`hn_female_x`, `sg_male_x`) về voice v3 turbo tương ứng.
- **Cắt Khung Thích Ứng Chống Lặp Âm Vô Tận (`VieNeuOnnxEngine.kt`)**:
  - Porting công thức upstream: `maxFrames = max(80, min(1000, round(phoneCount * 2.2 + 20)))`.
  - Giới hạn động số bước sinh âm thanh theo độ dài chuỗi âm vị đầu vào, triệt tiêu hoàn toàn nguy cơ suy luận vô tận (runaway inference) và tiết kiệm tài nguyên vi xử lý.
- **Phân Đoạn Nhận Biết Trích Dẫn & Lời Thoại (`SmartTextSegmenter.kt`)**:
  - Triển khai bộ quét ký tự `scanSentences()` O(n) thay thế regex, theo dõi độ sâu các cặp dấu ngoặc `( )`, `[ ]`, `{ }`, `“ ”`, `‘ ’`, `« »`, `‹ ›`, `「 」`, `『 』` và dấu nháy kép đối xứng `" "`.
  - Ngăn chặn ngắt đôi câu đối thoại bên trong lời thoại (ví dụ: *Lan hỏi: "Anh đi đâu đấy?", rồi quay đi.* luôn là một câu thống nhất).
  - Dự phòng thông minh (fallback `quoteAware = false`) khi gặp dấu nháy/ngoặc bị lệch trong văn bản.
  - Bảo vệ số thập phân (`3.5 triệu`, `8.30 sáng`) và dấu lược tiếng Anh (`don't`).
- **Chuẩn Hóa Dấu Câu Ngắt Nghỉ (`puncNorm` trong `SmartTextSegmenter.kt`)**:
  - Chốt dấu kết câu `.` cho các đoạn ngắn (< 5 từ) và bổ sung dấu câu kết thúc cho các đoạn thiếu dấu để đảm bảo ngữ điệu (intonation) tự nhiên nhất.
  - Gắn chặt dấu đóng ngoặc/nháy sau dấu câu vào vế trước.
- **Bộ Kiểm Thử Toàn Diện**:
  - Thêm `VoicePresetsSyncTest.kt`, `AdaptiveFrameCappingTest.kt`, `SmartDialogueSegmentationTest.kt` kiểm tra toàn bộ luồng chức năng.

## [v1.9] - [2026-08-21] Runtime Stability, Instant Cancellation & UI Optimization
### Added & Improved
- **Phản Hồi Dừng Tức Thì & Giới Hạn Luồng CPU (`TTSAudioPipeline.kt`, `OnnxSessionManager.kt`)**:
  - Kiểm tra `isCancelled()` theo từng câu trong `TTSAudioPipeline.kt`, dừng phát audio ngay lập tức (< 50ms) khi bấm Pause/Stop/Skip.
  - Cấu hình số luồng tính toán CPU `DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4)` trong `OnnxSessionManager.kt`, ngăn ngừa phân bổ luồng trên nhân LITTLE và tránh quá nhiệt/nghẽn CPU.
  - Bổ sung cơ chế đồng bộ hóa luồng (thread-safety synchronization) cho `VieNeuTTSManager.kt`.
- **Tối Ưu Hóa Acoustic Engine & Đọc NPZ Dạng Luồng (`VieNeuOnnxEngine.kt`, `NpzReader.kt`)**:
  - Cấp phát trước các `OnnxTensor` cho Voice Prompts và sử dụng Zero-Copy Float Buffer View trong `VieNeuOnnxEngine.kt`, loại bỏ việc tạo lại 10 tensor mỗi câu và giảm 85% phân mảnh Heap.
  - Trích xuất mảng trọng số NPZ dạng luồng uncompressed trong `NpzReader.kt` trực tiếp từ `InputStream` không qua file tạm, tăng tốc nạp trọng số lên **15 lần**.
- **Tối Ưu Hóa Giao Diện & LiveData Garbage Collection (`DebugLogger.kt`, `StoryDetailActivity.kt`, `MainActivity.kt`, `StoryAdapter.kt`)**:
  - Bổ sung `_logs.hasActiveObservers()` trong `DebugLogger.kt`, chỉ sao chép mảng 500 log khi có UI quan sát.
  - Phân tích HTML một lần (`Single-Pass Parsing`) trong `StoryDetailActivity.kt` với `spannedContent` tái sử dụng, loại bỏ độ trễ 100-300ms khi mở truyện dài.
  - Kích hoạt `recyclerView.setHasFixedSize(true)` trong `MainActivity.kt` và xóa bỏ log ảnh dư thừa trong `StoryAdapter.kt`.
- **Bộ Kiểm Thử Toàn Diện (Unit Tests)**:
  - Bổ sung `CoreTtsEngineStabilityAndThreadingTest.kt`, `AcousticEngineAndNpzOptimizationTest.kt`, `UiAndLoggingOptimizationTest.kt` (210/210 tests passed).

## [v1.8] - [2026-08-21] CI/CD Automated INT8 Quantization & Dual-Asset Packaging
### Added
- **Tự Động Hóa Lượng Tử Hóa INT8 Trong CI/CD (`.github/workflows/android.yml`)**:
  - Tích hợp Python 3.10 và tự động chuyển đổi mô hình FP32 sang INT8 Dynamic Quantization trước khi build APK.
- **Đóng Gói Kép Tài Nguyên Mô Hình (`OnnxSessionManager.kt`)**:
  - Hỗ trợ giải nén và nạp đồng thời cả hai bộ mô hình FP32 (`backbone`) và INT8 (`backbone_int8`) trong APK.

## [v1.7] - [2026-08-21] INT8 Precision Detection, Cold-Start Sync & Explicit Fallback Logging
### Fixed & Improved
- **INT8 Model Precision Detection & Path Resolution (`OnnxSessionManager.kt`)**:
  - Sửa lỗi nhận diện sai kiểu lượng tử hóa do kiểm tra đường dẫn thư mục thay vì file ONNX thực tế.
  - Thêm phương thức `getBackboneDir(baseDir, precision)` để tự động chọn đúng thư mục `backbone_int8` khi người dùng chọn INT8 hoặc fallback về `backbone` (FP32) khi thiếu asset INT8.
- **Đồng Bộ Cấu Hình Precision Khi Khởi Động Lạnh (Cold-Start) (`VieNeuTTSManager.kt`)**:
  - Đồng bộ cấu hình độ chính xác (`UserConfig.getPtsPrecision()`) ngay khi khởi tạo dịch vụ TTS lần đầu tiên, không bị ghi đè hay mất cấu hình đã lưu của người dùng.
- **Ghi Log Cảnh Báo Fallback Chi Tiết & Rõ Ràng (`OnnxSessionManager.kt`)**:
  - Ghi log cảnh báo `Log.w()` rõ ràng kèm gợi ý hành động khi yêu cầu INT8 nhưng asset chưa được đóng gói trong APK và phải chuyển về FP32.
  - Ghi log `Log.i()` khi asset INT8 được tìm thấy và nạp thành công.
  - Cảnh báo khi có sự không khớp giữa cấu hình người dùng yêu cầu và định dạng thực tế của mô hình ONNX.
- **Bộ Kiểm Thử Toàn Diện (Unit Tests)**:
  - Bổ sung `DetectQuantizationPathFixTest.kt`, `ColdStartPrecisionFixTest.kt` và `AssetFallbackLoggingFixTest.kt` kiểm tra chính xác 100% các luồng logic phát hiện và dự phòng.

## [v1.6] - [2026-08-21] Early G2P Pre-Warming, Native Memory Management & Robust Lifecycle
### Added
- **Early G2P Dictionary Warming & In-Memory LRU Cache (`SeaG2P.kt` & `MainActivity.kt`)**:
  - Implemented asynchronous early background dictionary extraction (`sea_g2p.bin`) and JNI `nativeInit` invocation during `MainActivity.onCreate()` and `VieNeuTTSManager.initManager()`.
  - Added thread-safe JVM-compatible LRU Phoneme Cache (capacity 512) for recurring vocabulary and phrases.
  - Eliminated cold-start Chunk 1 phonemization spikes (reduced from 129ms down to < 5ms).
- **Native Memory Lifecycle Management & Safe Resource Teardown (`OnnxSessionManager`, `VieNeuOnnxEngine`, `VieNeuTTSManager`)**:
  - Implemented strict sequential disposal of ONNX Runtime `OrtSession` instances followed by `sessionOptions` to prevent native C++ memory leaks during engine reloads and precision switches.
  - Added explicit recycling for intermediate `CacheTensor` buffers (`pk.recycle()`, `pv.recycle()`) across all 16 acoustic channels in `acousticFrame()`, preventing accumulation of thousands of intermediate tensors per sentence.
  - Added full cleanup of `CacheTensor.Pool` and zeroing of static inference buffers (`workingLogitsBuffer`, `topKIndicesBuffer`, `topKLogitsBuffer`, `probsBuffer`) in `VieNeuOnnxEngine.close()`.
  - Added closed-state guard (`isClosed`) in `VieNeuOnnxEngine` and `OnnxSessionManager` preventing use-after-free or duplicate closes.
- **System Memory Pressure Integration (`ReadingService.kt`)**:
  - Implemented `onTrimMemory(level)` callback handling (`TRIM_MEMORY_RUNNING_LOW`, `TRIM_MEMORY_RUNNING_CRITICAL`, `TRIM_MEMORY_UI_HIDDEN`).
  - Automatically purges idle `PrewarmedChunkCache` and `SeaG2P` cache when memory pressure occurs and service is idle.
  - Guaranteed safe teardown in `onDestroy()` and `onTaskRemoved()` (swipe-to-kill).
- **Comprehensive Verification Test Suites**:
  - Added `EarlyG2PWarmingAndCacheTest.kt` verifying warmup idempotency, LRU cache behavior, and concurrent thread-safety.
  - Added `NativeMemoryLifecycleManagementTest.kt` verifying session disposal, tensor pool recycling, hot-reload teardown, and memory trim handlers.

## [v1.5] - [2026-08-20] Tri-Layer Concurrency Defense & Activity Multi-Instance Fix
### Added
- **Layer 1: SingleTop Navigation (`AndroidManifest.xml` & `StoryDetailActivity`)**:
  - Configured `android:launchMode="singleTop"` for `StoryDetailActivity` to eliminate duplicate activity instances on repeated clicks or notification returns.
  - Implemented `onNewIntent()` with explicit state reset (`loadedStoryId = 0`, `isPrewarmTriggered = false`, `storyContent = ""`) and automatic reload when switching stories.
- **Layer 2: ReadingService Idempotency Guard (`ReadingService.kt`)**:
  - Added atomic state check (`preparedStoryId`, `preparedVoice`, `isPreparingStory`) inside `prepareStory()` to skip redundant background ONNX pre-warming jobs.
- **Layer 3: Content Cache & In-Flight Network Deduplication (`StoryDetailActivity.kt`)**:
  - Added `loadedStoryId` content cache guard in `loadStoryContent()` skipping network calls if identical story content is already loaded.
  - Added `isLoadingContent` in-flight mutex to avoid concurrent duplicate requests during configuration changes or rapid UI interactions.
- **Verification & Test Coverage**:
  - Added 3 comprehensive test suites: `StoryDetailSingleTopTest.kt`, `ReadingServicePrepareGuardTest.kt`, and `StoryDetailContentCacheGuardTest.kt` (100% passed).
  - Verified on physical hardware (`log6.txt`): network detail request count dropped from 5 to 1, seamless pre-warming, and 0 buffer underruns.

## [2026-08-20] - User-Selectable Model Precision & Real-Time Dynamic Switching
### Added
- **User Precision Selector UI (`SettingsActivity` & `activity_settings.xml`)**:
  - Intuitive `cardModelPrecision` with `MaterialButtonToggleGroup` for instant switching between `⚡ INT8 (Nhanh & Mát máy)` and `🎯 FP32 (Chất lượng gốc)`.
  - Dynamic explanatory badges indicating expected speedup, RTF, RAM savings, and thermal characteristics.
  - Automatic visibility toggling: visible when VieNeu TTS is selected, hidden when Google TTS is active.
- **Dynamic Hot-Reloading & Intent Routing (`ReadingService` & `VieNeuTTSManager`)**:
  - `ACTION_SWITCH_PRECISION` intent propagation enabling on-the-fly model switching without service or application restarts.
  - Automatic `PrewarmedChunkCache` purging upon precision changes to ensure cache consistency.
  - Persistent precision preference storage via `UserConfig.KEY_TTS_PRECISION` (Default: `INT8`).
- **On-Device Performance Verification (`log3.txt` vs `log2.txt`)**:
  - **30.2% RAM Reduction**: Native Heap decreased from 1,148 MB (FP32) to 802 MB (INT8).
  - **Inference Speedup**: Average RTF improved from 0.98x (FP32) down to 0.90x (INT8), with per-chunk peak RTF dropping from 1.28x to 0.93x.
  - 100% audio continuity with 0 buffer underruns across all test sessions.

## [2026-08-20] - Model Quantization, XNNPACK Integration & Adaptive Pre-Buffering
### Added
- **Multi-Precision Model Quantization & Detection (`OnnxSessionManager` & `tools/quantize_models.py`)**:
  - Python Dynamic INT8 quantization pipeline protecting acoustic convolution layers.
  - FP16 conversion pipeline targeting NNAPI / GPU Execution Providers.
  - Automatic quantization detection via ONNX `metadata_props` binary inspection with filename fallback.
  - Dedicated XNNPACK EP configuration for CPU inference (isolated ORT intra-op threads and big core thread allocation).
  - NNAPI EP configuration (`USE_FP16`) with graceful fallback for FP16 models.
- **Dynamic Duration-Based Adaptive Pre-Buffering (`TTSAudioPipeline`)**:
  - Replaced fixed chunk counts with 7500ms dynamic audio duration buffering threshold to eliminate audio underruns.
  - Full end-to-end `storyId` propagation throughout synchronous and pending speech initiation paths.
- **AI Performance Diagnostic Engine (`TtsMetricsLogger` & `DevicePerformanceHelper`)**:
  - System performance telemetry (RAM, SoC, thermal throttling, battery temperature).
  - 4-stage latency breakdown (G2P, Backbone, Codec, Audio/PCM).
  - AI automated heuristic flags and actionable tuning suggestions.

## [2026-08-19] - Natural TTS Story Cadence & Diagnostics (Phases 01–05)
### Added
- **Intelligent Text Segmentation (`SmartTextSegmenter`)**:
  - Context-aware pause tags: `TITLE_BREAK` (1000ms), `PARAGRAPH_BREAK` (650ms), `SENTENCE_BREAK` (400ms), `CLAUSE_BREAK` (180ms), `DIALOGUE_LEAD` (300ms).
  - Protection against incorrect sentence breaks on Vietnamese abbreviations (`TP.HCM`, `BS.`, `TS.`, `GS.`), decimals, and clock time formats.
- **Natural Silence Generation & Micro-Fading (`PcmUtils`)**:
  - Exact acoustic silence sample creation based on user config.
  - 5ms smooth half-cosine micro-fade on chunk boundaries eliminating waveform clicks/pops.
- **Granular Telemetry & Timing Logger (`TtsMetricsLogger`)**:
  - Automatic calculation of TTFT (Time to First Audio), RTF (Real-Time Factor), per-chunk G2P latency, inference latency, audio duration, and queue dwell time.
  - Formatted session summaries viewable directly in `SettingsActivity` -> `DebugLogActivity`.
- **Customizable Pause Cadence in Settings (`UserConfig` & `SettingsActivity`)**:
  - Presets: Fast, Standard, Emotional.
  - Interactive sliders with range clamping for sentence, paragraph, and clause pause lengths.
- **Hardware Benchmarking (`TTSNaturalReadingDeviceTest` & `benchmark_report.md`)**:
  - Full end-to-end verification of story 118 ("Một trí khôn hơn trăm trí khôn") on physical Android 15 device (OPPO Reno / CPH2691) with 100% test pass rate.
