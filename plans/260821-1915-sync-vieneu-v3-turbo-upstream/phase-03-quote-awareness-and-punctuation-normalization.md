# Phase 03: Smart Quote/Bracket Awareness & Punctuation Normalization
Status: ✅ Completed
Dependencies: Phase 02

## Objective
Upgrade `SmartTextSegmenter.kt` with quote/bracket depth awareness (porting upstream `_scan_sentences()` logic) to prevent breaking sentences inside dialogue quotes, and implement chunk-level punctuation normalization (`punc_norm`) as pure Kotlin logic to ensure natural intonation.

## Requirements
### Functional
- **Replace `SENTENCE_SPLIT_REGEX` with a character scanner**: The current regex `(?<=[.!?…])\s+` at line 202 fundamentally cannot track bracket depth or in-quote state. Replace the sentence-splitting step (line 105: `trimmedPara.split(SENTENCE_SPLIT_REGEX)`) with a new `scanSentences(text: String): List<String>` function that ports the upstream `_scan_sentences()` character-by-character scanner.
- Bracket and quotation tracking in the new scanner:
  - Track open/close pairs: `( )`, `[ ]`, `{ }`, `" "`, `' '`, `« »`, `‹ ›`, `「 」`, `『 』`, and symmetric `"` quotes.
  - Sentence-ending punctuation (`.!?…`) inside quotes/brackets must not trigger sentence breaks.
  - Example: *Lan hỏi: "Anh đi đâu đấy?", rồi quay đi.* must remain as ONE sentence, not split at `?`.
- Fallback for unbalanced quotes: If the scanner detects unbalanced brackets (missing close), re-scan with `quoteAware = false` to prevent a stray quote from swallowing the rest of the text as one giant sentence (matches upstream behavior).
- Trailing close characters (`"`, `'`, `)`, `]`, `}`, `»`, `›`, `」`, `』`) after terminal punctuation bind to the preceding sentence, not the next one.
- **Punctuation Normalization (`punc_norm`)** — implemented as pure Kotlin in `SmartTextSegmenter.kt` (not via JNI native layer, since it operates at chunk boundary level after segmentation):
  - Short chunks (< 5 words) are guaranteed to end with a terminal period `.`.
  - Chunks missing terminal punctuation (`.`, `!`, `?`, `…`, `,`) automatically append a period `.` at the chunk boundary.
  - Attached closing quotes or brackets (e.g., `bao giờ?"`, `thế à!)`) stay bound with the preceding sentence.

> Note: `CLAUSE_SPLIT_REGEX` at line 203 remains unchanged — it handles sub-clause splitting within individual sentences and doesn't need quote awareness.

### Non-Functional
- Maintain single-pass O(n) scanning efficiency in the new `scanSentences()` function.

## Implementation Steps
1. Add `scanSentences(text: String, quoteAware: Boolean = true): Pair<List<String>, Boolean>` method in `SmartTextSegmenter.kt` porting upstream `_scan_sentences()`.
2. Replace `trimmedPara.split(SENTENCE_SPLIT_REGEX)` at line 105 with `scanSentences(trimmedPara).first`.
3. Add `puncNorm(chunk: String): String` utility method for chunk-level terminal punctuation enforcement.
4. Apply `puncNorm()` to each chunk's text before emitting `TextChunk`.
5. Create and run exactly one comprehensive unit test `SmartDialogueSegmentationTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SmartTextSegmenter.kt` — **[MODIFY]** Replace regex sentence splitting with character scanner; add `puncNorm()`.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/SmartDialogueSegmentationTest.kt` — **[NEW]** Comprehensive unit test for nested dialogue quotes, unbalanced quote fallback, and punc_norm.

## Test Criteria
- [x] Exactly one file-based unit test `SmartDialogueSegmentationTest.kt` passes, validating that dialogue quotes are never prematurely split, unbalanced quotes fall back gracefully, and terminal punctuation is properly normalized.

---
End of Plan
