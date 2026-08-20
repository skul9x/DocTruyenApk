# Changelog - DocTruyen Android App

## [2026-08-20] - Tri-Layer Concurrency Defense & Activity Multi-Instance Fix
### Added
- **Layer 1: SingleTop Navigation (`AndroidManifest.xml` & `StoryDetailActivity`)**:
  - Configured `android:launchMode="singleTop"` for `StoryDetailActivity` to eliminate duplicate activity instances on repeated clicks or notification returns.
  - Implemented `onNewIntent()` with explicit state reset (`loadedStoryId = 0`, `isPrewarmTriggered = false`, `storyContent = ""`) and automatic reload when switching stories.
- **Layer 2: ReadingService Idempotency Guard (`ReadingService.kt`)**:
  - Added atomic state check (`preparedStoryId`, `preparedVoice`, `isPreparingStory`) inside `prepareStory()` to skip redundant background ONNX pre-warming jobs.
- **Layer 3: Content Cache & In-Flight Network Deduplication (`StoryDetailActivity.kt`)**:
  - Added `loadedStoryId` content cache guard in `loadStoryContent()` skipping network calls if identical story content is already loaded.
  - Added `isLoadingContent` in-flight mutex to avoid concurrent duplicate requests during configuration changes or rapid UI interactions.
- **Verification & Test Coverage**:
  - Added 3 comprehensive test suites: `StoryDetailSingleTopTest.kt`, `ReadingServicePrepareGuardTest.kt`, and `StoryDetailContentCacheGuardTest.kt` (100% passed).
  - Verified on physical hardware (`log6.txt`): network detail request count dropped from 5 to 1, seamless pre-warming, and 0 buffer underruns.

## [2026-08-20] - User-Selectable Model Precision & Real-Time Dynamic Switching
### Added
- **User Precision Selector UI (`SettingsActivity` & `activity_settings.xml`)**:
  - Intuitive `cardModelPrecision` with `MaterialButtonToggleGroup` for instant switching between `⚡ INT8 (Nhanh & Mát máy)` and `🎯 FP32 (Chất lượng gốc)`.
  - Dynamic explanatory badges indicating expected speedup, RTF, RAM savings, and thermal characteristics.
  - Automatic visibility toggling: visible when VieNeu TTS is selected, hidden when Google TTS is active.
- **Dynamic Hot-Reloading & Intent Routing (`ReadingService` & `VieNeuTTSManager`)**:
  - `ACTION_SWITCH_PRECISION` intent propagation enabling on-the-fly model switching without service or application restarts.
  - Automatic `PrewarmedChunkCache` purging upon precision changes to ensure cache consistency.
  - Persistent precision preference storage via `UserConfig.KEY_TTS_PRECISION` (Default: `INT8`).
- **On-Device Performance Verification (`log3.txt` vs `log2.txt`)**:
  - **30.2% RAM Reduction**: Native Heap decreased from 1,148 MB (FP32) to 802 MB (INT8).
  - **Inference Speedup**: Average RTF improved from 0.98x (FP32) down to 0.90x (INT8), with per-chunk peak RTF dropping from 1.28x to 0.93x.
  - 100% audio continuity with 0 buffer underruns across all test sessions.

## [2026-08-20] - Model Quantization, XNNPACK Integration & Adaptive Pre-Buffering
### Added
- **Multi-Precision Model Quantization & Detection (`OnnxSessionManager` & `tools/quantize_models.py`)**:
  - Python Dynamic INT8 quantization pipeline protecting acoustic convolution layers.
  - FP16 conversion pipeline targeting NNAPI / GPU Execution Providers.
  - Automatic quantization detection via ONNX `metadata_props` binary inspection with filename fallback.
  - Dedicated XNNPACK EP configuration for CPU inference (isolated ORT intra-op threads and big core thread allocation).
  - NNAPI EP configuration (`USE_FP16`) with graceful fallback for FP16 models.
- **Dynamic Duration-Based Adaptive Pre-Buffering (`TTSAudioPipeline`)**:
  - Replaced fixed chunk counts with 7500ms dynamic audio duration buffering threshold to eliminate audio underruns.
  - Full end-to-end `storyId` propagation throughout synchronous and pending speech initiation paths.
- **AI Performance Diagnostic Engine (`TtsMetricsLogger` & `DevicePerformanceHelper`)**:
  - System performance telemetry (RAM, SoC, thermal throttling, battery temperature).
  - 4-stage latency breakdown (G2P, Backbone, Codec, Audio/PCM).
  - AI automated heuristic flags and actionable tuning suggestions.

## [2026-08-19] - Natural TTS Story Cadence & Diagnostics (Phases 01–05)
### Added
- **Intelligent Text Segmentation (`SmartTextSegmenter`)**:
  - Context-aware pause tags: `TITLE_BREAK` (1000ms), `PARAGRAPH_BREAK` (650ms), `SENTENCE_BREAK` (400ms), `CLAUSE_BREAK` (180ms), `DIALOGUE_LEAD` (300ms).
  - Protection against incorrect sentence breaks on Vietnamese abbreviations (`TP.HCM`, `BS.`, `TS.`, `GS.`), decimals, and clock time formats.
- **Natural Silence Generation & Micro-Fading (`PcmUtils`)**:
  - Exact acoustic silence sample creation based on user config.
  - 5ms smooth half-cosine micro-fade on chunk boundaries eliminating waveform clicks/pops.
- **Granular Telemetry & Timing Logger (`TtsMetricsLogger`)**:
  - Automatic calculation of TTFT (Time to First Audio), RTF (Real-Time Factor), per-chunk G2P latency, inference latency, audio duration, and queue dwell time.
  - Formatted session summaries viewable directly in `SettingsActivity` -> `DebugLogActivity`.
- **Customizable Pause Cadence in Settings (`UserConfig` & `SettingsActivity`)**:
  - Presets: Fast, Standard, Emotional.
  - Interactive sliders with range clamping for sentence, paragraph, and clause pause lengths.
- **Hardware Benchmarking (`TTSNaturalReadingDeviceTest` & `benchmark_report.md`)**:
  - Full end-to-end verification of story 118 ("Một trí khôn hơn trăm trí khôn") on physical Android 15 device (OPPO Reno / CPH2691) with 100% test pass rate.
