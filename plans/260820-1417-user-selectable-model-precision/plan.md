# Plan: User-Selectable Model Precision in App Settings (INT8 vs FP32)

Created: 2026-08-20 14:17
Status: 🟡 In Progress

## Overview
Provide users with the ability to choose their preferred ONNX neural model precision (**Dynamic INT8** vs **Original FP32**) directly within the app's Settings screen (`SettingsActivity`). 
- **Dynamic INT8 (Recommended / Default)**: 2x faster inference, lower RTF (~0.45x - 0.55x), lower memory footprint, and cooler device operation for smoother, stutter-free playback.
- **Original FP32**: Full studio-grade floating-point precision for high-end flagship devices or users prioritizing maximum fidelity.

The implementation is modularized into 3 structured phases with full backward compatibility, live hot-reloading in `VieNeuTTSManager` without requiring app restarts, and automated telemetry logging.

## Key Objectives
1. **Multi-Precision Asset Provisioning**: Produce quantized `backbone_int8` assets using the standalone quantization pipeline and configure asset extraction for both precision modes.
2. **Dynamic Engine Hot-Reloading**: Enable runtime switching between INT8 and FP32 sessions in `OnnxSessionManager`, `VieNeuOnnxEngine`, and `VieNeuTTSManager` with cache invalidation and thread safety.
3. **Settings UI & Configuration**: Add a modern, interactive precision selector in `activity_settings.xml` and `SettingsActivity.kt` linked to `UserConfig` and `ReadingService`.

## Phases & Verification Tests

| Phase | Name | Status | Verification Test File |
|---|---|---|---|
| 01 | Multi-Precision Asset Provisioning & Configuration Persistence | ⬜ Pending | `PrecisionAssetAndConfigTest.kt` |
| 02 | Dynamic Engine Precision Switching & Hot-Reloading | ⬜ Pending | `DynamicPrecisionSwitchingTest.kt` |
| 03 | Settings UI Precision Selector & End-to-End Flow | ⬜ Pending | `SettingsPrecisionIntegrationTest.kt` |

## Verification Strategy
- Exactly one comprehensive file-based test per phase.
- Only run that single test after completing each phase.
- Stop after each phase to enable user review.
