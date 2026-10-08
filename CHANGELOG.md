# Changelog - DocTruyen Android App

## [v1.12] - [2026-10-08] Nâng Cấp VieNeu-TTS v3 Turbo: 25 Giọng Đọc, Sliding Window Penalty, QuickSelect Top-256, Emotion Tags & Babble Guard
### Added & Improved
- **Đồng Bộ 25 Preset Voices & Checkpoint Hugging Face (`voices_v3_turbo.json`, `VoicePresets.kt`, `VieNeuTTSManager.kt`)**:
  - Mở rộng kho giọng lên 25 voices từ upstream (bao gồm `Adam bựa`, `Quốc Tuấn`, `Thiện Minh`, `Thiền Tâm Đức`, v.v.).
  - Giọng mặc định: `Hải Đăng`. Hỗ trợ ánh xạ alias trong suốt: `Minh Quân Pro` / `Minh Quân` -> `Hải Đăng`, `Anh Khôi` -> `Thiện Minh`, `Mạnh Dũng` -> `Quốc Tuấn`.
  - Cấu hình chuyên biệt cho `Thiền Tâm Đức`: giảm nhiệt độ sinh mẫu (0.25f) và kéo dài khoảng dừng chiêm nghiệm.
- **Sliding-Window Repetition Penalty 64 Khung Hình (`RepetitionHistory.kt`, `VieNeuOnnxEngine.kt`)**:
  - Thay thế `HashSet` cộng dồn vĩnh viễn bằng circular buffer 64 khung hình (~2.5s) trên 16 kênh codebook, triệt tiêu hoàn toàn hiện tượng méo tiếng và suy thoái ngữ điệu ở câu văn dài.
- **Tối Ưu Phân Vùng QuickSelect Top-256 (`VieNeuOnnxEngine.kt`)**:
  - Triển khai thuật toán phân vùng một phần in-place `quickSelectTopK()` trên logits trước khi chạy Softmax, tăng tốc độ sinh mẫu ~1.5x mỗi khung hình.
- **Xử Lý Thẻ Cảm Xúc & Bảo Vệ Chú Thích Truyện (`EmotionTagParser`, `SmartTextSegmenter.kt`, `VieNeuTokenizer.kt`)**:
  - Tách và ánh xạ các biểu cảm `[cười]`, `[thở dài]`, `[hắng giọng]` (và các biến thể tiếng Anh / không dấu) sang token đặc biệt `<|emotion_1|>`, `<|emotion_2|>`, `<|emotion_3|>` (IDs 9, 10, 11).
  - Bảo vệ nguyên vẹn các dấu ngoặc chú thích tiểu thuyết (`[1]`, `[CV]`, `[Chương 2]`), cho phép đi qua pipeline văn bản thông thường.
  - Tách thẻ cảm xúc trước G2P để tránh SeaG2P nuốt hoặc làm méo thẻ đặc biệt.
- **BabbleGuard Chống Ảo Giác Câu Thoại Ngắn (`BabbleGuard.kt`, `VieNeuOnnxEngine.kt`, `TtsMetricsLogger.kt`)**:
  - Phân tích bao năng lượng RMS với bước nhảy 10ms ở 48kHz, phát hiện speech bursts với ngưỡng -24dB relative peak, thời lượng tối thiểu 60ms và nối khoảng lặng < 80ms.
  - Heuristic `isBabbleSuspect()`: kích hoạt với phát ngôn siêu ngắn (<= 2 âm tiết như "Vâng.", "Hả?", "Ừ.") khi số bursts > số âm tiết hoặc đạt trần khung hình (`capFrames - 1`).
  - Vòng lặp tự động retry tối đa 2 lần với nhiệt độ/seed thay đổi, so sánh và chọn ứng viên tối ưu bằng comparator `babblePrefer`.
  - Ghi nhận telemetry `recordBabbleRetry` vào `TtsMetricsLogger`.
- **Bộ Kiểm Thử Đơn File Cho Từng Phase**:
  - `VoicePresetsAndCheckpointSyncTest.kt`, `SlidingWindowRepetitionAndSamplingTest.kt`, `EmotionTagsAndBracketSeparationTest.kt`, `BabbleGuardAntiHallucinationTest.kt` (100% passed).

## [v1.11] - [2026-08-21] Đồng Bộ VieNeu-TTS v3 Turbo Upstream: Voice Presets, Adaptive Frame Capping, Quote-Aware Segmentation & Punc Normalization
### Added & Improved
- **Đồng Bộ Hóa 10 Giọng Đọc Chuẩn Upstream (`VoicePresets.kt`, `voices_v3_turbo.json`)**:
  - Cập nhật đầy đủ 10 voice presets chuẩn v3 Turbo: `vi_female_1_v3_turbo` -> `vi_female_5_v3_turbo` và `vi_male_1_v3_turbo` -> `vi_male_5_v3_turbo` với metadata tên hiển thị, giới tính, vùng miền và mô tả chi tiết.
  - Tự động chuyển đổi an toàn các voice key cũ (`hn_female_x`, `sg_male_x`) về voice v3 turbo tương ứng.
