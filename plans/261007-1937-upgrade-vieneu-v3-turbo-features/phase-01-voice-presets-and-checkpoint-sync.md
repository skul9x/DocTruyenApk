# Phase 01: Voice Presets, Aliases, Profile & Checkpoint Sync

Status: ✅ Completed
Dependencies: None

## Objective
Update the voice preset asset database to 25 voices from upstream, implement alias resolution so legacy voice names like `Minh Quân Pro` transparently resolve to `Hải Đăng`, configure specialized cadence parameters for `Thiền Tâm Đức`, and synchronize the latest 2026-09-23 Hugging Face model checkpoint.

## Requirements
### Functional
- [x] Replace `app/src/main/assets/vieneu/voices_v3_turbo.json` with the upstream 25-voice dataset.
- [x] Implement alias resolution mapping in `VieNeuTTSManager.kt`:
  - `Minh Quân Pro` -> `Hải Đăng`
  - `Minh Quân` -> `Hải Đăng`
  - `Anh Khôi` -> `Thiện Minh`
  - `Mạnh Dũng` -> `Quốc Tuấn`
- [x] Set `Hải Đăng` as default voice in fallback resolution.
- [x] Retain original name `Adam bựa` as requested.
- [x] Add custom voice tuning profile for `Thiền Tâm Đức`:
  - Lower sampling temperature (`0.25f` vs default `0.45f`) for calm, steady cadence.
  - Extended clause pause (`300ms` vs `180ms`) and sentence pause (`600ms` vs `350ms`).
- [x] Verify checkpoint alignment with upstream Hugging Face repo `pnnbao-ump/VieNeu-TTS-v3-Turbo` (commit `61b85e3...`). Ensure compatibility with INT8 quantized models in `tools/quantize_models.py`.

### Non-Functional
- [x] Memory footprint: Zero redundant allocation for aliases; use lightweight map lookup.
- [x] Backward compatibility: Any user config or saved preference with `Minh Quân Pro` or `Anh Khôi` continues to work without error.

## Implementation Steps
1. [x] Update `app/src/main/assets/vieneu/voices_v3_turbo.json` from `VieNeu-TTS-main/src/vieneu/assets/voices_v3_turbo.json`.
2. [x] Modify `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` to load alias dictionary from `voices_v3_turbo.json` and resolve voice names prior to embedding extraction.
3. [x] Add `VoiceTuningProfile` mapping in `VieNeuTTSManager.kt` to apply custom parameters when `voiceName == "Thiền Tâm Đức"`.
4. [x] Synchronize and verify model weights against `app/src/main/assets/vieneu/backbone/` and run quantization checks if needed.
5. [x] Write and execute the single verification test: `VoicePresetsAndCheckpointSyncTest.kt`.

## Files to Create/Modify
- `app/src/main/assets/vieneu/voices_v3_turbo.json` - Updated voice presets (25 voices + aliases).
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Alias resolution & voice-specific cadence tuning.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/VoicePresetsAndCheckpointSyncTest.kt` - Single comprehensive unit test.

## Single File-Based Test Criteria
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/VoicePresetsAndCheckpointSyncTest.kt`
- Test Scenarios:
  1. `testAll25VoicesLoadCorrectly`: Verify that exactly 25 voices exist with 192-dim speaker embeddings and valid reference codes.
  2. `testAliasResolution`: Assert that requesting `Minh Quân Pro`, `Minh Quân`, `Anh Khôi`, and `Mạnh Dũng` correctly resolves to `Hải Đăng`, `Thiện Minh`, and `Quốc Tuấn`.
  3. `testThienTamDucCustomProfile`: Verify that selecting `Thiền Tâm Đức` applies temperature `0.25f` and extended pause durations.
  4. `testDefaultVoiceIsHaiDang`: Verify fallback when voice is unspecified resolves to `Hải Đăng`.

## Review Checkpoint
Stop after running `VoicePresetsAndCheckpointSyncTest.kt` and await user review before proceeding to Phase 02.
