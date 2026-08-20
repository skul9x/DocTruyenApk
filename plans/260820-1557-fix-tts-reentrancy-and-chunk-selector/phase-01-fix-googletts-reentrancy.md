# Phase 01: Fix GoogleTTS Re-entrancy & Callback Guard
Status: 🟩 Completed
Dependencies: None

## Objective
Fix the critical recursive instantiation bug where `GoogleTTSManager` constructor fires state callbacks during construction, causing `SettingsTtsHolder` to re-enter `getOrCreateGoogleTts()` before the instance reference is assigned, producing infinite recursion and `java.lang.StackOverflowError`.

## Requirements
### Functional
- [x] In `SettingsTtsHolder.getOrCreateGoogleTts()`:
  - Add an atomic initialization guard or assign the instance before invoking any callbacks.
  - Filter `onVoiceDisplayRefresh` to only trigger when state is `READY` (not `INITIALIZING`), preventing premature queries to unbound TTS engines.
- [x] In `GoogleTTSManager.kt`:
  - Ensure callbacks are not fired synchronously inside `init` until engine setup listener fires asynchronously or defer notification.
  - Avoid calling `getVoices()` when `engineWrapper` or `TextToSpeech` is not yet bound.
- [x] In `SettingsActivity.kt`:
  - Ensure switching engines or updating voice displays never triggers unconstrained recursion.

### Non-Functional
- [x] Zero `StackOverflowError` or unhandled exceptions in Logcat when switching engines.
- [x] Thread safety and clean lifecycle teardown.

## Implementation Steps
1. [x] Update `SettingsTtsHolder` in `SettingsActivity.kt` with re-entrancy lock and state filtering (`state == ITTSManager.TTSState.READY`).
2. [x] Update `GoogleTTSManager.kt` to guard against premature callback execution during constructor.
3. [x] Create unit test `GoogleTtsReentrancySafetyTest.kt` to verify that instantiating `GoogleTTSManager` with immediate callback execution does not cause infinite recursion or stack overflows.
4. [x] Run the single unit test `GoogleTtsReentrancySafetyTest.kt` for verification.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/SettingsActivity.kt` - Fix SettingsTtsHolder re-entrancy guard
- `app/src/main/java/com/skul9x/doctruyen/tts/GoogleTTSManager.kt` - Safe initialization callbacks
- `app/src/test/java/com/skul9x/doctruyen/GoogleTtsReentrancySafetyTest.kt` - [NEW] Verification test

## Test Criteria
- [x] Test that `getOrCreateGoogleTts()` handles immediate callback execution without recursive duplicate creation.
- [x] Test that state changes to `READY` properly update voice display while `INITIALIZING` does not cause loop.

---
Next Phase: [Phase 02: Pipeline Chunk Config](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1557-fix-tts-reentrancy-and-chunk-selector/phase-02-pipeline-chunk-config.md)
