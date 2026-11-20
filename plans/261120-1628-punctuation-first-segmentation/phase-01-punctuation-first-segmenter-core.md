# Phase 01: Punctuation-First Segmenter Core Refactor
Status: ✅ Completed
Dependencies: None

## Objective
Refactor `SmartTextSegmenter.kt` so that sentence chunking is governed strictly by natural punctuation boundaries (`,`, `.`, `!`, `?`, `…`, `:`, `;`, `—`, `–`). Remove mechanical 75-character mid-clause word-chopping, set `SAFE_MAX_CHARS = 200`, and guarantee that compound words like "sẵn sàng" remain intact while preserving the `:.` punctuation normalization behavior.

## Scope
- Modify `SmartTextSegmenter.kt` constants and clause splitting logic (`splitSentenceIntoSubClauses`).
- Update default chunk length thresholds to 200 characters (`DEFAULT_MAX_CHARS = 200`).
- Ensure `puncNorm` continues to output `:.` when encountering a colon followed by normalization.
- Ensure quote-awareness and bracket protection remain fully functional.

## Requirements
### Functional
1. **Punctuation Delimiter Priority**: Sub-clauses are extracted at punctuation boundaries (`[,;:—–-]`). A clause that does not exceed `maxChars` (200 characters) must NOT be split further.
2. **Compound Word Protection**: Clauses up to 200 characters (e.g., the 101-character sentence *"vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc và luôn sẵn sàng học hỏi từ người khác."*) must be returned as a single unbroken chunk ending with `.`.
3. **Punctuation Normalization Retention**: Chunks ending in `:` normalize to `:.` as required by the user specification.
4. **Emergency Word Break Safeguard**: Only clauses exceeding 200 characters without punctuation are broken at word boundaries, never in the middle of a token.

### Non-Functional
- Zero memory allocations for regex matching where precompiled patterns apply.
- Fast execution under 2ms per paragraph segmentation.

## How to Implement
1. In `SmartTextSegmenter.kt`:
   - Update `DEFAULT_MAX_CHARS` from `75` to `200`.
   - Update `splitSentenceIntoSubClauses`:
     - Split sentence by `CLAUSE_SPLIT_REGEX` (`(?<=[,;:—–-])\s+|(?<=[—–])(?=[^\s])`).
     - Accumulate clauses into sub-chunks as long as total length does not exceed `maxChars` (200).
     - When an individual clause itself exceeds `maxChars` (rare edge case > 200 chars), fall back to whitespace boundary splitting without splitting words.
   - Retain `puncNorm` behavior for `:` yielding `:.`.
2. Keep existing `EmotionTagParser` bypass and abbreviation placeholders intact.

## Files to Create / Modify
- **Modify:** `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SmartTextSegmenter.kt` - Update default max length and clause packing logic.
- **Create:** `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PunctuationFirstSegmenterCoreTest.kt` - Unit tests verifying punctuation-first chunking, no word-splitting, and `:.` retention.

## Dependencies
- Standard Kotlin JVM and JUnit 4.

## Constraints
- Do not break backward compatibility with `PcmUtils.splitIntoSentences` or `PcmUtils.splitIntoChunks`.
- Must pass all existing tests in `SmartTextSegmenterTest.kt` or adjust tests specifically asserting the deprecated 75-character cap.

## Acceptance Criteria
- Given the sentence: *"Bài học cho Minh Khôi: Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn. Không cần khoe mình giỏi, vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc và luôn sẵn sàng học hỏi từ người khác."*, `SmartTextSegmenter.segment(text)` produces exactly 4 chunks:
  1. `Bài học cho Minh Khôi:.`
  2. `Con hãy khiêm tốn và biết bình tĩnh suy nghĩ khi gặp khó khăn.`
  3. `Không cần khoe mình giỏi,`
  4. `vì sự thông minh thật sự là biết tìm cách giải quyết đúng lúc và luôn sẵn sàng học hỏi từ người khác.`
- Chunk 4 is completely intact (101 chars) and does NOT split at "sẵn" / "sàng".

## Comprehensive File-Based Test
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PunctuationFirstSegmenterCoreTest.kt`
- Test cases:
  1. `testLessonDialogueSegmentationIntegrity`: Verifies the 4 exact chunks from the user prompt.
  2. `testCompoundWordNeverSplit`: Verifies long clauses between 75 and 180 characters with words like "sẵn sàng", "khiêm tốn" remain intact.
  3. `testColonPunctuationNormalization`: Verifies `:` results in `:.`.
  4. `testEmergencyFallbackOver200Chars`: Verifies extreme clauses > 200 chars split cleanly at whitespace boundaries without breaking mid-token.

