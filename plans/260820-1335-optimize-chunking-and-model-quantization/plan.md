# Plan: Text Chunking Optimization & Model Quantization (FP16 / INT8)

Created: 2026-08-20 13:35
Status: 🟡 In Progress

## Overview
Optimize the TTS pipeline and neural inference engine for the offline Vietnamese TTS system (VieNeu-TTS) to drastically reduce Real-Time Factor (RTF) from ~0.92x down towards <0.30x, eliminate audio buffer starvation/stuttering, resolve UI state flickering during startup, and provide automated tooling for Dynamic INT8 model quantization (primary CPU path) and optional FP16 conversion (only effective with NNAPI/GPU execution providers, NOT CPU EP).

## Key Objectives
1. **Intelligent Text Chunking (`SmartTextSegmenter.kt`)**: Reduce max chunk length from 180 chars to ~75 chars with natural Vietnamese punctuation/clause boundary detection, ensuring small, predictable inference batches (~1.5s - 2.5s) that never exhaust the audio playback buffer.
2. **UI Startup State Smoothness (`VieNeuTTSManager.kt`)**: Eliminate race-condition state flickering (`PLAYING` -> `READY` -> `PLAYING`) when play is pressed during engine initialization.
3. **Automated Quantization Pipeline (`tools/quantize_models.py`)**: Provide a standalone Python pipeline supporting Dynamic INT8 quantization (primary) and optional FP16 conversion with external data handling (`.data` files) and acoustic-safe operator filtering via `op_types_to_quantize=['MatMul', 'Gemm']`.
4. **Android Engine & Adaptive Buffering Integration (`VieNeuOnnxEngine.kt`, `TTSAudioPipeline.kt`)**: Integrate quantized model support (INT8 for CPU/XNNPACK, FP16 only via NNAPI when NPU is available), configure optimal XNNPACK CPU acceleration with correct thread isolation, implement dynamic duration-based pre-buffering (>=7500ms target), and ensure complete `storyId` telemetry propagation.

## Phases & Verification Tests

| Phase | Name | Status | Verification Test File |
|---|---|---|---|
| 01 | Intelligent Text Chunking Optimization & UI State Fix | ⬜ Pending | `OptimizedTextChunkingAndStateTest.kt` |
| 02 | Automated ONNX Model Quantization Pipeline (FP16 / INT8) | ⬜ Pending | `test_quantization_pipeline.py` |
| 03 | Android Engine Quantization Integration & Adaptive Buffering | ⬜ Pending | `QuantizedEngineAndAdaptiveBufferingTest.kt` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Only run that single test after completing each phase.
- Stop after each phase to enable user review.
