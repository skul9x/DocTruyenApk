# Plan: Fix GoogleTTS Re-entrancy Bug & Add Pre-buffering Chunk Selector (1, 2, 3 Chunks)
Created: 2026-08-20T15:57:00+07:00
Status: 🟡 In Progress

## Overview
This plan addresses two critical enhancements:
1. **Fix Critical Google TTS Re-entrancy Bug**: During `GoogleTTSManager` constructor initialization, `notifyStateChange(INITIALIZING)` was invoking `onVoiceDisplayRefresh() -> updateActiveVoiceDisplay() -> getOrCreateGoogleTts()` before the constructor returned, triggering infinite recursive instantiations and `java.lang.StackOverflowError`.
2. **Configurable Pre-buffering Buffer Depth (1, 2, or 3 Chunks)**: Provide a segmented selector in Settings so users can customize their audio startup behavior between:
   - **1 Chunk (Ultra Fast / Low TTFT)**: ~1.8s TTFT (immediate audio playback).
   - **2 Chunks (Balanced / Recommended)**: ~3.5s TTFT (fast start + zero buffer starvation).
   - **3 Chunks (Max Stability)**: ~8.0s TTFT (maximum stability for long background listening).

## Phases

| Phase | Name | Description | Status | Single Verification Test |
|-------|------|-------------|--------|--------------------------|
| 01 | Fix GoogleTTS Re-entrancy & Callback Guard | Prevent recursive instantiation during GoogleTTSManager initialization and filter callback refreshes | ⬜ Pending | `GoogleTtsReentrancySafetyTest.kt` |
| 02 | Pre-buffering 1, 2, 3 Chunks Pipeline Config | Add UserConfig chunk preference and adapt TTSAudioPipeline dynamic threshold calculation | ⬜ Pending | `PrebufferChunkConfigTest.kt` |
| 03 | Settings UI Chunk Selector & Service Integration | Add Material toggle group in SettingsActivity, string resources, and propagate settings to ReadingService | 🟢 Completed | `SettingsPrebufferUiTest.kt` |

## Quick Links
- [Phase 01: Fix GoogleTTS Re-entrancy](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1557-fix-tts-reentrancy-and-chunk-selector/phase-01-fix-googletts-reentrancy.md)
- [Phase 02: Pipeline Chunk Config](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1557-fix-tts-reentrancy-and-chunk-selector/phase-02-pipeline-chunk-config.md)
- [Phase 03: Settings UI Selector](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1557-fix-tts-reentrancy-and-chunk-selector/phase-03-settings-ui-chunk-selector.md)
