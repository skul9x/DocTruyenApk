# Plan: TTS Timing Diagnostics & Natural Story Cadence
Created: 2026-08-19 08:55
Status: 🟡 In Progress

## Overview
This plan implements granular TTS timing telemetry in the debug settings and optimizes Vietnamese speech cadence for offline VieNeu-TTS. Currently, reading story text produces unnatural pauses—either abrupt transitions without punctuation pauses, or unexpected stuttering pauses caused by synthesis buffer underrun. This feature introduces context-aware text segmentation (handling paragraphs, sentences, clauses, dialogue dashes, and abbreviations), configurable acoustic silence insertion with smooth cross-fading, pre-buffering to eliminate playback starvation, and detailed timing metrics (TTFT, RTF, synthesis time, playback duration, pause accuracy) visible in the Settings Debug Log.

## Tech Stack & Architecture
- **Inference & Audio Engine**: Kotlin coroutines, ONNX Runtime Mobile, Android `AudioTrack` (48kHz Mono 16-bit PCM).
- **Text & Linguistics Processing**: `sea-g2p` phonemizer via Rust JNI, regex & AST-based smart Vietnamese sentence/paragraph segmenter.
- **Diagnostics & UI**: `DebugLogger`, `DebugLogActivity`, `SettingsActivity`, `UserConfig` (SharedPreferences).
- **Testing**: JUnit 4, Kotlin Coroutines Test, AndroidX Instrumented Tests.

## Phases

| Phase | Name | Status | Progress |
|-------|------|--------|----------|
| 01 | TTS Timing Metrics & Diagnostic Logging System | ✅ Completed | 100% |
| 02 | Context-Aware Smart Text Segmentation & Pause Rules | ✅ Completed | 100% |
| 03 | Natural Pause Audio Generation & Anti-Starvation Pipeline | ✅ Completed | 100% |
| 04 | Settings Configuration & Pause Customization | ✅ Completed | 100% |
| 05 | Verification, Benchmarking & End-to-End Testing | ✅ Completed | 100% |

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
