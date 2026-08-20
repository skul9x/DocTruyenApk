# Plan: Configurable Pre-buffering Chunks (1, 2, or 3 Chunks) in Settings
Created: 2026-08-20T15:55:02+07:00
Status: 🟡 In Progress

## Overview
Provide users with an interactive selector in Settings to customize the initial audio pre-buffering buffer depth (1 Chunk, 2 Chunks, or 3 Chunks) for the VieNeu Offline TTS engine. This allows users to choose between:
- **1 Chunk (Ultra Fast / Low TTFT)**: Immediate audio playback (~1.8s TTFT), prioritizing instant responsiveness.
- **2 Chunks (Balanced / Recommended)**: Fast start (~3.5s TTFT) while maintaining buffer safety against underruns.
- **3 Chunks (Max Stability)**: Deep buffer (~8.0s TTFT), ideal for background listening without interruptions.

## Tech Stack & Architecture
- **Configuration Persistence**: `UserConfig.kt` (`KEY_TTS_PREBUFFER_CHUNKS`, default: 2).
- **Core Engine & Pipeline**: `TTSAudioPipeline.kt` dynamically adapts `minPreBufferChunks` and `targetDurationMs` based on user selection.
- **Service Layer**: `ReadingService.kt` updates pipeline parameters immediately on engine or setting switches.
- **UI & Controls**: `SettingsActivity.kt` & `activity_settings.xml` featuring a MaterialButtonToggleGroup with dynamic badge descriptions.

## Phases

| Phase | Name | Description | Status | Verification Test |
|-------|------|-------------|--------|-------------------|
| 01 | UserConfig & Audio Pipeline Pre-buffering Config | Persist chunk settings in UserConfig and adapt TTSAudioPipeline pre-buffering logic for 1, 2, and 3 chunks | ⬜ Pending | `PrebufferChunkConfigTest.kt` |
| 02 | Settings UI Chunk Selector & Service Integration | Add Material toggle group in SettingsActivity, string resources, and propagate changes to ReadingService | ⬜ Pending | `SettingsPrebufferUiTest.kt` |

## Quick Links
- [Phase 01: UserConfig & Pipeline Config](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1555-prebuffering-chunk-selector/phase-01-userconfig-and-pipeline-chunk-config.md)
- [Phase 02: Settings UI Selector](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1555-prebuffering-chunk-selector/phase-02-settings-ui-chunk-selector.md)