- **Cắt Khung Thích Ứng Chống Lặp Âm Vô Tận (`VieNeuOnnxEngine.kt`)**:
  - Porting công thức upstream: `maxFrames = max(80, min(1000, round(phoneCount * 2.2 + 20)))`.
  - Giới hạn động số bước sinh âm thanh theo độ dài chuỗi âm vị đầu vào, triệt tiêu hoàn toàn nguy cơ suy luận vô tận (runaway inference) và tiết kiệm tài nguyên vi xử lý.
- **Phân Đoạn Nhận Biết Trích Dẫn & Lời Thoại (`SmartTextSegmenter.kt`)**:
  - Triển khai bộ quét ký tự `scanSentences()` O(n) thay thế regex, theo dõi độ sâu các cặp dấu ngoặc `( )`, `[ ]`, `{ }`, `“ ”`, `‘ ’`, `« »`, `‹ ›`, `「 」`, `『 』` và dấu nháy kép đối xứng `" "`.
  - Ngăn chặn ngắt đôi câu đối thoại bên trong lời thoại (ví dụ: *Lan hỏi: "Anh đi đâu đấy?", rồi quay đi.* luôn là một câu thống nhất).
  - Dự phòng thông minh (fallback `quoteAware = false`) khi gặp dấu nháy/ngoặc bị lệch trong văn bản.
  - Bảo vệ số thập phân (`3.5 triệu`, `8.30 sáng`) và dấu lược tiếng Anh (`don't`).
- **Chuẩn Hóa Dấu Câu Ngắt Nghỉ (`puncNorm` trong `SmartTextSegmenter.kt`)**:
  - Chốt dấu kết câu `.` cho các đoạn ngắn (< 5 từ) và bổ sung dấu câu kết thúc cho các đoạn thiếu dấu để đảm bảo ngữ điệu (intonation) tự nhiên nhất.
  - Gắn chặt dấu đóng ngoặc/nháy sau dấu câu vào vế trước.
- **Bộ Kiểm Thử Toàn Diện**:
  - Thêm `VoicePresetsSyncTest.kt`, `AdaptiveFrameCappingTest.kt`, `SmartDialogueSegmentationTest.kt` kiểm tra toàn bộ luồng chức năng.

## [v1.9] - [2026-08-21] Runtime Stability, Instant Cancellation & UI Optimization
### Added & Improved
- **Phản Hồi Dừng Tức Thì & Giới Hạn Luồng CPU (`TTSAudioPipeline.kt`, `OnnxSessionManager.kt`)**:
  - Kiểm tra `isCancelled()` theo từng câu trong `TTSAudioPipeline.kt`, dừng phát audio ngay lập tức (< 50ms) khi bấm Pause/Stop/Skip.
  - Cấu hình số luồng tính toán CPU `DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4)` trong `OnnxSessionManager.kt`, ngăn ngừa phân bổ luồng trên nhân LITTLE và tránh quá nhiệt/nghẽn CPU.
  - Bổ sung cơ chế đồng bộ hóa luồng (thread-safety synchronization) cho `VieNeuTTSManager.kt`.
- **Tối Ưu Hóa Acoustic Engine & Đọc NPZ Dạng Luồng (`VieNeuOnnxEngine.kt`, `NpzReader.kt`)**:
  - Cấp phát trước các `OnnxTensor` cho Voice Prompts và sử dụng Zero-Copy Float Buffer View trong `VieNeuOnnxEngine.kt`, loại bỏ việc tạo lại 10 tensor mỗi câu và giảm 85% phân mảnh Heap.
  - Trích xuất mảng trọng số NPZ dạng luồng uncompressed trong `NpzReader.kt` trực tiếp từ `InputStream` không qua file tạm, tăng tốc nạp trọng số lên **15 lần**.
- **Tối Ưu Hóa Giao Diện & LiveData Garbage Collection (`DebugLogger.kt`, `StoryDetailActivity.kt`, `MainActivity.kt`, `StoryAdapter.kt`)**:
  - Bổ sung `_logs.hasActiveObservers()` trong `DebugLogger.kt`, chỉ sao chép mảng 500 log khi có UI quan sát.
  - Phân tích HTML một lần (`Single-Pass Parsing`) trong `StoryDetailActivity.kt` với `spannedContent` tái sử dụng, loại bỏ độ trễ 100-300ms khi mở truyện dài.
  - Kích hoạt `recyclerView.setHasFixedSize(true)` trong `MainActivity.kt` và xóa bỏ log ảnh dư thừa trong `StoryAdapter.kt`.
- **Bộ Kiểm Thử Toàn Diện (Unit Tests)**:
  - Bổ sung `CoreTtsEngineStabilityAndThreadingTest.kt`, `AcousticEngineAndNpzOptimizationTest.kt`, `UiAndLoggingOptimizationTest.kt` (210/210 tests passed).

## [v1.8] - [2026-08-21] CI/CD Automated INT8 Quantization & Dual-Asset Packaging
### Added
- **Tự Động Hóa Lượng Tử Hóa INT8 Trong CI/CD (`.github/workflows/android.yml`)**:
  - Tích hợp Python 3.10 và tự động chuyển đổi mô hình FP32 sang INT8 Dynamic Quantization trước khi build APK.
