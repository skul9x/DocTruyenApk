# Phase 03: Settings UI Precision Selector & End-to-End Flow

Status: ✅ Completed
Dependencies: Phase 01, Phase 02

## Objective
1. Design and integrate an intuitive, modern Model Precision selection card (`cardModelPrecision`) into `activity_settings.xml` and `SettingsActivity.kt`.
2. Connect UI controls (MaterialButtonToggleGroup / RadioButtons) to `UserConfig` and propagate updates to `ReadingService` in real time.
3. Dynamically toggle the visibility of the precision card based on the active TTS engine (visible for VieNeu TTS, hidden for Google TTS).
4. Localize all UI strings in `res/values/strings.xml` and `res/values-vi/strings.xml` (or default strings).
5. Verify complete end-to-end user experience from UI selection to audio synthesis.

## Requirements
### Functional
- [x] Add `cardModelPrecision` to `activity_settings.xml`:
  - Positioned directly beneath `cardTtsEngine`.
  - Header: "Độ chính xác mô hình AI (Precision) / AI Model Precision".
  - Subtitle: "Lựa chọn chế độ lượng tử hóa phù hợp với hiệu năng máy."
  - MaterialButtonToggleGroup (`togglePrecisionGroup`):
    - `btnPrecisionInt8`: "⚡ INT8 (Nhanh & Mát máy)"
    - `btnPrecisionFp32`: "🎯 FP32 (Chất lượng gốc)"
  - Information badge (`tvPrecisionBadge`):
    - When INT8 is selected: "⚡ Dynamic INT8: Tốc độ cao gấp 2x, RTF ~0.5x, tiết kiệm RAM & pin (Khuyên dùng cho mọi thiết bị)."
    - When FP32 is selected: "🎯 FP32 Original: Chất lượng âm thanh chuẩn nguyên bản, tiêu tốn CPU hơn."
- [x] Update `SettingsActivity.kt`:
  - Bind `cardModelPrecision`, `togglePrecisionGroup`, `btnPrecisionInt8`, `btnPrecisionFp32`, and `tvPrecisionBadge`.
  - Restore user selection in `onCreate()` via `UserConfig.getTtsPrecision(this)`.
  - On toggle change:
    - Save new precision to `UserConfig.setTtsPrecision(this, newPrecision)`.
    - Update `tvPrecisionBadge` text and style.
    - Send intent to `ReadingService` with action `ACTION_SWITCH_PRECISION`.
    - Display confirmation Toast/Snackbar.
  - In `setupEngineSelector()`:
    - If Google TTS is selected -> hide `cardModelPrecision` (`View.GONE`).
    - If VieNeu TTS is selected -> show `cardModelPrecision` (`View.VISIBLE`).
- [x] Add UI strings to `app/src/main/res/values/strings.xml`:
  - `settings_precision_title`
  - `settings_precision_desc`
  - `settings_precision_int8`
  - `settings_precision_fp32`
  - `settings_precision_int8_badge`
  - `settings_precision_fp32_badge`
  - `settings_precision_switched`

### Non-Functional
- [x] Responsive & visually appealing layout matching Material Design 3 guidelines.
- [x] Seamless state restoration when opening and closing `SettingsActivity`.
- [x] Instant UI feedback without screen lag or freezing.

## Implementation Steps
1. Add new string resources in `app/src/main/res/values/strings.xml`.
2. Update `app/src/main/res/layout/activity_settings.xml` to add `cardModelPrecision` with toggle buttons and badge.
3. Update `app/src/main/java/com/skul9x/doctruyen/SettingsActivity.kt` to bind and manage precision UI states.
4. Create test file: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/SettingsPrecisionIntegrationTest.kt`.

## Files to Create/Modify
- `app/src/main/res/layout/activity_settings.xml` - Precision selection card layout.
- `app/src/main/res/values/strings.xml` - UI strings for precision selection and badges.
- `app/src/main/java/com/skul9x/doctruyen/SettingsActivity.kt` - Precision controller and service binding.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/SettingsPrecisionIntegrationTest.kt` - Unit test for Settings UI logic, persistence, and service routing.

## Test Criteria
- Exactly one test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/SettingsPrecisionIntegrationTest.kt`
- Verifies initial UI selection reflects `UserConfig.getTtsPrecision`.
- Verifies selecting INT8 vs FP32 updates `UserConfig` and generates the correct `ReadingService` intent.
- Verifies switching between Google TTS and VieNeu TTS properly shows/hides the precision card.
- Verifies complete flow from settings configuration to synthetic audio generation.
