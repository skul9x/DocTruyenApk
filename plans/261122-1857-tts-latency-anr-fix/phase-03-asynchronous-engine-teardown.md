# Phase 03: Asynchronous Engine Teardown & Lifecycle ANR Elimination (P3)
Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
Eliminate Main-Thread blocking and Application Not Responding (ANR) risks (P3 in `fix.md`) caused by synchronous engine closing (`engine.close()`), precision switching, and service lifecycle destruction holding the UI thread while waiting for active 3–7 second inference locks.

## Scope
1. Refactor `VieNeuOnnxEngine.kt` to introduce cooperative cancellation flags (`@Volatile var cancelRequested`) across decode frames and session runs.
2. Decouple `engine.close()`, `shutdown()`, and `switchPrecision()` from the Android Main thread, migrating all heavy teardowns to background coroutines (`Dispatchers.IO`).
3. Remove manual synchronous `System.gc()` calls on the Main thread in `ReadingService.kt` and `VieNeuTTSManager.kt`.

## Requirements
### Functional
- When `ReadingService.onDestroy()`, `onTaskRemoved()`, or `switchPrecision()` is invoked:
  - Main thread returns immediately without blocking.
  - Active synthesis jobs are signaled to terminate immediately via cooperative cancellation flag.
  - Resource cleanup (`OrtSession.close()`, native buffer clearing) executes safely on `Dispatchers.IO`.
- Precision switching and engine reloads transition smoothly without freezing UI or triggering Android OS ANR watchdog dialogs.

### Non-Functional & Performance
- Main thread block duration during service destruction or precision switch must be < 10ms.
- Elimination of all `SIGSEGV` native crashes by ensuring active `OrtSession.run()` terminates before `OrtSession.close()` is invoked.
- Removal of forced `System.gc()` pauses.

## How to Implement
1. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`:
   - Introduce `@Volatile private var cancelRequested = false`.
   - In `infer()`, `prefill()`, and inside the frame loop of `synthesizePass()`, check `if (cancelRequested || !Thread.currentThread().isInterrupted)` to break execution immediately on cancel.
   - Refactor `close()`:
     - Flag `cancelRequested = true`.
     - Acquire lock safely or perform asynchronous teardown without blocking the caller thread.
2. In `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`:
   - Make `shutdown()` suspendable or launch teardown onto `scope.launch(Dispatchers.IO)`.
   - Remove blocking `System.gc()` calls.
3. In `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt`:
   - In `onDestroy()`, `onTaskRemoved()`, and `switchPrecision()`:
     - Do not block the Main thread waiting for engine locks.
     - Dispatch shutdown to background scope (`serviceScope.launch(Dispatchers.IO)`).
     - Remove `System.gc()` calls.

## Files to Create/Modify
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`
- Modify: `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt`

## Dependencies
- Phase 01, Phase 02.

## Constraints
- Teardown on background threads must not leak native ONNX pointers or cause race conditions if a new engine is quickly re-initialized.
- Android Lifecycle callbacks (`onDestroy`) must complete within system deadlines.

## Acceptance Criteria
- [x] `engine.close()` and `ReadingService.onDestroy()` execute off the Main thread without blocking UI events.
- [x] Active inference loop exits early when cancellation is requested.
- [x] No `System.gc()` triggered on Main thread.
- [x] Passes `AsynchronousEngineTeardownTest`.

## Test Implementation
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/AsynchronousEngineTeardownTest.kt`
- Exactly one comprehensive file-based test suite verifying:
  1. `close()` invoked while `infer()` is holding engine lock does not freeze caller thread.
  2. Cancellation flag stops inference loop prematurely and releases resources.
  3. Safe concurrent teardown and re-initialization sequence without native deadlock.

