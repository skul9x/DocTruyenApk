# Phase 03: Lazy Google TTS Initialization in Settings
Status: 🟢 Complete
Dependencies: Phase 02

## Objective
Convert `tempGoogleTts` in `SettingsActivity.kt` from an eager initialization in `onCreate()` to a strictly on-demand (lazy) initialization. This ensures that opening `SettingsActivity` or viewing debug logs when using VieNeu Offline TTS will never trigger unwanted background Google TTS system IPC binding or initialization logs.

## Requirements
### Functional
- [x] Remove unconditional creation of `GoogleTTSManager` in `SettingsActivity.onCreate()`.
- [x] Create helper `getOrCreateGoogleTts(): GoogleTTSManager` that only instantiates the manager when:
  - The current engine is `GOOGLE_TTS` and voice display needs to query system voices.
  - The user switches the engine toggle to `Google TTS`.
  - The user opens the Google Voice picker dialog (`showGoogleVoicePickerDialog()`).
- [x] Ensure that when engine is `VIENEU` (the default), `GoogleTTSManager` is never created during `SettingsActivity` creation, resume, or destruction.
- [x] Properly clean up `tempGoogleTts?.shutdown()` in `onDestroy()`.

### Non-Functional
- [x] No regression in UI responsiveness when switching between VieNeu and Google TTS.
- [x] No memory leaks or dangling IPC connections.

## Implementation Steps
1. [x] Modify `SettingsActivity.kt`:
   - Remove eager instantiation from `onCreate()`.
   - Implement `getOrCreateGoogleTts()` helper method.
   - Update `setupEngineSelector()`, `updateActiveVoiceDisplay()`, and `showGoogleVoicePickerDialog()` to use lazy initialization.
2. [x] Create unit test `SettingsLazyGoogleTtsTest.kt` to verify that:
   - When configured with `VIENEU`, loading settings or updating UI does not instantiate or bind Google TTS.
   - When switched to `GOOGLE_TTS`, lazy binding is triggered on demand.
3. [x] Run the single unit test to verify correct lazy lifecycle.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/SettingsActivity.kt` - Implement lazy Google TTS initialization
- `app/src/test/java/com/skul9x/doctruyen/SettingsLazyGoogleTtsTest.kt` - [NEW] Verification test

## Test Criteria
- [x] Test that initializing settings with VIENEU engine keeps Google TTS instance null / uninitialized.
- [x] Test that requesting Google voice dialog or switching to Google engine properly initializes the instance.

---
End of Plan.
