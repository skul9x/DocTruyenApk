# Plan: Fix StoryDetailActivity Multi-Instance & CPU Contention Bug

Created: 2026-08-20 15:03
Status: 🟡 In Progress

## Problem Statement

When the user rapidly taps a story item or the Activity is recreated (e.g. configuration change),
multiple instances of `StoryDetailActivity` are created (default `standard` launchMode).
Each instance independently triggers:
- Network API call (`loadStoryContent`)
- Service binding (`bindService`)
- Pre-warming call (`prepareStory`)

This causes **CPU contention** with the ONNX inference thread, leading to:
- Chunk inference time spiking from ~3.5s to **16.9s** (4.40x RTF)
- **3 buffer starvations** (audio glitches)
- Overall RTF degrading from 1.02x to **1.26x**

Evidence: [log5.txt](../../log5.txt) — 4 Activity instances created within 13 seconds while TTS was actively playing.

## Root Cause Analysis

1. **`StoryDetailActivity` has no `launchMode`** in AndroidManifest.xml (defaults to `standard`)
2. **`loadStoryContent()` has no deduplication guard** — always fires a network request regardless of existing data
3. **`ReadingService.prepareStory()` has no state guard** — accepts calls even when already playing the same story
4. **`isPrewarmTriggered` guard is per-Activity-instance** — resets to `false` on each new Activity instance

## Phases

| Phase | Name | Status | Test |
|-------|------|--------|------|
| 01 | Manifest singleTop + onNewIntent | ⬜ Pending | `StoryDetailSingleTopTest.kt` |
| 02 | ReadingService prepareStory Guard | ⬜ Pending | `ReadingServicePrepareGuardTest.kt` |
| 03 | StoryDetailActivity Content Cache Guard | ⬜ Pending | `StoryDetailContentCacheGuardTest.kt` |

## Files To Modify

### Phase 01
- `app/src/main/AndroidManifest.xml` — Add `android:launchMode="singleTop"`
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` — Add `onNewIntent()`, refactor `loadStoryFromIntent()` to a reusable `handleIntent()`

### Phase 02
- `app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt` — Add state check in `prepareStory()`

### Phase 03
- `app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt` — Add content cache guard in `loadStoryContent()`

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
