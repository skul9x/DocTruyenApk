# Plan: Early G2P Dictionary Warming & Native Memory Management

Created: 2026-08-21 09:55
Status: 🟡 In Progress

## Overview
Based on AI diagnostic telemetry analysis across `log1.txt` to `log6.txt`, this plan addresses two critical bottlenecks:
1. **Cold-start G2P Overhead (`G2P_OVERHEAD` > 50ms on Chunk 1)**: First-chunk phonemization latency spikes up to 129ms due to uninitialized regex engines, deferred dictionary loading (`sea_g2p.bin`), and lack of an in-memory phoneme cache.
2. **Native Memory Accumulation & Potential Leaks (`MEMORY_LEAK_SUSPECTED` +335MB, Heap up to 1.62GB)**: Native heap balloons during initial engine startup and accumulates when switching precision (INT8 <-> FP32) due to uncollected native ONNX sessions, unreleased `CacheTensor.Pool` buffers, and lack of systematic memory trimming during idle states.

## Tech Stack
- Kotlin / Android Coroutines
- Rust / JNI (`libsea_g2p_android.so`)
- ONNX Runtime Java (`ai.onnxruntime:onnxruntime-android`)
- JUnit 4 / Mockito / Android Tests

## Phases & Verification Tests

| Phase | Name | Status | Verification Test File |
|---|---|---|---|
| 01 | Early G2P Dictionary Pre-warming & Phoneme LRU Cache | ⬜ Pending | `EarlyG2PWarmingAndCacheTest.kt` |
| 02 | Native Memory Lifecycle Management & Safe Resource Teardown | ⬜ Pending | `NativeMemoryLifecycleManagementTest.kt` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Only run that single test after completing each phase.
- Stop after each phase to enable user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
