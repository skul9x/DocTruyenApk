# Phase 02: Dynamic Engine Precision Switching & Hot-Reloading

Status: ✅ Completed
Dependencies: Phase 01

## Objective
1. Enable dynamic runtime switching and hot-reloading between **INT8** and **FP32** precision modes in `OnnxSessionManager.kt`, `VieNeuOnnxEngine.kt`, and `VieNeuTTSManager.kt`.
2. Ensure existing ONNX sessions, tensors, and memory allocations are cleanly released prior to instantiating new sessions.
3. Automatically invalidate and clear `PrewarmedChunkCache` upon switching precision to prevent audio cache corruption across model architectures.
4. Support precision update signals via `ReadingService.kt` to allow live changes while the app is active.

## Requirements
### Functional
- [x] Update `OnnxSessionManager.create`:
  - Add parameter `precision: String = "INT8"`.
  - Pass precision down to asset extractor and session builder.
  - Set `executionProvider` and `quantizationType` properties matching the loaded model.
- [x] Update `VieNeuOnnxEngine.create`:
  - Add parameter `precision: String = "INT8"`.
  - Forward precision parameter to `OnnxSessionManager.create`.
- [x] Add dynamic precision switching in `VieNeuTTSManager.kt`:
  - Add method `fun setPrecision(precision: String)`:
    - Atomically stops any active synthesis / playback job.
    - Closes old `VieNeuOnnxEngine` and `TTSAudioPipeline`.
    - Clears `PrewarmedChunkCache` (via `PrewarmedChunkCache.clear()`).
    - Recreates `VieNeuOnnxEngine` with the requested precision (`UserConfig.getTtsPrecision(context)`).
    - Reconnects `TTSAudioPipeline` with the new engine instance while retaining current user speed, pitch, cadence, and voice settings.
- [x] Update `ReadingService.kt`:
  - Add intent action `ACTION_SWITCH_PRECISION = "com.skul9x.doctruyen.ACTION_SWITCH_PRECISION"`.
  - Add intent extra `EXTRA_PRECISION_TYPE = "precision_type"`.
  - On receiving `ACTION_SWITCH_PRECISION`, invoke `vieNeuTTSManager.setPrecision(...)` and log telemetry change.
- [x] Update `TtsMetricsLogger.kt`:
  - Log model reload event and update active session telemetry quantization property (`INT8` vs `FP32`).

### Non-Functional
- [x] Thread safety: Engine reloading must synchronize access to the engine instance to avoid concurrency crashes if speech is triggered during a reload.
- [x] Zero memory leaks: Explicitly call `.close()` on all `OrtSession` and `OrtEnvironment` instances before creating new ones.
- [x] Fast reload time (< 1.5s).

## Implementation Steps
1. Modify `OnnxSessionManager.kt` to support `precision: String` parameter in `create()` and `createFromFiles()`.
2. Modify `VieNeuOnnxEngine.kt` to accept `precision: String` during initialization.
3. Modify `VieNeuTTSManager.kt` to implement `setPrecision(precision: String)` and `reloadEngine(precision: String)`.
4. Update `ReadingService.kt` with `ACTION_SWITCH_PRECISION` action handler.
5. Create unit test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/DynamicPrecisionSwitchingTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt` - Precision-aware session creation and cleanup.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt` - Engine creation delegation with precision.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Runtime engine reloading and cache invalidation.
- `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt` - Service intent routing for precision switches.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/DynamicPrecisionSwitchingTest.kt` - Unit test for engine hot-reloading and thread safety.

## Test Criteria
- Exactly one test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/DynamicPrecisionSwitchingTest.kt`
- Verifies switching precision from FP32 to INT8 and INT8 to FP32 updates `quantizationType` and `executionProvider`.
- Verifies `PrewarmedChunkCache` is cleared upon precision switch.
- Verifies `VieNeuTTSManager` state transitions to `READY` after reloading.
- Verifies `TtsMetricsLogger` records the new quantization type in session starts following a precision switch.
