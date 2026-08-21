# Plan: Fix Runtime Performance, Memory, and Stability Issues (loi.md)

Created: 2026-08-21 14:15
Status: 🟡 In Progress

## Overview
This plan addresses critical stability, CPU contention, memory overhead, and rendering bottlenecks identified in `loi.md` across the VieNeu TTS engine, audio pipeline, telemetry logging, and Android UI layers.

## Tech Stack
- Kotlin 2.x / Android Coroutines (Structured Concurrency)
- ONNX Runtime Android (XNNPACK / CPU Execution Providers)
- Android Architecture Components (LiveData, Lifecycle)
- Android UI (RecyclerView, Spanned HTML Rendering)
- JUnit 4 / Android Unit & Logic Testing

## Phases & Verification Tests

| Phase | Name | Status | Verification Test File |
|---|---|---|---|
| 01 | Fix Cancellation Exception Handling & Optimal CPU Core Threading | ⬜ Pending | `CoreTtsEngineStabilityAndThreadingTest.kt` |
| 02 | Optimize Acoustic Engine Dummy Tensor Allocation & NPZ Stream I/O | ⬜ Pending | `AcousticEngineAndNpzOptimizationTest.kt` |
| 03 | Optimize UI Rendering, Image Spam & LiveData Garbage Collection | ⬜ Pending | `UiAndLoggingOptimizationTest.kt` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Do not create or run more than one test per phase.
- After completing each phase, run only that single test for verification.
- Stop after each phase to enable user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Start Phase 2: `/code phase-02`
- Start Phase 3: `/code phase-03`
