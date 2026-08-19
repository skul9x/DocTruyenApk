# Phase 03: Natural Pause Audio Generation & Anti-Starvation Pipeline
Status: ✅ Completed
Dependencies: phase-01-timing-logger.md, phase-02-intelligent-segmentation.md

## Objective
Upgrade `TTSAudioPipeline` to generate exact acoustic silence buffers with smooth cross-fades between chunks, implement anti-starvation pre-buffering to eliminate awkward stuttering gaps, and connect full timing telemetry to `DebugLogger`.

## Requirements

### Functional Requirements
- [x] Implement silence PCM sample synthesis:
  - Calculate silence sample count: $N = \text{sampleRate} \times \frac{\text{pauseMs}}{1000}$.
  - Append micro-fade (5ms linear/half-cosine ramp) at end of chunk speech and start of next chunk speech to eliminate pop/click discontinuities.
- [x] Implement Anti-Starvation Pre-Buffering:
  - When starting or seeking, synthesis generates the first chunk and queues it immediately.
  - Before `AudioTrack` playback unblocks, ensure buffer contains at least 1 full synthesized chunk (or $\ge 1.5$ seconds of audio) so synthesis always stays ahead of playback.
  - In case synthesis falls behind (e.g. heavy CPU load), track starvation event: record exact starvation duration in milliseconds and log to `DebugLogger`.
- [x] Telemetry probes in Pipeline:
  - Log chunk synthesis start & finish with inference duration and RTF.
  - Log playback start & finish with pause duration and queue depth.
  - Log session summary at completion or stop.

### Non-Functional Requirements
- Zero pop/click acoustic artifacts at pause boundaries.
- Total audio playback smoothness: 0 underflows under normal mobile CPU conditions.

## Implementation Steps
1. [x] Update `TTSAudioPipeline.kt` to consume `List<TextChunk>` rather than raw string list.
2. [x] Add acoustic silence generator and micro-fading helper in `PcmUtils.kt`.
3. [x] Implement pre-buffering queue check in `startPipeline()`.
4. [x] Wire `TtsMetricsLogger` probes throughout `synthesisJob` and `playbackJob`.
5. [x] Update `VieNeuTTSManager.kt` to forward pause configurations and handle title pause seamlessly through `SmartTextSegmenter`.
6. [x] Implement file-based test suite `TTSNaturalAudioPipelineTest.kt`.

## Files to Create/Modify
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt` - [MODIFY] Support `TextChunk`, silence insertion, pre-buffering, and timing logs
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/PcmUtils.kt` - [MODIFY] Add `generateSilence()` and `applyFadeOutFadeIn()`
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt` - [MODIFY] Forward structured chunks and custom pause settings
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/TTSNaturalAudioPipelineTest.kt` - [NEW] File-based verification test suite

## Test Criteria (File-Based Tests)
- [x] `TTSNaturalAudioPipelineTest.kt` passes with 100% assertions on:
  - Accurate silence PCM sample counts for various pause types (180ms, 400ms, 650ms, 1000ms)
  - Micro-fading prevents waveform clipping at boundary samples
  - Pre-buffering threshold blocks until minimum audio queue duration is reached
  - Pipeline timing logs emitted to `DebugLogger` with `LogType.TTS_TIMING`

---
Next Phase: [phase-04-settings-configuration.md](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/plans/260819-0855-tts-timing-natural-pauses/phase-04-settings-configuration.md)
