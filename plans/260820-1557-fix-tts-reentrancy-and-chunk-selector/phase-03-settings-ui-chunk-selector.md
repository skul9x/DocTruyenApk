# Phase 03: Settings UI Chunk Selector (Segmented Group) & Integration
Status: 🟢 Completed
Dependencies: Phase 02

## Objective
Add a dedicated Pre-buffering Segmented Toggle Group in `SettingsActivity` (`activity_settings.xml`) allowing the user to select between 1 Chunk, 2 Chunks, or 3 Chunks with real-time badge description and immediate propagation to `ReadingService`.

## Requirements
### Functional
- [x] Add `cardPrebuffering` with a `MaterialButtonToggleGroup` in `activity_settings.xml`:
  - `btnPrebuffer1Chunk`: "1 Chunk (Siêu nhanh / TTFT ~1.8s)"
  - `btnPrebuffer2Chunks`: "2 Chunks (Cân bằng / Khuyên dùng)"
  - `btnPrebuffer3Chunks`: "3 Chunks (Ổn định tối đa / Chống ngắt)"
  - Dynamic `tvPrebufferBadge` explaining the selected strategy.
- [x] Only display `cardPrebuffering` when the active engine is `VIENEU` (hide when `GOOGLE_TTS` is active, matching `cardModelPrecision`).
- [x] In `SettingsActivity.kt`:
  - Implement `setupPrebufferSelector()` to read initial setting, bind click listeners, update `UserConfig`, and notify `ReadingService` via `ACTION_SWITCH_PREBUFFER_CHUNKS`.
- [x] In `ReadingService.kt`:
  - Handle `ACTION_SWITCH_PREBUFFER_CHUNKS` and update the active `VieNeuTTSManager` pipeline target chunks.

### Non-Functional
- [x] Seamless UI styling consistent with Material3 Card and Toggle Groups in `activity_settings.xml`.
- [x] Instant feedback and persistence.

## Implementation Steps
1. [x] Add string resources in `res/values/strings.xml` for pre-buffering options and badge explanations.
2. [x] Add `cardPrebuffering` layout in `activity_settings.xml` under Model Precision Card.
3. [x] Implement `setupPrebufferSelector()` in `SettingsActivity.kt` and handle visibility toggling based on active TTS engine.
4. [x] Add `ACTION_SWITCH_PREBUFFER_CHUNKS` intent action and handler in `ReadingService.kt`.
5. [x] Create unit test `SettingsPrebufferUiTest.kt` to verify:
   - UI state mapping and badge description changes.
   - Preference persistence and intent generation on toggle click.
6. [x] Run the single unit test `SettingsPrebufferUiTest.kt` for verification.

## Files to Create/Modify
- `app/src/main/res/values/strings.xml` - Prebuffer string resources
- `app/src/main/res/layout/activity_settings.xml` - Prebuffer selector card
- `app/src/main/java/com/skul9x/doctruyen/SettingsActivity.kt` - Prebuffer UI binding & logic
- `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt` - Action handler for chunk count switch
- `app/src/test/java/com/skul9x/doctruyen/SettingsPrebufferUiTest.kt` - [NEW] Verification test

## Test Criteria
- [x] Test UI button toggle selection correctly updates `UserConfig` with 1, 2, or 3 chunks.
- [x] Test visibility rule (visible for VIENEU, hidden for GOOGLE_TTS).

---
End of Plan.

