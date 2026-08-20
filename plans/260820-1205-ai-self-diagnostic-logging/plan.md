# Plan: AI-Oriented Self-Diagnostic Performance Logging System

Created: 2026-08-20 12:05
Status: 🟡 In Progress

## Overview
Implement an AI-oriented, self-diagnostic telemetry and performance logging system (`per.md`) for the offline VieNeu-TTS neural engine.
When diagnostic logs are provided to an LLM/AI (Gemini, Claude, GPT), the formatted telemetry allows the AI to immediately detect performance bottlenecks (TTFT latency, buffer underruns, CPU/thermal throttling, memory leaks, chunk size anomalies, G2P overhead) and prescribe concrete code optimizations without ambiguity.

## Key Components to Implement
1. **`DevicePerformanceHelper.kt`**: Hardware & runtime telemetry helper capturing SoC model, CPU core count, available RAM, Native Heap memory footprint, and thermal throttling status.
2. **Fine-Grained Latency Breakdown**: Separate timing instrumentation for SeaG2P Phonemization, ONNX Backbone inference, ONNX MOSS Codec decoding, Audio micro-fade/silence processing, and Queue dwell/starvation delays.
3. **AI Diagnostic Rules Engine (`TtsMetricsLogger.kt`)**: Automated threshold evaluation generating standardized `⚠️ [AI_FLAG: ...]` tags.
4. **Actionable Recommendations Matrix (`TtsMetricsLogger.kt`)**: Summary matrix generating concrete `🎯 AI ACTIONABLE RECOMMENDATIONS` based on active diagnostic flags.
5. **Formatted Diagnostic Output**: Clean, copy-pasteable `========================== 📊 AI PERFORMANCE DIAGNOSTIC ==========================` block generated upon `endSession()`.

## Phases & Verification Tests

| Phase | Name | Status | Verification Test File |
|---|---|---|---|
| 01 | Device Hardware & Telemetry Profiler (`DevicePerformanceHelper.kt`) | ⬜ Pending | `DevicePerformanceHelperTest.kt` |
| 02 | Stage-by-Stage Latency Breakdown in Engine & Pipeline | ⬜ Pending | `StageLatencyBreakdownTest.kt` |
| 03 | AI Diagnostic Flag Rules & Actionable Recommendation Matrix | ⬜ Pending | `AiDiagnosticMetricsTest.kt` |
| 04 | End-to-End Pipeline Integration & Session Diagnostic Log | ⬜ Pending | `AiSelfDiagnosticIntegrationTest.kt` |

## Verification Strategy
- Exactly one comprehensive file-based unit test per phase.
- Only run that single unit test after implementing each phase.
- Run tests via `./gradlew testDebugUnitTest --tests "<TestClass>"`.
