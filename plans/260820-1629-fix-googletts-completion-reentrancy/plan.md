# Plan: Fix GoogleTTS Completion State Reentrancy and Re-binding

Created: 2026-08-20 16:29
Status: 🟡 In Progress

## Overview
Prevent state chattering (flipping between READY and PLAYING), duplicate completion callbacks, and unnecessary late engine re-binding when Google TTS completes playback.

## Tech Stack
- Kotlin / Android
- Google TextToSpeech API / UtteranceProgressListener
- JUnit 4 / Mockito / Robolectric

## Phases

| Phase | Name | Status | Progress |
|-------|------|--------|----------|
| 01 | Fix Utterance Progress Listener Reentrancy & Completion Deduplication | ⬜ Pending | 0% |
| 02 | Prevent Post-Session Engine Re-binding & Story State Reset in ReadingService | ⬜ Pending | 0% |

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
