# Phase 01: Intelligent Text Chunking Optimization & UI State Fix

Status: ✅ Completed
Dependencies: None

## Objective
1. Optimize Vietnamese text segmentation in `SmartTextSegmenter.kt` by tuning default max character length from 180 to ~75 characters, enabling sub-clause splitting along punctuation marks (`,`, `;`, `:`, `—`, `–`, `-`) for lengthy sentences while protecting abbreviations and dialogue formatting.
2. Fix UI state flickering and race conditions in `VieNeuTTSManager.kt` during asynchronous initialization when speech requests are queued in pending state.

## Requirements
### Functional
- [x] Update `DEFAULT_MAX_CHARS` in `SmartTextSegmenter.kt` to 75 characters.
- [x] Enhance `splitSentenceIntoSubClauses` to prioritize splitting on natural Vietnamese clause boundaries (comma, semicolon, em-dash, colon) when length exceeds 70-80 characters, preserving trailing punctuation and natural cadence pause types (`CLAUSE_BREAK`, `SENTENCE_BREAK`, `PARAGRAPH_BREAK`, `DIALOGUE_LEAD`).
- [x] Verify that no speech chunk exceeds 80 characters across diverse Vietnamese literature paragraphs.
- [x] In `VieNeuTTSManager.initManager()`, check whether `pendingContent != null` or `pendingTitle != null` before broadcasting `TTSState.READY`. If speech is pending, transition directly into speech execution (`TTSState.PLAYING`) to prevent intermediate `READY` state UI flickers.

### Non-Functional
- [x] Zero audio artifacts or broken words across Vietnamese compound words and protected abbreviations (`TP.HCM`, `BS.`, `v.v.`, `PGS.TS.`).
- [x] Low-latency, deterministic segmentation running in under 2ms for full story chapters.

## Implementation Steps
1. Modify `SmartTextSegmenter.kt`:
   - Adjust `DEFAULT_MAX_CHARS` to 75.
   - Refine sub-clause splitting regex and clause boundary preservation.
2. Modify `VieNeuTTSManager.kt`:
   - Update `initManager()` callback flow to eliminate spurious `READY` state emission when pending speech is queued.
3. Create unit test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/OptimizedTextChunkingAndStateTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SmartTextSegmenter.kt` - Text segmenter with optimized chunk length bounds and sub-clause boundary rules.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - TTS Manager with race-free initialization state handling.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/OptimizedTextChunkingAndStateTest.kt` - Single test verifying chunk boundaries, pause assignments, and pending state transition flow.

## Test Criteria
- Exactly one test: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/OptimizedTextChunkingAndStateTest.kt`
- Verifies that complex Vietnamese texts with long sentences (100+ chars) are cleanly divided into chunks <= 80 chars.
- Verifies preservation of abbreviations, dialogue markers, and punctuation pauses.
- Verifies that `initManager()` transitions directly to `PLAYING` without intermediate `READY` callback when speech is pending.

---
Next Phase: [Phase 02: Automated ONNX Model Quantization Pipeline (FP16 / INT8)](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260820-1335-optimize-chunking-and-model-quantization/phase-02-model-quantization-pipeline.md)
