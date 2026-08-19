# Plan: TTS Instant & Gapless Playback (Pre-buffering + Pre-warming)
Created: 2026-08-19 11:05 (GMT+7)
Status: 🟡 In Progress

## Overview
Eliminate initial audio stutter, buffer starvation, and delays during TTS playback by combining:
1. **Adaptive Lookahead Pre-buffering (Approach 1):** Ensuring at least 2 chunks or $\ge 6000\text{ms}$ of audio buffer is accumulated before releasing the stream to `AudioTrack` on fresh playback sessions.
2. **Background Pre-warming (Approach 2):** Proactively pre-synthesizing Chunks 0 & 1 into an in-memory queue when the story content is loaded into the viewer UI before the user taps "Play", achieving near-zero TTFT ($\le 50\text{ms}$) and 100% gapless narration.

## Tech Stack & Architecture
- **Language:** Kotlin (Coroutines, StateFlow / Channel, Supervised Scope)
- **Audio Engine:** `TTSAudioPipeline`, `AudioPlayer` (`AudioTrack` MODE_STREAM, 24kHz 16-bit PCM)
- **Neural Engine:** `VieNeuOnnxEngine` + `OnnxSessionManager` (4-thread ONNX Runtime Mobile)
- **Segmentation:** `SmartTextSegmenter` (Vietnamese NLP, boundary & pause tagging)

## Phases Breakdown

| Phase | Name | Status | Progress | Key Deliverable |
|:---|:---|:---|:---|:---|
| [Phase 01](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-01-adaptive-lookahead-prebuffering.md) | Adaptive Lookahead Pre-buffering | ⬜ Pending | 0% | Dynamic 2-chunk/6s pre-buffer threshold in `TTSAudioPipeline` |
| [Phase 02](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-02-background-prewarming-engine.md) | Background Pre-warming Engine | ⬜ Pending | 0% | `prepareStory()` in `VieNeuTTSManager` & `ReadingService` with memory cache |
| [Phase 03](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-03-lifecycle-and-ui-orchestration.md) | UI & Lifecycle Orchestration | ⬜ Pending | 0% | Hook `StoryDetailActivity` on-load pre-warm & instant play trigger |
| [Phase 04](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-1105-tts-prebuffering-prewarming/phase-04-verification-benchmarking.md) | Comprehensive Verification & Benchmarks | ⬜ Pending | 0% | Automated unit tests & on-device buffer underrun telemetry |

## Success Criteria
- **Initial Buffer Underruns:** 0 occurrences (100% gapless from Chunk 1 through End).
- **TTFT on Pre-warmed Playback:** $\le 50\text{ms}$ (instant audio output).
- **Fallback TTFT (Cold Playback):** Controlled startup with zero subsequent starvation.
- **Memory Overhead:** Memory cache capped at $\le 15\text{MB}$ PCM buffer.
