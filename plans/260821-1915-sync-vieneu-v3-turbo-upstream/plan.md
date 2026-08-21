# Plan: Sync VieNeu-TTS v3 Turbo Upstream Upgrades

Created: 2026-08-21 19:15
Updated: 2026-08-21 19:24
Status: 🟡 In Progress

## Overview
Port and synchronize the latest upstream VieNeu-TTS v3 Turbo improvements into DocTruyenApk:
1. Expand from 8 to 20 curated voices across North/Central/South regions.
2. Add adaptive frame capping to eliminate hallucinations on short utterances.
3. Implement quote-aware dialogue segmentation with punctuation normalization.

## Tech Stack
- Kotlin 2.x / Android (API 24-35)
- ONNX Runtime Android (v1.20.0)
- MOSS Audio Tokenizer Nano Codec / 48kHz PCM AudioTrack
- sea-g2p Phonemizer (Rust NDK JNI)
- JUnit 4 / Android Unit Tests

## Phases

| Phase | Name | Status | Progress |
|-------|------|--------|----------|
| 01 | Update Preset Voices (20 Voices) & Asset Sync | ✅ Completed | 100% |
| 02 | Adaptive Frame Capping (Anti-Hallucination) in ONNX Engine | ✅ Completed | 100% |
| 03 | Smart Quote/Bracket Awareness & Punctuation Normalization | ⬜ Pending | 0% |

## Verification Strategy
- Each phase is paired with **exactly one comprehensive file-based unit test**.
- After executing a phase, only that single test is executed to verify core functionality before pausing for review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
