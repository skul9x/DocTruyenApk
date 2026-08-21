# Phase 01: Update Preset Voices (20 Voices) & Asset Sync
Status: ✅ Completed
Dependencies: None

## Objective
Synchronize the new `voices_v3_turbo.json` containing 20 curated voices across North, Central, and South regions from upstream `VieNeu-TTS-main` into `DocTruyenApk-main`. Verify that the existing generic parser in `VoicePresets.kt` handles all 20 voices correctly without code changes.

## Requirements
### Functional
- Copy the latest `voices_v3_turbo.json` (20 preset voices) from `/home/skul9x/Desktop/Test_code/VieNeu-TTS-main/src/vieneu/assets/voices_v3_turbo.json` to `app/src/main/assets/vieneu/voices_v3_turbo.json`.
- Verify `VoicePresets.kt` correctly parses all 20 voices — the existing `loadFromJson()` is already generic and handles: `name`, `description`, `gender`, `region`, `style`, `speaker_emb` (192-dim float array), and optional `codes`. **No parser code changes expected.**
- Verify `SettingsActivity.kt` dynamically loads and displays all 20 voices in the voice selector dialog — it already iterates `VoicePresets.availableVoices` without hardcoding count. **No UI code changes expected.**
- Ensure fallback resolution for region display, gender display, and default voice selection works for all 20 entries.

### Non-Functional
- Zero allocation leak during JSON parsing; thread-safe caching of presets in `VoicePresets.kt` (already implemented via `ConcurrentHashMap`).

## Implementation Steps
1. Copy `voices_v3_turbo.json` from `VieNeu-TTS-main` to `DocTruyenApk-main/app/src/main/assets/vieneu/`.
2. Verify existing `VoicePresets.loadFromJson()` handles all 20 voices with correct metadata extraction (gender, region, style, 192-dim embeddings, codes).
3. Verify `SettingsActivity.kt` voice selector handles 20 items without UI overflow.
4. Create and run exactly one comprehensive unit test `VoicePresetsSyncTest.kt`.

## Files to Create/Modify
- `app/src/main/assets/vieneu/voices_v3_turbo.json` — **[REPLACE]** Updated 20-voice preset definitions copied from upstream.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/VoicePresetsSyncTest.kt` — **[NEW]** Comprehensive unit test for voice loading & parsing of all 20 voices.

> Note: `VoicePresets.kt` and `SettingsActivity.kt` are NOT expected to need code changes — the existing parser and UI are already generic. If the test reveals any parsing issues with the new 20-voice JSON, targeted fixes will be applied.

## Test Criteria
- [x] Exactly one file-based unit test `VoicePresetsSyncTest.kt` passes, verifying that all 20 voices load, metadata (gender, region, 192-dim speaker embeddings, codes) are valid, and formatting works as expected.

---
Next Phase: [Phase 02](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260821-1915-sync-vieneu-v3-turbo-upstream/phase-02-adaptive-frame-capping.md)
