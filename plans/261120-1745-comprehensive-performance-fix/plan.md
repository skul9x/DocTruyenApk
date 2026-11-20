# Plan: Comprehensive Performance & Memory Optimization

**Created:** 2026-11-20 17:45 (GMT+7)  
**Status:** ⏳ Planned  

## Overview
Address and resolve all critical performance bottlenecks, memory leaks, thread contention issues, asset bloat, and build misconfigurations identified in the system performance audit of DocTruyen & VieNeu-TTS. Transform the on-device inference and streaming playback pipeline into an industry-grade, zero-copy, memory-bounded, and stutter-free mobile TTS system.

## Architectural Objectives
1. **Zero-Copy KV Cache & Native Tensor Lifecycle Management**: Eliminate native tensor churn and the +314MB native heap ballooning during autoregressive decoding by retaining native tensors across steps and removing explicit `System.gc()` calls.
2. **Bounded Audio Streaming with Asynchronous Backpressure**: Prevent Java heap exhaustion during long audiobook playback by introducing a bounded Coroutine Channel and isolating blocking hardware AudioTrack writes onto dedicated non-blocking dispatchers.
3. **Asset Cleansing & Memory-Efficient Binary Ingestion**: Strip 85MB of unused AI assets, eliminate the transient 250MB heap spike in `NpzReader`, and activate release-mode R8/ProGuard optimizations and ABI packaging splits.
4. **Execution Provider Tuning & Telemetry Precision**: Correct XNNPACK multi-threading fallback, optimize hot matrix-vector computations, clean up sentence-level G2P cache overhead, and enforce proper release network logging.

## Phases

| Phase | Title | Scope | Test File |
|---|---|---|---|
| **01** | Native Tensor Lifecycle & Zero-Copy KV Cache Reuse | ONNX decode step, acoustic step tensor retention, zero `System.gc()` | `NativeTensorReuseAndZeroGcTest.kt` |
| **02** | Bounded Audio Channel & Playback Concurrency | Coroutine Channel backpressure, AudioTrack thread isolation, 0ms event-driven dispatch | `BoundedAudioChannelPipelineTest.kt` |
| **03** | Asset Cleansing, Memory-Efficient NPZ & Build Optimization | Delete dead models, flat binary NPZ parsing, R8 minification, ABI packaging | `AssetCleansingAndBuildOptimizationTest.kt` |
| **04** | Runtime Threading, Execution Providers & Engine Tuning | XNNPACK thread fix, INT8 fallback alignment, network logging, hot loop efficiency | `RuntimeOptimizationAndPrecisionVerificationTest.kt` (✅ Completed) |

## Plan Directory
- Location: `plans/261120-1745-comprehensive-performance-fix/`

