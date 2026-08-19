# Phase 04: Settings Configuration & Pause Customization
Status: ✅ Completed
Dependencies: phase-01-timing-logger.md, phase-02-intelligent-segmentation.md, phase-03-natural-pause-audio-pipeline.md

## Objective
Expose customizable reading rhythm and pause preferences in `UserConfig` and `SettingsActivity`. Allow users to adjust sentence pauses, paragraph pauses, and clause pauses, and view live reading performance metrics in the Settings Debug Log.

## Requirements

### Functional Requirements
- [x] Add pause configuration keys in `UserConfig`:
  - `KEY_TTS_PAUSE_SENTENCE` (Float / Int ms, default 400ms, range 150ms – 1000ms)
  - `KEY_TTS_PAUSE_PARAGRAPH` (Float / Int ms, default 650ms, range 300ms – 1500ms)
  - `KEY_TTS_PAUSE_CLAUSE` (Float / Int ms, default 180ms, range 50ms – 500ms)
  - `KEY_TTS_DEBUG_LOGGING` (Boolean, default true)
- [x] Update `activity_settings.xml` and `SettingsActivity.kt`:
  - Add section "Nhịp điệu giọng đọc (Ngắt nghỉ câu/đoạn)" (Reading Cadence & Pauses).
  - Add UI controls (Sliders or Dialog presets: "Tự nhiên / Chuẩn", "Đọc nhanh / Ngắt ngắn", "Truyện cảm xúc / Ngắt dài").
  - Add live preview or direct link to "Nhật ký đọc & Đo đạc thời gian" (Debug Logs).
- [x] Connect `UserConfig` pause settings dynamically to `SmartTextSegmenter` and `VieNeuTTSManager`.

### Non-Functional Requirements
- Instant persistence in `SharedPreferences`.
- Clean UI matching Material Design 3 guidelines and existing dark/light app theme.

## Implementation Steps
1. [x] Add getter/setter helpers in `UserConfig.kt`.
2. [x] Update layout `app/src/main/res/layout/activity_settings.xml` with pause adjustment controls.
3. [x] Wire controls and listeners in `SettingsActivity.kt`.
4. [x] Pass pause configuration into `VieNeuTTSManager` on reading start.
5. [x] Implement file-based test suite `UserConfigPauseTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/utils/UserConfig.kt` - [MODIFY] Add pause settings properties
- `app/src/main/res/layout/activity_settings.xml` - [MODIFY] Add UI controls for pause settings
- `app/src/main/java/com/skul9x/doctruyen/SettingsActivity.kt` - [MODIFY] Bind pause controls and validation
- `app/src/test/java/com/skul9x/doctruyen/utils/UserConfigPauseTest.kt` - [NEW] File-based verification test suite

## Test Criteria (File-Based Tests)
- [x] `UserConfigPauseTest.kt` passes with 100% assertions on:
  - Default pause values loaded correctly
  - Custom values preserved and correctly clamped within valid ranges
  - Pause values correctly propagate to `SmartTextSegmenter`

---
Next Phase: [phase-05-device-testing-benchmarking.md](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-0855-tts-timing-natural-pauses/phase-05-device-testing-benchmarking.md)
