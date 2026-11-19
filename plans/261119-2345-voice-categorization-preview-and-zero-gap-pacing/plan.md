# Plan: Voice Categorization, Audio Preview & Zero-Gap Pacing

Created: 2026-11-19 23:45
Status: 🟢 Completed

## Overview
Implement comprehensive voice categorization and filtering, interactive on-demand audio preview, and zero-gap cadence (0 ms pause support) in the DocTruyen settings and TTS pipeline:

1. **Phase 01 (Zero-Gap Pacing & Cadence):** Extend pause duration ranges across settings sliders, configuration persistence, and the TTS pipeline down to 0 ms with adaptive labeling ("0 ms (Liền mạch / Zero Gap)") and pipeline zero-gap safety.
2. **Phase 02 (Voice Metadata Categorization & Filtering Model):** Structure and normalize voice metadata across Gender (Nam, Nữ), Region (Bắc, Trung, Nam), and Style (Kể chuyện, Tự nhiên, Tin tức), providing efficient multi-faceted query/filtering logic.
3. **Phase 03 (Lifecycle-Safe Audio Preview Engine & Cache):** Build a dedicated, single-active audio preview playback engine with in-memory PCM caching, cooperative coroutine cancellation, and guaranteed resource cleanup on dialog dismissal.
4. **Phase 04 (Traditional Dialog Voice Selector UI with Filter Chips & Preview):** Construct a traditional Material AlertDialog featuring 3-tier horizontal ChipGroup filters, constrained-height scrollable RecyclerView, interactive voice items with metadata badges, and inline preview play/stop controls.

## Tech Stack
- Kotlin 2.0 / Android SDK (minSdk 24, targetSdk 35)
- Google Material Components (MaterialAlertDialogBuilder, ChipGroup, Slider, RecyclerView)
- Kotlin Coroutines & Flow (structured concurrency, cooperative cancellation)
- AudioTrack PCM 16-bit 24kHz / On-device VieNeu ONNX Engine / Google TTS
- JUnit 4 / Android Unit Tests / Mockito

## Phases

| Phase | Name | Status | Single File-Based Test | Progress |
|---|---|---|---|---|
| 01 | Zero-Gap Pacing and Cadence | ✅ Completed | `ZeroGapPauseCadenceTest.kt` | 100% |
| 02 | Voice Metadata Categorization | ✅ Completed | `VoiceCategorizationFilterTest.kt` | 100% |
| 03 | Audio Preview Engine and Cache | ✅ Completed | `AudioPreviewEngineTest.kt` | 100% |
| 04 | Traditional Dialog Voice Selector UI | ✅ Completed | `VoiceSelectorDialogLogicTest.kt` | 100% |

## Strict Verification Protocol
- Exactly **one comprehensive file-based test** is assigned per phase.
- Do NOT create or run more than one test per phase.
- After completing implementation for each phase, run ONLY that single test for verification.
- Stop immediately after running the test to await user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
