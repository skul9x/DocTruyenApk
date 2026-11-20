# Plan: Full Performance Remediation & Architectural Optimization

Created: 2026-11-20  
Status: 🟢 Completed  
Goal: Remediate all 10 verified performance bottlenecks (PERF-001 through PERF-010) identified in the v1.17 performance audit report to achieve production-grade responsiveness, zero starvation audio streaming, low memory consumption, and streamlined build packaging.

---

## Tech Stack & Architecture
- **Language & Runtime:** Kotlin 2.0 (Target JVM 11), Android Jetpack, Kotlin Coroutines & Channels.
- **On-Device Neural Engine:** ONNX Runtime Android 1.20.0 with XNNPACK Execution Provider.
- **Phonemizer:** `sea-g2p` (Rust NDK compiled library `libsea_g2p_android.so` with memory-mapped `sea_g2p.bin`).
- **Audio Output:** `android.media.AudioTrack` in `MODE_STREAM` (48kHz 16-bit Mono PCM) on dedicated priority `-19` thread.

---

## Phases Overview

| Phase | Name | Target Issues | Status | Progress |
|---|---|---|---|---|
| **01** | [Inference Cancellation & Audio Pre-buffering Stability](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/plans/261120-0915-full-performance-remediation/phase-01-cancellation-and-prebuffering.md) | PERF-001, PERF-005 | ✅ Completed | 100% |
| **02** | [Storage & Memory Footprint Optimization](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/plans/261120-0915-full-performance-remediation/phase-02-storage-and-memory-optimization.md) | PERF-003, PERF-004 | ✅ Completed | 100% |
| **03** | [Acoustic Threading & Matrix Acceleration](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/plans/261120-0915-full-performance-remediation/phase-03-acoustic-threading-and-matrix-acceleration.md) | PERF-002, PERF-007 | ✅ Completed | 100% |
| **04** | [Lifecycle, UI, G2P Caching & Packaging Hygiene](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/plans/261120-0915-full-performance-remediation/phase-04-lifecycle-ui-and-packaging-hygiene.md) | PERF-006, PERF-008, PERF-009, PERF-010 | ✅ Completed | 100% |

---

## Execution Guidelines
- Each phase is completely defined as a separate English markdown file specifying objectives, scope, requirements, constraints, dependencies, acceptance criteria, and exactly one comprehensive file-based test.
- The plan focuses on **what to accomplish**, leaving implementation decisions to the coding agent following project architecture and best practices.
- Do not implement code or run tests during the planning stage.

