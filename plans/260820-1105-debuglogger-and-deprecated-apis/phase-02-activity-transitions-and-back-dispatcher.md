Status: ✅ Completed  
Dependencies: None

## Objective

Migrate deprecated `overridePendingTransition()` calls in `MainActivity.kt` and `StoryDetailActivity.kt` to the modern Android 14+ (API 34+) `overrideActivityTransition()` API with backward-compatible fallbacks. Replace the deprecated `Activity.onBackPressed()` override in `StoryDetailActivity.kt` with modern AndroidX `OnBackPressedDispatcher` and `OnBackPressedCallback`.

---

## Requirements

### 1. Functional Requirements

#### A. Activity Custom Transition Animation Migration
- **Target Files:** `MainActivity.kt`, `StoryDetailActivity.kt`
- **Modern API (API 34+):**
  - Use `overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, android.R.anim.fade_in, android.R.anim.fade_out)` when launching `StoryDetailActivity`.
  - Use `overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, android.R.anim.fade_in, android.R.anim.fade_out)` when closing/finishing `StoryDetailActivity`.
- **Legacy Fallback (API < 34):**
  - Keep `overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)` safely protected under `@Suppress("DEPRECATION")` within `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)`.
- **Helper Utility:** Provide a clean extension/helper `ActivityTransitionHelper` to eliminate duplicate boilerplate code.

#### B. Predictive Back Dispatcher Migration
- **Target File:** `StoryDetailActivity.kt`
- **Problem:** `override fun onBackPressed()` is deprecated in Android 13/14 (API 33+) and blocks Predictive Back gesture animations.
- **Solution:**
  - Register an `OnBackPressedCallback` via `onBackPressedDispatcher.addCallback(this)`.
  - Inside `handleOnBackPressed()`:
    1. Check if `readingService` is bound and not playing; if so, cancel active background pre-warming (`service.cancelPrewarm()`).
    2. Finish activity and apply modern close transition.
  - Remove `@Deprecated override fun onBackPressed()`.

---

## Implementation Steps

1. [ ] Create transition helper in `app/src/main/java/com/skul9x/doctruyen/utils/ActivityTransitionHelper.kt`:
   - Handles `overrideActivityTransition` for API 34+ and falls back to `overridePendingTransition` for API < 34.
2. [ ] In `MainActivity.kt`:
   - In `openStoryDetail(story: Story)`:
     * Call `startActivity(intent)`
     * Apply fade transition via `ActivityTransitionHelper`.
3. [ ] In `StoryDetailActivity.kt`:
   - In `onCreate()`:
     * Set up `onBackPressedDispatcher.addCallback(this) { ... }` with proper pre-warm cleanup and back handling.
     * Configure close transition in `onCreate` or on back press.
   - In `btnBack.setOnClickListener`:
     * Call `onBackPressedDispatcher.onBackPressed()` cleanly.
   - Delete deprecated `onBackPressed()` override.
4. [ ] Add test cases to the unified test file `app/src/test/java/com/skul9x/doctruyen/AuditFixesCombinedTest.kt`.

---

## Files to Create / Modify
- [NEW] `app/src/main/java/com/skul9x/doctruyen/utils/ActivityTransitionHelper.kt` — Shared helper for version-aware activity transitions.
- [MODIFY] `app/src/main/java/com/skul9x/doctruyen/MainActivity.kt` — Update activity transition in `openStoryDetail`.
- [MODIFY] `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` — Migrate to `OnBackPressedDispatcher` and update transitions.
- [MODIFY] `app/src/test/java/com/skul9x/doctruyen/AuditFixesCombinedTest.kt` — Unified test suite (contains `testActivityTransitions_andBackDispatcher`).

---

## Unified Test Criteria (Part 2 - Activity Navigation & Transitions)

Target test method in `app/src/test/java/com/skul9x/doctruyen/AuditFixesCombinedTest.kt`:

1. **`testActivityTransitions_andBackDispatcher`:**
   - Verify `ActivityTransitionHelper` correctly branches for modern API 34+ vs legacy API < 34 without runtime crashes.
   - Verify `OnBackPressedCallback` cancels pre-warming when service is in IDLE/READY state, and retains audio playback when in PLAYING state.

---
Next Phase: [Phase 03 — MediaSession & WebView Deprecations](./phase-03-mediasession-and-webview-deprecations.md)
