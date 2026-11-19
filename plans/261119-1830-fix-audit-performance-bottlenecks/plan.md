# Plan: Fix All Performance Bottlenecks from System Audit (audit_per.md)

Created: 2026-11-19 18:30
Status: ✅ Completed

## Overview
Comprehensive remediation of all 13 performance bottlenecks identified in `audit_per.md` (PERF-001 through PERF-013), applying production-grade on-device neural TTS inference patterns, zero-copy native tensor lifecycle management, non-blocking real-time audio pipeline concurrency, JVM/Kotlin ILP vectorization, and build/storage hygiene:

1. **Phase 01 (Hygiene, Dead Assets & UI/Network Cleanup):** Add `android:largeHeap="true"`, remove 85MB of unused assets (`denoiser.onnx` and `moss_audio_tokenizer_encode.*`), pre-compile static Regexes and offload heavy HTML parsing off Main UI thread, replace XML animations, and fix Retrofit reflection/header logging.
2. **Phase 02 (Zero-Copy KV-Cache Ping-Pong & Forced GC Elimination):** Eliminate 3.2GB float allocation churn per sentence by chaining native `OrtSession.Result` and `OnnxTensor` instances directly across autoregressive decode steps and acoustic channels; completely remove Stop-The-World `System.gc()` calls.
3. **Phase 03 (Flat Embeddings & NpzReader Memory Optimization):** Replace the 3D array of 16,384 `FloatArray` objects in `NpzReader` with flat contiguous buffer indexing, saving >100MB resident heap and eliminating triple-copy weight duplication.
4. **Phase 04 (Dedicated Audio Playback Thread & Coroutine Channel Backpressure):** Move blocking `AudioTrack.write()` off `Dispatchers.Default` onto a dedicated single-threaded audio worker, and replace unbounded queue polling `delay(20)` with Kotlin Coroutines `Channel<PlaybackChunk>` with backpressure.
5. **Phase 05 (Matrix Multiplication Acceleration & BabbleGuard Acoustic Trimming):** Accelerate 2.5 billion FLOPs acoustic projection via 4x/8x unrolled multi-accumulator ILP, and trim trailing babble bursts acoustically in PCM domain without re-running 12-layer AR synthesis loop up to 2 times.
6. **Phase 06 (Asset Streaming Buffer & Release R8 Minification):** Upgrade asset extraction stream buffer from 8KB to 64KB/128KB with version-aware update detection, enable `isMinifyEnabled = true` with full Proguard configuration, and verify native `.so` stripping.

## Tech Stack
- Kotlin 2.0 / Android SDK (minSdk 24, targetSdk 35)
- ONNX Runtime Android (v1.20.0) with XNNPACK FP32/INT8 execution provider
- MOSS Audio Tokenizer Nano Codec / 48kHz 16-bit PCM AudioTrack streaming
- sea-g2p Phonemizer (Rust NDK JNI)
- Android Jetpack, Coroutines & Flow (Channels), Retrofit 2.9, OkHttp 4.12
- JUnit 4 / Mockito / Android Unit Tests

## Phases

| Phase | Name | Status | Single File-Based Test | Progress |
|---|---|---|---|---|
| 01 | Hygiene, Dead Assets, and UI/Network Cleanup | ✅ Completed | `HygieneAndAssetCleanupTest.kt` | 100% |
| 02 | Zero-Copy KV-Cache Ping-Pong & Forced GC Elimination | ✅ Completed | `ZeroCopyKVCacheAndEngineTest.kt` | 100% |
| 03 | Flat Embeddings & NpzReader Memory Optimization | ✅ Completed | `NpzReaderFlatEmbeddingsTest.kt` | 100% |
| 04 | Dedicated Audio Thread & Channel Backpressure | ✅ Completed | `AudioPipelineChannelConcurrencyTest.kt` | 100% |
| 05 | Matrix Unrolling (ILP) & Acoustic Tail Trimming | ✅ Completed | `MatrixOptimizationAndAcousticTrimmingTest.kt` | 100% |
| 06 | Asset Streaming Buffer & Release R8 Minification | ✅ Completed | `AssetExtractionAndProguardRulesTest.kt` | 100% |

## Strict Verification Protocol
- Exactly **one comprehensive file-based test** is assigned per phase.
- Do NOT create or run more than one test per phase.
- After completing implementation for each phase, run ONLY that single test for verification.
- Stop immediately after running the test to await user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`

