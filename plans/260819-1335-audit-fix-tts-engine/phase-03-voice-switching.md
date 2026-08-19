# Phase 03: Voice Switching Bug Fix

Status: ✅ Completed  
Dependencies: Phase 01 (same engine file modified)

## Objective

Fix the bug where changing voice in Settings has no effect on TTS output. Currently, the user can select a different voice (e.g., "Minh Đức" instead of "Trúc Ly") in the Settings dialog, but the TTS always reads with the same voice.

## Root Cause Analysis

After analyzing the code flow, the bug chain is:

1. **Settings saves voice name to SharedPreferences** via `UserConfig.setTtsVoice()` ✅ (works)
2. **`VieNeuTTSManager.initManager()`** reads `UserConfig.getTtsVoice()` at init time and sets `voiceName` ✅ (works at init)
3. **BUT `ReadingService` is a long-lived `Service`** that creates `VieNeuTTSManager` in `onCreate()` ONCE. When the user changes the voice in Settings and goes back, the service is NOT recreated.
4. **`ReadingService.startReading()` and `prepareStory()`** only call `ttsManager?.setVoice(voice)` when an explicit `voice` parameter is passed, but `StoryDetailActivity` never passes a voice parameter — it calls `service.startReading(storyId, title, storyContent)` and `service.prepareStory(storyId, title, storyContent)` without the `voice` argument.
5. **Therefore**, the voice set at Service creation time is used forever, ignoring any Settings changes.

**Secondary issue:** Even if `setVoice()` is called on `VieNeuTTSManager`, it updates `pipeline?.voiceName = name`. The `TTSAudioPipeline` constructor has `var voiceName: String = "Trúc Ly"` but the grep shows `voiceName` is NOT referenced anywhere inside `TTSAudioPipeline.kt` itself. Looking at the synthesis code in `startPipeline()`, line 305 calls `engine.infer(phonemes = phonemes, voiceName = voiceName)` — but this `voiceName` is the pipeline's field. Wait — the grep returned no results for `voiceName` in `TTSAudioPipeline.kt`. Let me re-check...

Actually, looking at line 305 of TTSAudioPipeline: `engine.infer(phonemes = phonemes, voiceName = voiceName)` — this `voiceName` IS the pipeline's `var voiceName` field (declared on line 30). So the pipeline DOES use it. The bug is purely in the service layer: `ReadingService` doesn't read the latest voice from `UserConfig` before starting playback.

## Implementation Steps

### 3.1 Fix ReadingService to Read Fresh Voice from UserConfig

1. [x] In `ReadingService.startReading()`: always read `UserConfig.getTtsVoice(this)` and call `ttsManager?.setVoice(freshVoice)` BEFORE calling `speakWithTitlePause`
2. [x] In `ReadingService.prepareStory()`: same pattern — read fresh voice from UserConfig if no explicit voice param is provided
3. [x] In `VieNeuTTSManager.setVoice()`: ensure it also updates `pipeline?.voiceName` (already does ✅) AND clears any cached pre-warmed chunks for different voices

### 3.2 Fix Pre-Warming to Use Fresh Voice

1. [x] In `VieNeuTTSManager.prepareStory()`: read fresh voice from `appContext?.let { UserConfig.getTtsVoice(it) }` and use it
2. [x] In `StoryDetailActivity.triggerPrewarmIfReady()`: pass the current voice from UserConfig to `service.prepareStory()`

### 3.3 Ensure Pipeline Applies Voice Correctly

1. [x] Verify `TTSAudioPipeline.startPipeline()` uses `this.voiceName` (not a stale captured value) — confirmed ✅
2. [x] Add a `voiceName` parameter to `PrewarmedChunkCache.getChunksForStory()` and filter by voice — already implemented ✅

### 3.4 Invalidate Pre-Warmed Cache on Voice Change

1. [x] In `VieNeuTTSManager.setVoice()`: call `PrewarmedChunkCache.clear()` to flush any cached audio with old voice

## Files to Create/Modify

- `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt` — `startReading()`, `prepareStory()`
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` — `setVoice()`, `prepareStory()`
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` — `triggerPrewarmIfReady()`

## Test Criteria

### Automated Tests

- [ ] **VoiceSwitchTest.kt** — Unit test verifying:
  1. Set voice to "Trúc Ly" in UserConfig mock
  2. Call `ReadingService.startReading()` → verify `ttsManager.setVoice("Trúc Ly")` was called
  3. Change voice to "Minh Đức" in UserConfig mock
  4. Call `ReadingService.startReading()` again → verify `ttsManager.setVoice("Minh Đức")` was called
  5. Verify `pipeline.voiceName` reflects the change

- [ ] **PrewarmCacheInvalidationTest.kt** — Verify:
  1. Pre-warm story with voice A
  2. Change voice to B
  3. `setVoice(B)` clears the pre-warm cache
  4. Pre-warm again with voice B → new cache entries

### Manual Verification
1. Open Settings → select voice "Minh Đức"
2. Go to a story → press Play
3. Verify the audio uses "Minh Đức" voice (audibly different from default "Trúc Ly")
4. Go back to Settings → change to "Phạm Tuyên"
5. Go to same story → press Play
6. Verify the audio uses "Phạm Tuyên" voice

---

## Notes
- This is a critical UX bug — the entire voice selection feature is currently non-functional
- The fix is purely in the service layer (ReadingService), not in the engine or pipeline
- Must NOT break the pre-warming optimization (ensure cache is voice-aware)

---
Next Phase: [Phase 04 — Dual TTS Engine](./phase-04-dual-tts-engine.md)
