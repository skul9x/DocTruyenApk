# Phase 03: Emotion Tags Extraction & Novel Bracket Separation

Status: ✅ Completed
Dependencies: Phase 02

## Objective
Implement pre-normalization emotion tag extraction (`[cười]`, `[thở dài]`, `[hắng giọng]`) in the text processing pipeline, mapping them to special token IDs (`<|emotion_1|>`, `<|emotion_2|>`, `<|emotion_3|>`) while shielding arbitrary novel annotations (`[1]`, `[CV: ...]`, `[Chương 2]`) from corruption.

## Requirements
### Functional
- [x] Implement `EmotionTagParser` in `SmartTextSegmenter.kt` / `VieNeuTokenizer.kt`:
  - Identify recognized emotion cues:
    - `[cười]`, `[cuoi]`, `[chuckle]` -> mapped to `<|emotion_1|>` (token ID 9).
    - `[thở dài]`, `[tho dai]`, `[sigh]` -> mapped to `<|emotion_2|>` (token ID 10).
    - `[hắng giọng]`, `[hang giong]`, `[clear throat]` -> mapped to `<|emotion_3|>` (token ID 11).
  - Unrecognized bracketed spans (`[1]`, `[Xem chú thích]`, `[CV: ...]`) must NOT be converted to emotion tokens. They must pass through as regular text so story dialogue and footnotes remain intelligible.
- [x] Isolate emotion tokens prior to G2P normalization:
  - Since `sea-g2p` normalizer strips or phonemizes brackets, the pre-processor must split the string around emotion tokens, phonemize only the natural text segments, and re-splice `<|emotion_k|>` intact.
- [x] Update `VieNeuTokenizer.kt`:
  - Map `<|emotion_1|>`, `<|emotion_2|>`, `<|emotion_3|>` directly to their respective vocabulary token IDs (IDs 9, 10, 11).
  - Handle surrounding punctuation attachment rules without introducing spurious whitespace.

### Non-Functional
- [x] Safe text processing: Malformed brackets (e.g. unclosed `[` or nested `[[...]]`) do not crash or throw exceptions.
- [x] Zero settings overhead: Operates natively without requiring an additional toggle switch in the UI.

## Implementation Steps
1. [x] Create emotion tag regex matcher and token dictionary in `SmartTextSegmenter.kt`.
2. [x] Integrate splitting and re-splicing around `SeaG2P.phonemize()` calls in `VieNeuTTSManager.kt`.
3. [x] Verify special token handling in `VieNeuTokenizer.kt`.
4. [x] Write and execute the single verification test: `EmotionTagsAndBracketSeparationTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SmartTextSegmenter.kt` - Bracket distinction & emotion tag extraction.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTokenizer.kt` - Special token encoding for `<|emotion_k|>`.
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - Pre-G2P splicing pipeline.
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/EmotionTagsAndBracketSeparationTest.kt` - Single comprehensive unit test.

## Single File-Based Test Criteria
- File: `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/EmotionTagsAndBracketSeparationTest.kt`
- Test Scenarios:
  1. `testEmotionTagTokenEncoding`: Verify that `[cười]`, `[thở dài]`, and `[hắng giọng]` correctly resolve to tokens 9, 10, and 11.
  2. `testNovelFootnoteBracketsNotCorrupted`: Assert that `"Chương 1 [1] hắn cười [cười]"` treats `[1]` as text and `[cười]` as emotion token 9.
  3. `testG2PBypassIntegrity`: Verify that `sea-g2p` phonemizes only the text portions and preserves `<|emotion_k|>` special tokens intact.
  4. `testPunctuationAttachment`: Verify that trailing punctuation (`[thở dài].`) remains attached without synthetic silence gaps.

## Review Checkpoint
Stop after running `EmotionTagsAndBracketSeparationTest.kt` and await user review before proceeding to Phase 04.
