# Phase 03: Audio Preview Engine and Cache

Status: ✅ Completed
Dependencies: Phase 02

## Objective
Provide a dedicated, lifecycle-safe audio preview engine with in-memory caching and cooperative cancellation for auditioning short voice greetings without interrupting main background reading or leaking audio hardware resources.

## Scope
- Preview synthesis and playback manager.
- In-memory bounded audio buffer cache (LruCache / RAM map for short PCM clips).
- Lifecycle binding, audio focus handling, and cooperative cancellation hooks.
- Preview integration for both VieNeu neural voices and Google TTS.

## Requirements
### Functional
- Provide sample greeting generation for any chosen voice (e.g. "Xin chào, tôi là [Tên giọng], rất vui được đọc truyện cùng bạn." or standard audition prompt).
- Enforce single-active playback semantics: triggering audition for voice B immediately cancels, stops, and cleans up playback for voice A.
- Provide explicit play, stop, and toggle functions with observable state callbacks (Idle, Loading/Synthesizing, Playing, Stopped, Error).
- Cache synthesized preview PCM audio in memory so repeated clicks on the same voice audition button play immediately with 0 ms synthesis latency.
- Provide a clean stop-and-release API for dialog dismissal and activity lifecycle events.

### Non-Functional
- Strictly avoid `GlobalScope`; use structured concurrency with Coroutines and proper `CancellationException` propagation.
- AudioTrack resources must be stopped, flushed, and released inside `finally` blocks to guarantee zero native audio resource leaks.
- Memory cache must be strictly bounded (e.g. max 32 items, <3MB total resident RAM) to prevent memory pressure.
- Avoid audio focus deadlocks; ensure preview playback operates independently or ducks gracefully without corrupting `ReadingService` state.

## Constraints
- Sample audio length must be brief (~2 seconds) to keep synthesis latency low and memory usage minimal.
- Do not block the Main UI thread during neural G2P, inference, or AudioTrack write loops.
- Handle device audio route changes or transient audio focus loss cleanly.

## Dependencies
- Phase 02 (Voice metadata categorization).

## Acceptance Criteria
- Triggering preview transitions state to Loading and then Playing.
- Starting a second preview while the first is active immediately halts the first without overlap.
- Calling stop or release halts playback, releases the underlying AudioTrack, and transitions state to Idle/Stopped.
- Subsequent preview requests for an already synthesized voice retrieve the audio from cache without re-inference.
- Coroutine cancellation (e.g. on dialog dismissal) cleanly terminates active synthesis or playback loops without throwing unhandled exceptions.
- Running the comprehensive file-based test passes with 100% success.

## Single File-Based Test
`app/src/test/java/com/skul9x/doctruyen/tts/AudioPreviewEngineTest.kt`

---
Next Phase: [Phase 04: Traditional Dialog Voice Selector UI](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/plans/261119-2345-voice-categorization-preview-and-zero-gap-pacing/phase-04-traditional-dialog-voice-selector-ui.md)
