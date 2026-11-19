# Phase 04: Traditional Dialog Voice Selector UI

Status: ✅ Completed
Dependencies: Phase 01, Phase 02, Phase 03

## Objective
Build a traditional pop-up Material AlertDialog voice selector featuring 3-tier ChipGroup filters, a constrained-height scrollable list, active voice selection, and embedded audio preview buttons with complete lifecycle safety.

## Scope
- Custom dialog layout XML (`dialog_voice_selector.xml`).
- Voice item layout XML (`dialog_item_voice.xml`).
- String and drawable resources for filters, badges, and preview controls.
- Settings Activity dialog binding and lifecycle management (`SettingsActivity.kt`).
- RecyclerView Adapter using `ListAdapter` with `DiffUtil`.

## Requirements
### Functional
- Construct a traditional pop-up dialog using `MaterialAlertDialogBuilder` (not a bottom sheet).
- Include 3 horizontal scrolling `ChipGroup` rows at the top of the dialog:
  1. **Gender:** `[Tất cả]`, `[Nam]`, `[Nữ]`.
  2. **Region:** `[Tất cả]`, `[Bắc]`, `[Trung]`, `[Nam]`.
  3. **Style:** `[Tất cả]`, `[Kể chuyện]`, `[Tự nhiên]`, `[Tin tức]`.
- Embed a `RecyclerView` with `app:layout_constrainedHeight="true"` (capped at ~360dp or 50% screen height via ConstraintLayout) to prevent overflowing the dialog boundaries while allowing smooth recycling.
- In each voice item, display:
  - RadioButton indicating current selection.
  - Voice display name.
  - Category badges (`[Nam]`, `[Miền Nam]`, `[Kể chuyện]`).
  - An Audio Preview button (Play/Stop toggle icon) with a mini progress/loading indicator.
- Display an empty state view with a "Reset Filters" action when no voices match the active chips.
- Selecting a voice marks it active, saves to `UserConfig`, and updates the active voice display in Settings.
- Automatically stop preview playback and cancel background synthesis coroutines when the dialog is dismissed or canceled.
- Ensure audio preview is also stopped in `SettingsActivity.onDestroy()`.

### Non-Functional
- Never nest the `RecyclerView` directly inside a vertical `ScrollView`.
- Use `ListAdapter` with `DiffUtil.ItemCallback` to update the list smoothly upon filter changes without full rebinds (`notifyDataSetChanged`).
- Maintain consistent visual theming across dark and light modes according to Material Design 3 guidelines.
- Support both VieNeu neural voices and Google TTS system voices.

## Constraints
- Must use traditional dialog presentation (`MaterialAlertDialogBuilder`).
- Audio preview must stop immediately upon dialog dismissal, with zero audible artifacts or ghost audio.
- Comply with Android edge-to-edge and system window insets guidelines.

## Dependencies
- Phase 01 (Zero-gap pacing and cadence).
- Phase 02 (Voice metadata categorization).
- Phase 03 (Audio preview engine and cache).

## Acceptance Criteria
- Clicking "Select Voice" in Settings launches the traditional pop-up dialog with filter chips and voice list.
- Toggling chips in any of the 3 filter groups dynamically updates the visible voice items in real time.
- Clicking the preview button on a voice initiates audio playback, toggling its icon to Stop; clicking Stop halts playback.
- Dismissing the dialog (via Cancel, outside touch, Back button, or Confirmation) immediately silences preview audio.
- Confirming a voice selection persists the chosen voice in `UserConfig` and updates `tvCurrentVoice` on the Settings screen.
- Running the comprehensive file-based test passes with 100% success.

## Single File-Based Test
`app/src/test/java/com/skul9x/doctruyen/ui/VoiceSelectorDialogLogicTest.kt`
