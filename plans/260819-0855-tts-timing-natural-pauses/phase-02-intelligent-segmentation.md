# Phase 02: Context-Aware Smart Text Segmentation & Pause Rules
Status: ⬜ Pending
Dependencies: phase-01-timing-logger.md

## Objective
Replace basic regex text splitting with a linguistic, context-aware Vietnamese text segmenter (`SmartTextSegmenter`). It parses raw story text (including HTML tags, dialogue lines, paragraph breaks, and abbreviations) into structured chunks (`TextChunk`), assigning appropriate pause types and silence durations for natural prosody.

## Requirements

### Functional Requirements
- [ ] Define `PauseType` enum:
  - `TITLE_BREAK` (Default: 1000ms pause)
  - `PARAGRAPH_BREAK` (Default: 650ms pause after `\n\n` or `</p>`)
  - `SENTENCE_BREAK` (Default: 400ms pause after `.`, `!`, `?`, `…`)
  - `CLAUSE_BREAK` (Default: 180ms pause after `,`, `;`, `:`, `—`, `–`)
  - `DIALOGUE_LEAD` (Default: 300ms pause after introductory dialogue dash `- `)
- [ ] Create `TextChunk` data class:
  ```kotlin
  data class TextChunk(
      val index: Int,
      val text: String,
      val pauseType: PauseType,
      val pauseDurationMs: Int,
      val isParagraphEnd: Boolean
  )
  ```
- [ ] Implement `SmartTextSegmenter`:
  - Strips HTML markup while preserving structural paragraph breaks (`<p>`, `<br>`, `<div>`).
  - Protects Vietnamese abbreviations and numerals from false sentence breaks (e.g. "TP.HCM", "TS.", "ThS.", "BS.", "GS.", "v.v.", "1.000", "08:33").
  - Preserves dialogue markers (`- `, `“... ”`, `"..."`).
  - Limits chunk size safely ($\le 180$ characters) to keep inference fast and responsive while splitting only at valid sub-clause boundaries.

### Non-Functional Requirements
- Segmentation speed $\ge 50,000$ characters per millisecond.
- Clean text without trailing orphan punctuation or unbalanced quotation marks.

## Implementation Steps
1. [ ] Create `SmartTextSegmenter.kt` in `com.skul9x.doctruyen.tts.vieneu`.
2. [ ] Add abbreviation protection dictionary and regex rule sets.
3. [ ] Implement paragraph and sentence boundary tokenizer with context tagging.
4. [ ] Deprecate old simple split in `PcmUtils.kt` and wire `SmartTextSegmenter` into `PcmUtils.splitIntoChunks()`.
5. [ ] Implement comprehensive file-based test suite `SmartTextSegmenterTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SmartTextSegmenter.kt` - [NEW] Linguistic segmentation engine
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/PcmUtils.kt` - [MODIFY] Delegate chunking to `SmartTextSegmenter`
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/SmartTextSegmenterTest.kt` - [NEW] File-based verification test suite

## Test Criteria (File-Based Tests)
- [ ] `SmartTextSegmenterTest.kt` passes with 100% assertions on:
  - Paragraph detection with 650ms `PARAGRAPH_BREAK`
  - Vietnamese abbreviation non-splitting ("BS. Hùng khám tại TP. Hồ Chí Minh lúc 8.30 sáng.")
  - Dialogue formatting with `DIALOGUE_LEAD`
  - Clause punctuation handling (commas, semicolons, dashes)
  - Full story test using real text from `log.txt` ("Một trí khôn hơn trăm trí khôn")

---
Next Phase: [phase-03-natural-pause-audio-pipeline.md](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-0855-tts-timing-natural-pauses/phase-03-natural-pause-audio-pipeline.md)
