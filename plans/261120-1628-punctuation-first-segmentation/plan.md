# Plan: Punctuation-First Natural Text Segmentation (Plan A)
Created: 2026-11-20 16:29 (GMT+7)
Status: ✅ Completed

## Overview
Implement **Plan A (Punctuation-First Lock)** in `SmartTextSegmenter.kt`. For well-punctuated novel and story content, speech audio synthesis continues seamlessly across words until valid natural punctuation marks (`,`, `.`, `!`, `?`, `…`, `:`, `;`, `—`) are encountered. Avoid mechanically chopping compound words (such as splitting "sẵn sàng" into "sẵn" and "sàng") while safely raising the emergency boundary limit to 200 characters. Preserve the user-specified punctuation normalization rule where a terminal period overlays a colon (`:.`).

## Architecture & Design Decisions
1. **Punctuation-First Segmentation**: Primary sub-clause segmentation relies strictly on linguistic punctuation boundaries (`[,;:—–-]` and terminal punctuation `[.!?…]`). Do not sub-split clauses mechanically by word counts if they fit within `SAFE_MAX_CHARS` (200 characters).
2. **Compound Word & Phrase Integrity**: Sub-clause splitting must never fracture Vietnamese words or multi-syllable collocations. Sentences up to 200 characters (comfortably handled by VieNeu-TTS v3 Turbo's 600-frame budget) generate as unified audio chunks.
3. **Punctuation Normalization Retention**: Preserve the existing pattern where terminal colons receiving punctuation normalization get a period overlaid (`:.`), maintaining established prompt cadence.
4. **Safety Ceiling**: If an abnormal sentence exceeds 200 characters without any punctuation, split cleanly on whitespace with compound word protection as a fallback safeguard.

## Phases

| Phase | Name | Status | Test File |
|---|---|---|---|
| 01 | Punctuation-First Segmenter Core Refactor | ✅ Completed | `PunctuationFirstSegmenterCoreTest.kt` |
| 02 | Pipeline, Pause Duration & Integration Verification | ✅ Completed | `PunctuationFirstPipelineIntegrationTest.kt` |

## Quick Commands
- Review Plan: Inspect markdown files in `plans/261120-1628-punctuation-first-segmentation/`

