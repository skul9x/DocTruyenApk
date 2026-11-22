# Plan: TTS Latency Optimization, Prewarm Preservation & ANR Prevention (P2, P1, P3, P7)
Created: 2026-11-22
Status: ✅ Completed

## Overview
This phased implementation plan addresses the 4 critical logic, latency, and stability issues identified in `fix.md` and synthesized in `docs/BRIEF.md`:
1. **Phase 01 (P2):** Prewarm Preservation & Instant First-Chunk Playback
2. **Phase 02 (P1):** Cooperative Audio Pipeline Pause/Resume with Buffer Retention
3. **Phase 03 (P3):** Asynchronous Engine Teardown & Lifecycle ANR Elimination
4. **Phase 04 (P7):** Robust INT8 Model Quantization Detection & Adaptive Prebuffer Optimization

## Architecture & Tech Stack
- **Platform:** Android Native (Kotlin 2.0+, Min SDK 24, Target SDK 34)
- **Audio Output:** Android `AudioTrack` (MODE_STREAM, 24kHz/48kHz PCM 16-bit Mono)
- **Concurrency:** Kotlin Coroutines & Channels (`Channel<Pair<Int, ShortArray>>`, `Dispatchers.IO`, `Dispatchers.Default`, `Mutex`, `SupervisorJob`)
- **Neural Engine:** ONNX Runtime Mobile (`OrtEnvironment`, `OrtSession`, Dynamic INT8 Quantized models)

## Phases

| Phase | Key Issue | Name | Status | Progress |
|-------|-----------|------|--------|----------|
| 01 | **P2** | Prewarm Preservation & Instant First-Chunk Playback | ✅ Completed | 100% |
| 02 | **P1** | Cooperative Audio Pipeline Pause/Resume with Buffer Retention | ✅ Completed | 100% |
| 03 | **P3** | Asynchronous Engine Teardown & Lifecycle ANR Elimination | ✅ Completed | 100% |
| 04 | **P7** | Robust INT8 Model Quantization Detection & Adaptive Prebuffer Optimization | ✅ Completed | 100% |

## Guidelines & Constraints
- Keep code clean, thread-safe, and resilient.
- Do not introduce breaking changes to public APIs used by `ReadingService` or activities.
- Each phase must contain exactly one comprehensive file-based unit test suite.

