# Plan: Fix 50Hz UI & Notification State Callback Spam in TTS Pipeline

Created: 2026-08-20 11:42
Status: 🟡 In Progress

## Overview
Inspection of telemetry and execution logs (`log.txt`) revealed that `TTSAudioPipeline.kt` emits redundant `PipelineState.Buffering` state transitions every 20ms during pre-buffering (`delay(20)` loop). This propagates through `VieNeuTTSManager` -> `ReadingService` -> `StoryDetailActivity`, triggering:
1. 50 UI layout and widget re-renders per second on the Android Main Thread.
2. 50 `NotificationManager.notify()` calls per second, overloading the Android `system_server`.
3. High CPU heating, thermal throttling, and UI stutter.

This plan eliminates redundant state dispatches at every layer of the architecture, enforces strict state deduplication, and safeguards UI and Notification updates.

## Key Changes
- **TTSAudioPipeline**: Deduplicate pipeline state transitions and eliminate 20ms polling state emission.
- **VieNeuTTSManager**: State transition deduplication (`currentState == state` guard).
- **ReadingService**: Avoid re-notifying `NotificationManager` when state has not transitioned.
- **StoryDetailActivity**: Guard against redundant UI redraws for identical states.

## Phases

| Phase | Name | Status | Test File |
|---|---|---|---|
| 01 | Pipeline State Deduplication & Buffering Loop Fix | ⬜ Pending | `PipelineStateDeduplicationTest.kt` |
| 02 | Service & UI Callback Deduplication & Notification Protection | ⬜ Pending | `ReadingServiceStateDeduplicationTest.kt` |
| 03 | End-to-End State Spam Regression Verification | ⬜ Pending | `TTSPlaybackStateSpamRegressionTest.kt` |

## Verification Strategy
- Exactly one comprehensive file-based unit test per phase.
- Verify that under active pre-buffering and playback, state transitions are emitted strictly on distinct state changes.
