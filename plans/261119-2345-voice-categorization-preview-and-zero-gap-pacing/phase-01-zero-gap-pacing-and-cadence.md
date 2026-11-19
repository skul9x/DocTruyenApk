# Phase 01: Zero-Gap Pacing and Cadence

Status: ✅ Completed
Dependencies: None

## Objective
Enable seamless zero-gap cadence (0 ms pause duration) across sentence, clause, and paragraph boundaries in user settings, configuration persistence, and the TTS pipeline without breaking existing pause presets or causing audio clipping.

## Scope
- Configuration persistence (`UserConfig.kt`) bounds and constants.
- Settings Activity layout (`activity_settings.xml`) slider ranges and values.
- Settings Activity logic (`SettingsActivity.kt`) formatting and preset application.
- VieNeu TTS Manager (`VieNeuTTSManager.kt`) pause configuration validation and assignment.
- TTS Audio Pipeline (`TTSAudioPipeline.kt`) zero-gap concatenation verification.

## Requirements
### Functional
- Allow 0 ms as the minimum selectable value for `tts_pause_sentence`, `tts_pause_clause`, and `tts_pause_paragraph`.
- Update `sliderPauseSentence` to `valueFrom="0"` and adjust step sizes appropriately.
- Update `sliderPauseClause` to `valueFrom="0"` and adjust step sizes appropriately.
- Update `sliderPauseParagraph` to `valueFrom="0"` and adjust step sizes appropriately.
- Format pause UI value adaptively: display "0 ms (Liền mạch / Zero Gap)" when 0 ms is selected, and "$ms ms" when > 0 ms.
- Update condition checking in `VieNeuTTSManager` (`sentencePauseMs >= 0`) so that 0 ms is honored rather than falling back to default non-zero values.
- Provide a dedicated seamless reading preset (or update existing presets) configured for 0 ms sentence and clause gaps.

### Non-Functional
- Maintain 100% backward compatibility with existing saved user preferences.
- Audio synthesis and playback must not crash or produce divide-by-zero or negative array size errors when pause duration is 0 ms.
- Ensure audio chunk concatenation with 0 ms pause uses smooth micro-fades without audio pops or clicks.

## Constraints
- Do not remove or alter existing default preset keys ("STANDARD", "FAST", "EMOTIONAL").
- Keep maximum boundaries intact (`MAX_PAUSE_SENTENCE = 1000`, `MAX_PAUSE_PARAGRAPH = 1500`, `MAX_PAUSE_CLAUSE = 500`).
- Ensure all changes compile cleanly under targetSdk 35 and R8 full mode.

## Dependencies
- None.

## Acceptance Criteria
- Setting sentence pause to 0 ms in `UserConfig` persists and reads back 0 ms without being coerced to legacy 150 ms minimum.
- Setting clause and paragraph pauses to 0 ms persists and reads back 0 ms without coercion to legacy positive minimums.
- `VieNeuTTSManager.getEffectivePauseConfig()` preserves and returns `sentencePauseMs = 0` when configured.
- `SettingsActivity` pause sliders can be set to 0 and render the seamless adaptive text.
- Running the comprehensive file-based test passes with 100% success.

## Single File-Based Test
`app/src/test/java/com/skul9x/doctruyen/utils/ZeroGapPauseCadenceTest.kt`

---
Next Phase: [Phase 02: Voice Metadata Categorization](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/plans/261119-2345-voice-categorization-preview-and-zero-gap-pacing/phase-02-voice-metadata-categorization.md)
