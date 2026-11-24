# Plan: Comprehensive Audit Remediation for Critical, High & Medium Issues (AUD-001 to AUD-006)
Created: 2026-11-24
Status: 🟡 In Progress

## Overview
This phased implementation plan addresses the Critical, High, and Medium issues diagnosed during the clinical system audit (`docs/reports/audit_20261124_1741.md`), researched in `docs/BRIEF.md`, and refined with international best practices:
1. **Phase 01 (AUD-001 & AUD-005):** Immediate Cooperative Inference Cancellation & Lifecycle Latency Elimination (with thread-safe lock-guarded cancellation reset).
2. **Phase 02 (AUD-002):** Robust Quantization Detection & Defensive Prebuffer Margin Restoration.
3. **Phase 03 (AUD-003):** Static JNI Tensor Pooling & Native Memory Churn Optimization (pooling 9,600 allocations/chunk across channels 0..15).
4. **Phase 04 (AUD-004 & AUD-006):** Foreground Service Callback Leak Elimination, Scalar Npz Parsing Hardening, Text Segmentation Harmonization & 100% Passing Test Suite Restoration (305/305 tests).

## Architecture & Tech Stack
- **Platform:** Android Native (Kotlin 2.0.21, Min SDK 24, Target SDK 35, JVM Target 11)
- **Audio Output:** Android `AudioTrack` (MODE_STREAM, 48kHz PCM 16-bit Mono) on isolated `AudioDispatchers.AudioTrackDispatcher`
- **Concurrency & Control Flow:** Kotlin Coroutines (`Dispatchers.IO`, `Dispatchers.Default`, `SupervisorJob`, cooperative suspension gates)
- **Neural Engine:** Microsoft ONNX Runtime Android 1.20.0 (`OrtEnvironment`, `OrtSession`, `OnnxTensor`, `OrtSession.RunOptions`)

## Phases

| Phase | Finding ID | Name | Status | Progress |
|-------|------------|------|--------|----------|
| 01 | **AUD-001, AUD-005** | Immediate Cooperative Inference Cancellation & Latency Elimination | ✅ Completed | 100% |
| 02 | **AUD-002** | Robust Quantization Detection & Defensive Prebuffer Margin Restoration | ✅ Completed | 100% |
| 03 | **AUD-003** | Static JNI Tensor Pooling & Native Memory Churn Optimization | ✅ Completed | 100% |
| 04 | **AUD-004, AUD-006** | Foreground Service Callback Leak Elimination & Scalar Npz Parsing Hardening | ✅ Completed | 100% |

## Guidelines & Constraints
- Keep code clean, thread-safe, and resilient.
- Do not introduce breaking changes to public APIs used by `ReadingService` or UI activities.
- Each phase must contain exactly one comprehensive file-based unit test suite.
- Strictly adhere to official ONNX Runtime Java/Android cancellation and memory pooling guidelines.
- Achieve 100% clean unit test results across the entire project (305/305 tests passing).
