# Phase 06: Story Switching & Playback Takeover

Status: ✅ Completed  
Dependencies: Phase 04 (Dual TTS Engine)

## Objective

Ensure seamless switching between stories: when a user is listening to Story A, navigates back, and selects Story B, the playback of Story A must **immediately and cleanly terminate**, and Story B must start playing without audio overlap, latency, or UI synchronization glitches.

## Problem Statement & Root Cause
1. **Audio Pipeline Drain Latency**: When transitioning from Story A to Story B, buffered PCM chunks of Story A in `AudioTrack` or `chunkQueue` could momentarily play before Story B begins.
2. **Coroutines & Synthesis Race**: Active synthesis coroutines synthesizing chunks for Story A must be cancelled immediately with `NonCancellable` safety to prevent stale chunks from being enqueued into Story B's queue.
3. **UI State Synchronization**: When opening Story B while Story A is playing in the background, `StoryDetailActivity` must recognize it is displaying a new story, show the initial "Đọc truyện" state, and upon user action (or auto-play), immediately take over `ReadingService`.
4. **Pre-warm Cache Collision**: Ensure pre-warmed audio chunks from Story A are completely purged from `PrewarmedChunkCache` when Story B is loaded.

## Implementation Steps

### 6.1 Instant Playback Abort & Flush in Service & Engine
1. [x] In `ReadingService`:
   - When `startReading(newStoryId, ...)` is called with `newStoryId != currentStoryId`:
     - Immediately call `ttsManager?.stop()` to abort existing synthesis and flush `AudioTrack`.
     - Update `currentStoryId = newStoryId` and `currentStoryTitle = title`.
     - Update foreground notification title immediately to Story B.
2. [x] In `TTSAudioPipeline`:
   - Enhance `stopInternal()` to perform an atomic flush:
     - Cancel `synthesisJob` and `playbackJob` synchronously.
     - Call `audioPlayer.flush()` followed by `audioPlayer.stop()`.
     - `chunkQueue.clear()`.
   - Ensure `startPipeline()` verifies that only chunks belonging to the current `storyId` are processed.

### 6.2 Pre-warm Invalidation on Story Change
1. [x] In `PrewarmedChunkCache`:
   - `clearPreviousStory(newStoryId)` ensures zero stale chunks from older stories remain in RAM.
2. [x] In `StoryDetailActivity`:
   - On `loadStoryContent()`, if `readingService?.currentStoryId != 0 && readingService?.currentStoryId != storyId`:
     - Provide clear UI feedback that Story B is ready to play.
     - When user taps "Đọc truyện", trigger instant takeover.

### 6.3 Optional Direct Auto-Switch Flag
1. [x] Support `intent.putExtra("auto_play", true)` when user taps a "Listen Now" action from story list to immediately switch and play Story B.

## Files to Create/Modify
- `[MODIFY] app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt`
- `[MODIFY] app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`
- `[MODIFY] app/src/main/java/com/skul9x/doctruyen/tts/vieneu/PrewarmedChunkCache.kt`
- `[MODIFY] app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt`

## Test Criteria

### Automated Tests
- [x] **StorySwitchingTakeoverTest.kt**:
  - Start reading Story 1 (ID: 101). Verify state is `PLAYING`, storyId = 101.
  - Call `service.startReading(102, "Story 2", "Content 2")`.
  - Assert that Story 1's audio pipeline was stopped immediately.
  - Assert `currentStoryId` is 102 and `currentStoryTitle` is "Story 2".
  - Verify no chunks from Story 1 exist in the queue.
- [x] **PrewarmCacheSwitchTest.kt**:
  - Pre-warm Story 101.
  - Switch to Story 102.
  - Assert `PrewarmedChunkCache.getChunksForStory(101)` returns empty map.

### Manual Verification
- Start playing Story A.
- Press Back to return to Story List in MainActivity. Audio of Story A continues.
- Tap on Story B.
- Press "Đọc truyện" on Story B.
- Verify Story A stops instantly with zero audio bleed-through, and Story B begins reading immediately.

---
Next Phase: [Phase 07 — Background Playback & Screen-Off](./phase-07-background-playback.md)