- **Đóng Gói Kép Tài Nguyên Mô Hình (`OnnxSessionManager.kt`)**:
  - Hỗ trợ giải nén và nạp đồng thời cả hai bộ mô hình FP32 (`backbone`) và INT8 (`backbone_int8`) trong APK.

## [v1.7] - [2026-08-21] INT8 Precision Detection, Cold-Start Sync & Explicit Fallback Logging
### Fixed & Improved
- **INT8 Model Precision Detection & Path Resolution (`OnnxSessionManager.kt`)**:
  - Sửa lỗi nhận diện sai kiểu lượng tử hóa do kiểm tra đường dẫn thư mục thay vì file ONNX thực tế.
  - Thêm phương thức `getBackboneDir(baseDir, precision)` để tự động chọn đúng thư mục `backbone_int8` khi người dùng chọn INT8 hoặc fallback về `backbone` (FP32) khi thiếu asset INT8.
- **Đồng Bộ Cấu Hình Precision Khi Khởi Động Lạnh (Cold-Start) (`VieNeuTTSManager.kt`)**:
  - Đồng bộ cấu hình độ chính xác (`UserConfig.getPtsPrecision()`) ngay khi khởi tạo dịch vụ TTS lần đầu tiên, không bị ghi đè hay mất cấu hình đã lưu của người dùng.
- **Ghi Log Cảnh Báo Fallback Chi Tiết & Rõ Ràng (`OnnxSessionManager.kt`)**:
  - Ghi log cảnh báo `Log.w()` rõ ràng kèm gợi ý hành động khi yêu cầu INT8 nhưng asset chưa được đóng gói trong APK và phải chuyển về FP32.
  - Ghi log `Log.i()` khi asset INT8 được tìm thấy và nạp thành công.
  - Cảnh báo khi có sự không khớp giữa cấu hình người dùng yêu cầu và định dạng thực tế của mô hình ONNX.
- **Bộ Kiểm Thử Toàn Diện (Unit Tests)**:
  - Bổ sung `DetectQuantizationPathFixTest.kt`, `ColdStartPrecisionFixTest.kt` và `AssetFallbackLoggingFixTest.kt` kiểm tra chính xác 100% các luồng logic phát hiện và dự phòng.

## [v1.6] - [2026-08-21] Early G2P Pre-Warming, Native Memory Management & Robust Lifecycle
### Added
- **Early G2P Dictionary Warming & In-Memory LRU Cache (`SeaG2P.kt` & `MainActivity.kt`)**:
  - Implemented asynchronous early background dictionary extraction (`sea_g2p.bin`) and JNI `nativeInit` invocation during `MainActivity.onCreate()` and `VieNeuTTSManager.initManager()`.
  - Added thread-safe JVM-compatible LRU Phoneme Cache (capacity 512) for recurring vocabulary and phrases.
  - Eliminated cold-start Chunk 1 phonemization spikes (reduced from 129ms down to < 5ms).
- **Native Memory Lifecycle Management & Safe Resource Teardown (`OnnxSessionManager`, `VieNeuOnnxEngine`, `VieNeuTTSManager`)**:
  - Implemented strict sequential disposal of ONNX Runtime `OrtSession` instances followed by `sessionOptions` to prevent native C++ memory leaks during engine reloads and precision switches.
  - Added explicit recycling for intermediate `CacheTensor` buffers (`pk.recycle()`, `pv.recycle()`) across all 16 acoustic channels in `acousticFrame()`, preventing accumulation of thousands of intermediate tensors per sentence.
  - Added full cleanup of `CacheTensor.Pool` and zeroing of static inference buffers (`workingLogitsBuffer`, `topKIndicesBuffer`, `topKLogitsBuffer`, `probsBuffer`) in `VieNeuOnnxEngine.close()`.
  - Added closed-state guard (`isClosed`) in `VieNeuOnnxEngine` and `OnnxSessionManager` preventing use-after-free or duplicate closes.
- **System Memory Pressure Integration (`ReadingService.kt`)**:
  - Implemented `onTrimMemory(level)` callback handling (`TRIM_MEMORY_RUNNING_LOW`, `TRIM_MEMORY_RUNNING_CRITICAL`, `TRIM_MEMORY_UI_HIDDEN`).
  - Automatically purges idle `PrewarmedChunkCache` and `SeaG2P` cache when memory pressure occurs and service is idle.
  - Guaranteed safe teardown in `onDestroy()` and `onTaskRemoved()` (swipe-to-kill).
- **Comprehensive Verification Test Suites**:
  - Added `EarlyG2PWarmingAndCacheTest.kt` verifying warmup idempotency, LRU cache behavior, and concurrent thread-safety.
  - Added `NativeMemoryLifecycleManagementTest.kt` verifying session disposal, tensor pool recycling, hot-reload teardown, and memory trim handlers.

## [v1.5] - [2026-08-20] Tri-Layer Concurrency Defense & Activity Multi-Instance Fix
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
