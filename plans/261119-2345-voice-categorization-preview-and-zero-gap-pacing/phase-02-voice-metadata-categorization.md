# Phase 02: Voice Metadata Categorization

Status: ✅ Completed
Dependencies: None

## Objective
Establish structured voice categorization data models and multi-faceted filtering logic (Gender, Region, Style) across all on-device VieNeu preset voices and Google TTS system voices.

## Scope
- Voice preset definitions (`VoicePresets.kt` & `VoicePreset`).
- Voice filtering data structures and query logic.
- Metadata badge extraction and human-readable formatting.
- Integration with both VieNeu neural voices and Google TTS available voices.

## Requirements
### Functional
- Formalize canonical categories:
  - **Gender:** All, Male (Nam), Female (Nữ).
  - **Region:** All, North (Miền Bắc), Central (Miền Trung), South (Miền Nam).
  - **Style:** All, Storytelling (Kể chuyện / Đọc truyện), Natural (Tự nhiên), News (Tin tức / Báo chí).
- Provide a query/filter engine that accepts active criteria for Gender, Region, and Style, returning the matching subset of voices.
- Correctly classify all 25 VieNeu presets in `voices_v3_turbo.json` into their respective categories based on `gender`, `region`, `style`, and `description`.
- Support Google TTS voice categorization by gender and locale where metadata is available.
- Provide formatted badge strings/tags for individual voice items (e.g. `[Nam]`, `[Miền Nam]`, `[Kể chuyện]`).
- Support resetting filters back to "All" to restore the complete list.

### Non-Functional
- Filtering must be instantaneous (<10ms) and allocation-efficient.
- String matching must be case-insensitive, whitespace-trimmed, and diacritics-resilient where applicable.
- Thread-safe access to voice presets and filtered queries.

## Constraints
- Do not modify binary voice embeddings, codes, or ONNX tensor structures.
- Retain full backward compatibility with `VoicePresets.availableVoices`, `VoicePresets.getVoice()`, and existing aliases.
- No external third-party dependencies.

## Dependencies
- Can be implemented independently; serves as the foundation for Phase 04.

## Acceptance Criteria
- Filtering by Gender returns only voices matching the specified gender.
- Filtering by Region returns only voices matching the specified region.
- Filtering by Style returns only voices matching the specified style.
- Combined multi-faceted filtering (e.g. Male + South + Storytelling) returns the exact intersection of matching voices.
- Passing "All" for all criteria returns all 25 VieNeu voices without omissions.
- Formatted metadata badges accurately reflect the voice attributes.
- Running the comprehensive file-based test passes with 100% success.

## Single File-Based Test
`app/src/test/java/com/skul9x/doctruyen/tts/VoiceCategorizationFilterTest.kt`

---
Next Phase: [Phase 03: Audio Preview Engine and Cache](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/plans/261119-2345-voice-categorization-preview-and-zero-gap-pacing/phase-03-audio-preview-engine-and-cache.md)
