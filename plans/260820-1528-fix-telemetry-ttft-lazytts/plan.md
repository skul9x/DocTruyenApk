# Plan: Telemetry TotalChars Fix, Low TTFT Adaptive Pre-buffering, and Lazy Google TTS Initialization
Created: 2026-08-20 15:28
Status: 🟡 In Progress

## Overview
This plan addresses 3 core areas discovered in log analysis:
1. **Telemetry & AI Diagnostic Accuracy**: Fix `TtsMetricsLogger.kt` where `totalChars` was locked to Chunk 1's char count (19 chars instead of 1,389 chars), causing distorted throughput and story metric logging.
2. **TTFT Optimization for INT8 / Fast SoCs**: Adaptively lower initial pre-buffering duration threshold in `TTSAudioPipeline.kt` (from 7,500ms down to 2,000ms–2,500ms or 1–2 chunks for INT8/Flagship CPUs) to reduce Time-To-First-Audio (TTFT) from 9.17s to < 2.5s.
3. **Lazy Initialization of Google TTS in Settings**: Eliminate unwanted background IPC binding of `GoogleTTSManager` in `SettingsActivity.kt` when the active engine is VieNeu Offline TTS.

---

## Phases

| Phase | Name | Description | Status | Verification Test |
|---|---|---|---|---|
| 01 | Fix TtsMetricsLogger TotalChars & Telemetry | Accumulate dynamic total characters across all synthesized chunks and fix throughput math in AI Diagnostic reports | ⬜ Pending | `MetricsLoggerTotalCharsAndDiagnosticTest.kt` |
| 02 | Low TTFT Adaptive Pre-Buffering | Dynamic duration and chunk thresholds for INT8 & fast SoCs to reduce TTFT to < 2.5s | ⬜ Pending | `AdaptivePreBufferLowTtftTest.kt` |
| 03 | Lazy Google TTS Initialization in Settings | Defer `GoogleTTSManager` binding in `SettingsActivity` until Google TTS is explicitly chosen or voice picker is opened | ⬜ Pending | `SettingsLazyGoogleTtsTest.kt` |

---

## Execution Rules
- All phase documents are written in English.
- Exactly one comprehensive file-based test per phase.
- Only run that single test after completing each phase, then stop for review.
