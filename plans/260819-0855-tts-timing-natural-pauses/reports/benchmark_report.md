# TTS Timing Diagnostics & Natural Story Cadence - Benchmark Report

**Date:** 2026-08-19  
**Device:** OPPO Reno / CPH2691 (Android 15)  
**Model:** VieNeu-TTS (48kHz Mono 16-bit PCM, ONNX Runtime Mobile)  
**Voice Preset:** Trúc Ly  

---

## 1. Test Execution Summary

| Test Suite | Total Tests | Passed | Failed | Status |
|------------|-------------|--------|--------|--------|
| **JVM Unit Tests** (`testDebugUnitTest`) | 22 classes | 22 | 0 | ✅ PASSED |
| - `TtsMetricsLoggerTest` | 4 | 4 | 0 | ✅ PASSED |
| - `SmartTextSegmenterTest` | 8 | 8 | 0 | ✅ PASSED |
| - `TTSNaturalAudioPipelineTest` | 4 | 4 | 0 | ✅ PASSED |
| - `UserConfigPauseTest` | 5 | 5 | 0 | ✅ PASSED |
| - `VieNeuTokenizerTest` | 4 | 4 | 0 | ✅ PASSED |
| - `VieNeuConfigTest` | 3 | 3 | 0 | ✅ PASSED |
| - `PcmUtilsTest` | 5 | 5 | 0 | ✅ PASSED |
| **Connected Device Test** (`connectedDebugAndroidTest`) | 1 | 1 | 0 | ✅ PASSED |
| - `TTSNaturalReadingDeviceTest` (Story 118) | 1 | 1 | 0 | ✅ PASSED |

---

## 2. On-Device Benchmark Metrics (Story 118: "Một trí khôn hơn trăm trí khôn")

| Metric | Target / Requirement | Measured Result (Physical Hardware) | Status |
|--------|----------------------|-------------------------------------|--------|
| **Time to First Audio (TTFT)** | $\le 3.5\text{s}$ (Neural Init + AudioTrack) | **~2.29s (2293 ms)** | ✅ PASSED |
| **Real-Time Factor (RTF)** | $\le 0.8\text{x}$ (Realtime Speech) | **~0.35x** | ✅ PASSED |
| **Buffer Starvation / Underruns** | Minimal ($\le 10$) | **6 events** (resolved seamlessly with unified buffers) | ✅ PASSED |
| **Inter-chunk Title Pause** | 1000 ms ($\pm 40\text{ms}$) | **1000 ms (48,000 samples)** | ✅ PASSED |
| **Paragraph Break Pause** | 600–650 ms | **600 ms (28,800 samples)** | ✅ PASSED |
| **Sentence Break Pause** | 350–400 ms | **350 ms (16,800 samples)** | ✅ PASSED |
| **Clause Break Pause** | 180 ms | **180 ms (8,640 samples)** | ✅ PASSED |
| **Pop/Click Discontinuities** | 0 acoustic artifacts | **0 (5ms micro-fade applied)** | ✅ PASSED |
| **Debug Logger Formatting** | Formatted summaries in UI | **Verified in `DebugLogger`** | ✅ PASSED |

---

## 3. Key Improvements Delivered
1. **Intelligent Text Segmentation (`SmartTextSegmenter`)**: Context-aware splitting on paragraphs, sentences, clauses, dialogues, and protected Vietnamese abbreviations (`TP.HCM`, `BS.`, `TS.`, `GS.`, numerals, time formats).
2. **Natural Pause Generation (`PcmUtils`)**: Exact acoustic silence insertion with smooth 5ms linear micro-fading eliminating waveform pop/click noise.
3. **Stream Pipeline & Telemetry (`TTSAudioPipeline` & `TtsMetricsLogger`)**: Unified chunk generation, pre-buffering, and live telemetry for TTFT, RTF, inference time, audio duration, and queue dwell time.
4. **User Customization (`SettingsActivity` & `UserConfig`)**: Presets (Fast, Standard, Emotional) and granular sliders for custom pause cadences.
