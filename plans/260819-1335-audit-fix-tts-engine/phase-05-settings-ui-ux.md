# Phase 05: Settings UI/UX Overhaul

Status: ✅ Completed  
Dependencies: Phase 04 (Dual TTS Engine)

## Objective

Redesign and optimize the Settings screen (`SettingsActivity` & `activity_settings.xml`) to provide a modern, intuitive, and visually pleasing experience adhering to Material 3 guidelines.

## Key UI/UX Enhancements

### 1. Grouped Card Layout Architecture
Transform the flat scroll view into structured Material 3 `ElevatedCard` containers with clear headers, icons, and visual separation:
1. **TTS Engine Selector Card**:
   - Segmented button or selectable cards: **VieNeu Neural TTS (Ngoại tuyến)** vs **Google TTS (Hệ thống / Trực tuyến)**.
   - Informative subtitle badges: "Offline AI • Giọng tự nhiên" vs "Google Cloud • Tiết kiệm RAM".
2. **Voice Selection Card**:
   - Displays currently active voice name with gender & regional accent tags (e.g., `Trúc Ly (Nữ - Bắc - Tự nhiên)`).
   - "Đổi giọng đọc" Material Button opening a rich Dialog / BottomSheet with search and categorized voices.
3. **Voice Modulation Card (Tốc độ & Cao độ)**:
   - Modern `Slider` with numeric indicators (`0.5x` -> `2.0x`).
   - "Đặt lại mặc định" quick button to reset pitch & speed to `1.0x`.
4. **Reading Cadence & Natural Pauses Card (Ngắt nghỉ & Nhịp đọc)**:
   - Preset chips: `Chuẩn (400ms)`, `Đọc nhanh (250ms)`, `Truyện cảm xúc (600ms)`.
   - Expandable sliders for Sentence, Paragraph, and Sub-clause pause durations.
5. **Server Connection Card**:
   - Clean `OutlinedTextField` for API URL with inline verification status icon.
   - "Kiểm tra kết nối" button with loading spinner state.
6. **Diagnostics & Logging Card**:
   - Switch for debug log output and button to open `DebugLogActivity`.

### 2. Dynamic UI Adaptation
- When user switches from **VieNeu** to **Google TTS**, the Voice Selection card automatically refreshes to display Google TTS voices.
- Subtitle and recommendations change dynamically.

## Implementation Steps

1. [x] Redesign `activity_settings.xml` using `MaterialCardView`, `MaterialButtonGroup`/`SingleSelectToggleGroup`, and consistent spacing tokens (8dp/16dp grid).
2. [x] Update `SettingsActivity.kt`:
   - Implement TTS Engine toggle listeners and sync with `UserConfig`.
   - Implement dynamic voice picker dialog updating based on current engine.
   - Add "Reset to Default" button handler for pitch and speed.
   - Add smooth enter/exit animations and feedback toasts.
3. [x] Add string resources in `res/values/strings.xml` for all new UI labels, descriptions, and tooltips.
4. [x] Ensure dark mode and edge-to-edge system bar contrast compatibility.

## Files to Create/Modify
- `[MODIFY] app/src/main/res/layout/activity_settings.xml`
- `[MODIFY] app/src/main/java/com/skul9x/doctruyen/SettingsActivity.kt`
- `[MODIFY] app/src/main/res/values/strings.xml`

## Test Criteria

### Automated Tests
- [x] **SettingsActivityLogicTest.kt**:
  - Test engine switch persistence: changing to `GOOGLE_TTS` updates `UserConfig.getTtsEngine()`.
  - Test voice selection persistence for both VieNeu and Google TTS independently.
  - Test pause preset application correctly sets sentence, paragraph, and clause values.

### Manual Verification
- Open Settings on device/emulator:
  - Verify layout renders cleanly without overlapping on small and large screens.
  - Toggle between VieNeu and Google TTS; verify voice list updates immediately.
  - Test sliders for speed, pitch, and pauses; verify values format accurately.
  - Verify back button saves state and returns seamlessly to MainActivity.

---
Next Phase: [Phase 06 — Story Switching](./phase-06-story-switching.md)
