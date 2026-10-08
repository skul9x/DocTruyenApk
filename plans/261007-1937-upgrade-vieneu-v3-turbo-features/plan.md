# Plan: Upgrade VieNeu-TTS v3 Turbo Features & Engine

Created: 2026-10-07 19:37
Status: ✅ Completed

## Overview
Synchronize upstream VieNeu-TTS v3 Turbo improvements (2026-09 release) into DocTruyenApk:
1. Update to 25 preset voices (including `Adam bựa`, `Quốc Tuấn`, `Thiện Minh`, `Thiền Tâm Đức`, and default voice `Hải Đăng` with `Minh Quân Pro` alias support).
2. Add customized voice profile for `Thiền Tâm Đức` (low temperature, extended contemplative pauses).
3. Synchronize latest Hugging Face checkpoint (`2026-09-23`) and verify INT8 quantization pipeline.
4. Replace cumulative `HashSet` repetition penalty with a 64-frame sliding-window circular buffer (`RepetitionHistory`) to permanently resolve long-sentence voice drift/degradation.
5. Optimize per-frame inference using QuickSelect Top-256 partitioning prior to softmax, achieving ~1.5x sampling speedup.
6. Implement safe emotion tags parser (`[cười]`, `[thở dài]`, `[hắng giọng]`) with protection for novel brackets (`[1]`, `[CV]`).
7. Implement `BabbleGuard` energy burst detection to eliminate end-of-sentence noise and hallucinations on ultra-short dialogue utterances.

## Tech Stack
- Kotlin 2.0 / Android (API 24 - 35)
- ONNX Runtime Android (v1.20.0)
- MOSS Audio Tokenizer Nano Codec / 48kHz 16-bit PCM AudioTrack
- sea-g2p Phonemizer (Rust JNI)
- JUnit 4 / Android Unit Tests

## Phases

| Phase | Name | Status | Single File-Based Test | Progress |
|---|---|---|---|---|
| 01 | Voice Presets, Aliases, Profile & Checkpoint Sync | ✅ Completed | `VoicePresetsAndCheckpointSyncTest.kt` | 100% |
| 02 | Sliding-Window Repetition Penalty & Top-K Sampling | ✅ Completed | `SlidingWindowRepetitionAndSamplingTest.kt` | 100% |
| 03 | Emotion Tags Extraction & Novel Bracket Separation | ✅ Completed | `EmotionTagsAndBracketSeparationTest.kt` | 100% |
| 04 | Babble Guard Anti-Hallucination & Burst Detection | ✅ Completed | `BabbleGuardAntiHallucinationTest.kt` | 100% |

## Strict Verification Protocol
- Exactly **one comprehensive file-based test** is assigned per phase.
- Do NOT create or run more than one test per phase.
- After completing implementation for each phase, run ONLY that single test for verification.
- Stop immediately after running the test to await user review.

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
- Save context: `/save-brain`
