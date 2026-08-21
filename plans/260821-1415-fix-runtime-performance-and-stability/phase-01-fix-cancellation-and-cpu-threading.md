# Phase 01: Fix Cancellation Exception Handling & Optimal CPU Core Threading
Status: ✅ Completed
Dependencies: None

## Objective
Fix two critical bugs in the VieNeu TTS engine and audio pipeline:
1. Prevent normal coroutine cancellation (`CancellationException`) during navigation/exit from triggering an unintended fallback to Android TTS.
2. Eliminate CPU thread contention and high RTF (> 1.0x) by assigning optimal thread counts matching big/performance cores (`coerceIn(2, 4)`) instead of blindly requesting all hardware cores.

## Requirements

### Functional Requirements
- [x] **Coroutine Cancellation Handling**: In `VieNeuTTSManager.initManager()`, explicitly catch and rethrow `CancellationException` (or ignore fallback invocation) so that back navigation / job cancellation terminates cleanly without setting `isUsingFallback = true` or initializing `AndroidTTSManager`.
- [x] **Real Error Fallback Preservation**: Genuine initialization failures (e.g., missing model files, corrupted ONNX binaries, out of memory) must continue to trigger `tryFallbackAndroidTTS()`.
- [x] **Optimal Thread Allocation in Audio Pipeline**: In `TTSAudioPipeline.kt` (line 128 and parameter default line 591), use `DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4)` for `engineThreads` rather than `Runtime.getRuntime().availableProcessors().coerceAtLeast(1)`.
- [x] **Consistent Thread Fallback in ONNX Session Manager**: In `OnnxSessionManager.kt` CPU fallback paths (lines 148 and 164), use `effectiveThreads` / `DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4)` to prevent spinning threads on LITTLE cores.

### Non-Functional Requirements
- [x] Preserves structured concurrency in coroutine lifecycles.
- [x] Prevents thread thrashing, context switching overhead, and thermal throttling on octa-core big.LITTLE architectures (e.g., Snapdragon 8 Gen 2/3, Dimensity 9000+).

## Implementation Steps
1. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`:
   - In `initManager()`, update `try ... catch (e: Throwable)` block:
     ```kotlin
     } catch (e: kotlinx.coroutines.CancellationException) {
         DebugLogger.log(TAG, "VieNeu initialization cancelled normally: ${e.message}", LogType.INFO)
         throw e
     } catch (e: Throwable) {
         DebugLogger.logError(TAG, "❌ Failed to initialize VieNeu engine: ${e.message}. Attempting Android TTS fallback...", e)
         Log.e(TAG, "Failed to initialize VieNeuOnnxEngine", e)
         tryFallbackAndroidTTS()
     }
     ```
2. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`:
   - In `start()` at line 128, replace:
     ```kotlin
     val engineThreads = DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4)
     ```
   - In line 591, update default parameter:
     ```kotlin
     cpuCores: Int = DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4),
     ```
3. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt`:
   - In lines 148 and 164, ensure CPU fallback intra-op threads use `effectiveThreads`:
     ```kotlin
     val intra = effectiveThreads
     sessionOptions.setIntraOpNumThreads(intra)
     ```

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` — Add CancellationException guard in `initManager()`.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` — Update `engineThreads` calculation.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt` — Update CPU fallback thread count.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/CoreTtsEngineStabilityAndThreadingTest.kt` — [NEW] Single comprehensive test file.

## Test Criteria (Single Test File)
**File:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/CoreTtsEngineStabilityAndThreadingTest.kt`
- [x] **Test 1**: Verify `CancellationException` during coroutine execution propagates without triggering fallback or altering fallback state.
- [x] **Test 2**: Verify non-cancellation exception (`RuntimeException`, `IOException`) triggers fallback and error logging as expected.
- [x] **Test 3**: Verify `engineThreads` computation produces bounded core count in `[2, 4]` on multi-core systems (e.g., 8-core CPU returns 4, not 8).
- [x] **Test 4**: Verify `OnnxSessionManager.configureSessionOptions()` allocates optimal thread counts for both XNNPACK and CPU fallback configurations.

## Verification Command
```bash
./gradlew test --tests "com.skul9x.doctruyen.tts.vieneu.CoreTtsEngineStabilityAndThreadingTest"
```

---
Next Phase: [phase-02-optimize-acoustic-engine-and-npz-io.md](./phase-02-optimize-acoustic-engine-and-npz-io.md)
