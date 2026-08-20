# Phase 03: Android Engine Quantization Integration & Adaptive Buffering

Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
1. Update `VieNeuOnnxEngine.kt` and `OnnxSessionManager.kt` to auto-detect model quantization type (FP32/INT8/FP16) via ONNX `metadata_props` and filename convention, then configure optimal execution provider:
   - **INT8 / FP32 models → XNNPACK EP** (primary CPU path) with correct thread isolation.
   - **FP16 models → NNAPI EP** (only when NPU/GPU hardware accelerator is available).
2. Implement dynamic duration-based **Adaptive Pre-Buffering** in `TTSAudioPipeline.kt` (ensuring at least 7,500ms of buffered audio before playback starts on devices with high RTF) to guarantee stutter-free playback.
3. Ensure end-to-end `storyId` propagation into `TtsMetricsLogger.startSession` across all synchronous and pending startup paths.

> ⚠️ **CRITICAL: FP16 on CPU EP is HARMFUL.**
> ONNX Runtime CPU EP does not have native FP16 kernels on Android.
> Loading FP16 models on CPU EP causes automatic Cast (FP16→FP32) insertion,
> making inference SLOWER than native FP32. FP16 must ONLY be used with NNAPI
> (via `NNAPI_FLAG_USE_FP16`) or GPU execution providers.

## Requirements
### Functional

#### Model Detection & Execution Provider Selection
- [x] Update `OnnxSessionManager.kt` to detect model quantization type:
  - **Primary:** Read ONNX `metadata_props` for key `"quantization"` → values: `"int8"`, `"fp16"`, or absent (FP32).
  - **Fallback:** Parse filename convention (`*_int8.onnx`, `*_fp16.onnx`, default = FP32).
- [x] Configure `SessionOptions` based on detected quantization:
  - **INT8 / FP32 → Use XNNPACK EP:**
    ```kotlin
    // XNNPACK manages its own thread pool — set ORT intra-op to 1 to avoid contention
    sessionOptions.setIntraOpNumThreads(1)
    sessionOptions.setInterOpNumThreads(1)
    sessionOptions.addConfigEntry("session.intra_op.allow_spinning", "0")
    
    // Configure XNNPACK with device-appropriate thread count
    val bigCoreCount = DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4)
    sessionOptions.addXnnpack(mapOf("intra_op_num_threads" to bigCoreCount.toString()))
    ```
  - **FP16 → Use NNAPI EP (if available):**
    ```kotlin
    sessionOptions.addNnapi(EnumSet.of(NnApiFlags.USE_FP16))
    // Fallback: if NNAPI not available or fails, log warning and load as FP32 via XNNPACK
    ```
  - Set `ExecutionMode.ORT_SEQUENTIAL` for all paths (recommended for mobile, supports memory optimizations).

#### Adaptive Pre-Buffering
- [x] Update `TTSAudioPipeline.kt` pre-buffering logic:
  - Replace static `prewarmCount = 2` chunk check with dynamic `bufferedDurationMs` threshold:
    - Minimum buffer duration target: `7500ms` (or all chunks if story is shorter).
    - If pre-warmed chunks are ingested from cache, start immediately if buffer duration >= target.
  - Keep existing starvation detection and recovery logic.

#### StoryId & Telemetry
- [x] Fix `storyId` forwarding in `VieNeuTTSManager.speakWithTitlePauseInternal` and `checkPendingSpeech` so that `storyId` is never lost or set to 0.
- [x] Integrate quantization telemetry (`FP32`, `INT8`, `FP16+NNAPI`) into `TtsMetricsLogger.startSession(..., quantization = ...)` for accurate AI Diagnostic reports.
- [x] Log which execution provider was actually used (XNNPACK vs NNAPI vs CPU) for debugging.

### Non-Functional
- [x] 100% backward compatibility: The app must seamlessly load legacy FP32 models or new INT8/FP16 models without crashing.
- [x] Zero audio underruns across test stories.
- [x] Graceful NNAPI fallback: if NNAPI initialization fails for FP16 models, fall back to XNNPACK with FP32 casting and log a performance warning.

## Implementation Steps
1. Modify `OnnxSessionManager.kt`:
   - Add `detectQuantizationType(modelPath: String): QuantizationType` method.
   - Add `enum class QuantizationType { FP32, INT8, FP16 }`.
   - Update `create()` to select XNNPACK or NNAPI EP based on detected quantization.
   - When using XNNPACK: set `setIntraOpNumThreads(1)` (ORT thread pool) and configure XNNPACK threads separately via `addXnnpack()`.
   - When using NNAPI for FP16: wrap in try-catch, fallback to XNNPACK on failure.
2. Modify `VieNeuOnnxEngine.kt`:
   - Expose detected `quantizationType` for telemetry logging.
3. Modify `TTSAudioPipeline.kt`:
   - Implement duration-based adaptive pre-buffering condition (7500ms target).
   - Forward `storyId` accurately.
4. Modify `VieNeuTTSManager.kt`:
   - Pass `pendingStoryId` cleanly during deferred speech triggering.
5. Create unit test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/QuantizedEngineAndAdaptiveBufferingTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt` - ONNX session options, quantization detection, and EP selection (XNNPACK vs NNAPI).
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` - Engine telemetry and multi-precision model handling.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` - Duration-based adaptive pre-buffering (7500ms target) and queue management.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Synchronized pendingStoryId passing.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/QuantizedEngineAndAdaptiveBufferingTest.kt` - Single test verifying quantization loading, EP selection, adaptive buffering calculations, and storyId propagation.

## Test Criteria
- Exactly one test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/QuantizedEngineAndAdaptiveBufferingTest.kt`
- Verifies that `TTSAudioPipeline` respects the minimum duration pre-buffer threshold (e.g. 7500ms) before triggering playback.
- Verifies that INT8 models trigger XNNPACK EP configuration (intra_op=1 for ORT, threads via XNNPACK options).
- Verifies that FP16 models trigger NNAPI EP, and gracefully fallback to XNNPACK if NNAPI is unavailable.
- Verifies that `storyId` is consistently delivered to `TtsMetricsLogger.currentSession`.
- Verifies session summary telemetry reflects correct quantization type, EP used, and zero starvation occurrences.
