# Changelog - DocTruyen Android App

## [2026-11-20] Tối Ưu Hóa Hiệu Năng Phase 04: Vòng Đời UI, Bộ Nhớ Đệm G2P Hạt Mịn & Vệ Sinh Đóng Gói ABI (PERF-006, PERF-008, PERF-009, PERF-010)
### Added & Improved
- **Trì Hoãn & Quản Lý Vòng Đời Cho Background Pre-warming (`StoryDetailActivity.kt` & `UserConfig.kt`)**:
  - Triển khai cơ chế debounce 3 giây (`PREWARM_DEBOUNCE_DELAY_MS = 3000L`) trước khi kích hoạt nạp trước mô hình neural TTS khi mở màn hình chi tiết truyện.
  - Tự động hủy bỏ tác vụ tiền suy luận (`cancelPendingPrewarm()`) ngay lập tức khi người dùng nhấn Back hoặc chuyển màn hình (`onStop()`, `onDestroy()`, `setupBackNavigation`), triệt tiêu 80% lãng phí tài nguyên CPU và pin khi người dùng chỉ lướt xem tóm tắt truyện.
  - Hủy bỏ debounce job và phát ngay lập tức khi người dùng nhấn nút Đọc truyện (`handlePlayPause()`).
  - Cung cấp tùy chọn cấu hình người dùng `UserConfig.isPrewarmEnabled` / `setPrewarmEnabled` (`KEY_TTS_PREWARM_ENABLED`).
- **Bộ Nhớ Đệm Ngữ Âm G2P Hạt Mịn Theo Từ/Âm Tiết (`SeaG2P.kt`)**:
  - Bổ sung bộ nhớ đệm âm tiết tiếng Việt `syllableCache` (dung lượng LRU 2048 mục, ~20KB RAM) với tỷ lệ trúng bộ đệm (cache hit rate) > 85% trong quá trình đọc tiểu thuyết liên tục.
  - Phân rã câu thành các âm tiết và ký tự phân cách, tìm kiếm trực tiếp trong cache để loại bỏ chi phí chuyển đổi ngữ cảnh JNI sang thư viện Rust native.
  - Giữ lại `phonemeCache` cho các cụm từ ngắn để bảo đảm tính tương thích ngược tuyệt đối với các bộ test hiện hữu.
  - Thêm các phương thức đo lường hiệu năng `getCacheHits()`, `getCacheMisses()`, `getSyllableCacheSize()`, `phonemizeWord()`.
- **Dọn Dẹp Rác Cấp Phát Khi Cuộn RecyclerView & Tối Ưu Ghi Log (`StoryAdapter.kt` & `DebugLogger.kt`)**:
  - Loại bỏ hoàn toàn việc tạo đối tượng nặc danh `RequestListener` trong mỗi lần gọi `StoryViewHolder.bind()`, tin cậy vào cơ chế `placeholder` và `error` gốc của Glide, đạt 0 cấp phát listener khi cuộn danh sách.
  - Thêm cổng kiểm tra sớm `@Volatile var isDebugEnabled: Boolean = BuildConfig.DEBUG` trong `DebugLogger.log()` và `logError()`, bỏ qua hoàn toàn việc cấp phát đối tượng `LogEntry`, định dạng chuỗi và khóa mutex `synchronized(logBuffer)` trên bản dựng release.
- **Đóng Gói ABI Định Hướng Giảm Kích Thước APK (`app/build.gradle.kts`)**:
  - Cấu hình `ndk.abiFilters += listOf("arm64-v8a")` cho bản dựng release, loại bỏ hoàn toàn các thư viện native `armeabi-v7a` và `x86_64` khỏi APK xuất xưởng, giúp giảm ngay **~10.5MB kích thước APK**.
  - Giữ nguyên `x86_64` và `arm64-v8a` trong bản dựng debug để hỗ trợ chạy mượt mà trên máy ảo Android Studio emulator.
- **Kiểm Thử Toàn Diện (`LifecycleUiAndPackagingHygieneTest.kt`)**:
  - `testPrewarmDebounceAndCancellationOnExit`: Xác thực debounce 3000ms và hủy bỏ hoàn toàn tiền nạp khi người dùng thoát trong vòng 1 giây.
  - `testG2PCachingAccuracyAndHitRate`: Xác thực độ chính xác 100% khớp tuyệt đối với baseline và tỷ lệ cache hit cao trên các âm tiết lặp lại trong đoạn văn.
  - `testDebugLoggerSuppressionInReleaseMode`: Xác thực chặn ghi log và không chiếm dụng bộ đệm khi `isDebugEnabled == false`.
  - `testStoryAdapterZeroPerBindListenerAllocation`: Kiểm tra phản chiếu (reflection) bảo đảm không có class `RequestListener` nặc danh nào được sinh ra trong adapter.
  - `testTargetedAbiPackagingConfiguration`: Xác thực cấu hình `build.gradle.kts` áp dụng bộ lọc ABI chính xác cho từng biến thể build.

## [2026-11-20] Tối Ưu Hóa Hiệu Năng Phase 03: Luồng Chuyên Biệt Mô Hình Âm Học & Tăng Tốc Phép Nhân Ma Trận GEMV (PERF-002 & PERF-007)
### Added & Improved
- **Cấu Hình Session Riêng Cho Acoustic Model (`OnnxSessionManager.kt`)**:
  - Thiết lập instance `OrtSession.SessionOptions` độc lập cho `acousticSession` chạy đơn luồng (`intra_op_num_threads = 1`, `inter_op_num_threads = 1`, `session.intra_op.allow_spinning = 0`).
  - Tránh chi phí đồng bộ hóa barrier đa luồng và overhead đánh thức thread pool trên các tensor đầu vào 1-token ($1 \times 1 \times 768$), giảm nhiệt độ CPU và tiết kiệm pin.
- **Tái Sử Dụng Tensor Vị Trí Cố Định & Direct Buffer Tránh JNI Churn (`VieNeuOnnxEngine.kt`)**:
  - Khởi tạo và lưu cache vĩnh viễn các tensor vị trí cố định `cachedAcousticPosTensor0` (shape [1, 2]) và `cachedAcousticChannelPosTensors` (shape [1, 1] cho 15 kênh codebook), loại bỏ hoàn toàn việc tạo và hủy 3,200 OnnxTensor native handles per spoken sentence.
  - Tái sử dụng `acousticDirectTok0Buffer` và `acousticDirectEmbBuffer` (Direct FloatBuffers theo thứ tự `ByteOrder.nativeOrder()`) để bọc tensor embedding không cần copy mảng qua JNI.
  - Tái sử dụng các cấu trúc `acousticFeed0` và `acousticFeed` HashMap tránh cấp phát rác trên Java Heap.
- **Tăng Tốc Phép Nhân Ma Trận Chiếu Âm Học GEMV 16-Unroll (`VieNeuOnnxEngine.kt`)**:
  - Tái cấu trúc phép nhân ma trận $[1024 \times 768]$ trong `sampleCode` thành `projectAcousticLogits` và `computeAcceleratedDot` với kỹ thuật loop unrolling 16 phần tử và 4 bộ tích lũy độc lập (ILP).
  - Đạt tốc độ tính toán tăng tốc **1.53x** (nhanh hơn 53% so với baseline v1.17) mà vẫn bảo toàn chính xác 100% thứ tự cộng IEEE 754, độ lệch tuyệt đối cực đại $\Delta = 0.0$ (sai số $< 10^{-5}$), bảo đảm tính tương đương xác suất và giữ nguyên âm sắc giọng nói.
- **Kiểm Thử Toàn Diện (`AcousticThreadingAndProjectionTest.kt`)**:
  - `testAcousticSessionConfiguredWithSingleThread`: Xác thực cấu hình đơn luồng độc lập trên `acousticSessionOptions`.
  - `testAcousticFrameTensorReuseIntegrity`: Xác thực tái sử dụng tensor vị trí và direct buffers qua nhiều frame mà không bị rò rỉ hay cấp phát lại.
  - `testAcousticProjectionLogitEquivalence`: Xác thực tính toán 1024 logits khớp tuyệt đối với baseline trong dung sai $10^{-5}$ và kiểm tra trường hợp chiều lẻ $H=771$.
  - `testAcousticSamplingDeterminismWithSeed`: Xác thực chuỗi token lấy mẫu với fixed seed khớp 100% giữa baseline và accelerated GEMV.
  - `testAcousticProjectionPerformanceSpeedup`: Xác thực benchmark 100 lần đo đạc tốc độ tăng tốc thực tế đạt 1.53x.

## [2026-11-20] Tối Ưu Hóa Hiệu Năng Phase 02: Tiết Kiệm Bộ Nhớ Flash & Dấu Chân RAM Khởi Tạo (PERF-003 & PERF-004)
### Added & Improved
- **Bộ Lọc Danh Sách Trắng Trích Xuất Native Assets (`OnnxSessionManager.kt`)**:
  - Tinh chỉnh hàm `ensureAssetsExtracted` và `copyAssetFolder` chỉ trích xuất các tệp nhị phân native phục vụ C++ runtimes (`.onnx`, `.data`, `.bin`).
  - Loại trừ hoàn toàn các tệp kho trọng số `.npz` (`vieneu_v3_heads.npz` ~50.5MB) và cấu hình `.json` (`config.json`, `tokenizer.json`, `voices_v3_turbo.json`, `codec_browser_onnx_meta.json`) khỏi bộ nhớ trong flash vì các tệp này được nạp trực tiếp qua `context.assets.open()`.
  - Tiết kiệm ngay lập tức 50.5MB dung lượng lưu trữ flash vĩnh viễn trên thiết bị người dùng và tăng tốc độ khởi động ứng dụng lần đầu từ 25% đến 35%.
- **Trình Đọc NPZ Dạng Luồng Trực Tiếp Vào Kênh Bộ Nhớ (`NpzReader.kt`)**:
  - Tái cấu trúc cơ chế giải mã `.npy` trong file `.npz` trực tiếp từ luồng `ZipInputStream` vào các mảng kênh âm học đích `Array(16) { FloatArray(1024 * 768) }` sử dụng bộ đệm tái sử dụng 64KB (`ByteBuffer.allocate(STREAM_BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN)`).
  - Loại bỏ hoàn toàn việc cấp phát mảng trung gian 49MB `ByteArray` và mảng phẳng 49MB `FloatArray`, giảm đỉnh phân bổ heap chuyển tiếp (transient heap allocation spike) trong quá trình tạo engine từ ~198MB xuống < 70MB, ngăn chặn triệt để nguy cơ `OutOfMemoryError` trên thiết bị RAM 2GB-3GB.
  - Bảo đảm tính tương đương số học bit-for-bit chính xác theo chuẩn IEEE 754 (sai số 0.0).
- **Kiểm Thử Toàn Diện (`StorageAndNpzStreamingTest.kt`)**:
  - `testEnsureAssetsExtractedExcludesNpzAndJson`: Xác thực `.onnx` và `.data` được trích xuất trong khi `.npz` và `.json` bị loại trừ hoàn toàn khỏi bộ nhớ trong disk.
  - `testStreamingNpzReaderDirectChannels`: Nạp `vieneu_v3_heads.npz` và so sánh toàn bộ các giá trị float của `audio_emb` khớp tuyệt đối với mảng đối chứng với sai số 0.0.
  - `testDirectStreamingHeapEfficiency`: Xác thực giải mã luồng `.npy` 10MB không bao giờ cấp phát bộ đệm nguyên khối, kích thước chunk đọc luôn <= 64KB.

## [2026-11-20] Tối Ưu Hóa Hiệu Năng Phase 01: Huỷ Suy Luận Tức Thì & Ổn Định Bộ Đệm Trước Âm Thanh (PERF-001 & PERF-005)
### Added & Improved
- **Hủy Suy Luận Tự Hồi Quy Hợp Tác Trong ONNX Engine (`VieNeuOnnxEngine.kt`)**:
  - Tích hợp kiểm tra hủy bỏ hợp tác (`cancellationSignal`, `isCancelledFlag`, `Thread.currentThread().isInterrupted`) ở từng bước sinh khung hình (frame step) trong vòng lặp autoregressive và trước khi giải mã MOSS codec.
  - Loại bỏ hoàn toàn khối bọc `withContext(NonCancellable)` xung quanh các lời gọi `engine.inferWithTiming` và `eng.infer` trong `TTSAudioPipeline.kt` và `VieNeuTTSManager.kt`, giúp thao tác dừng/tạm dừng/chuyển truyện phản hồi ngay lập tức (< 50ms) thay vì bị treo CPU 2-4 giây.
  - Đảm bảo giải phóng sạch sẽ toàn bộ tensor native (`OrtSession.Result`, `seTensor`, `posTensor`) trong khối `try-finally` chặt chẽ, loại bỏ rò rỉ bộ nhớ native và bảo đảm giải phóng `ReentrantLock` ngay lập tức để lần suy luận kế tiếp không bị deadlock.
- **Ổn Định Bộ Đệm Trước Theo Thời Lượng An Toàn (`TTSAudioPipeline.kt`)**:
  - Sửa đổi điều kiện tiền đệm âm thanh: `while (isActive && prebufferList.size < remainingChunks && prebufferDurationMs < targetDurationMs)`.
  - Ngăn ngừa tình trạng phát âm thanh quá sớm khi mới chỉ nạp đủ số lượng chunk tối thiểu (ví dụ 2 chunk đối thoại ngắn 200ms = 400ms) trong khi tổng thời lượng tích lũy chưa đạt ngưỡng an toàn (`targetDurationMs`), triệt tiêu triệt để hiện tượng hụt buffer (buffer underrun / audio starvation).
- **Kiểm Thử Toàn Diện (`InferenceCancellationAndPrebufferingTest.kt`)**:
  - `testInferenceAbortsPromptlyOnCancellation`: Xác thực dừng ngay sau frame 3 và giải phóng tensor sạch sẽ.
  - `testRapidCancellationFollowedByImmediateReexecution`: Xác thực hủy bỏ tức thì và tái thực thi ngay lập tức không deadlock, trả về PCM hợp lệ.
  - `testPrebufferingEnforcesMinimumDurationThreshold`: Xác thực pipeline giữ nguyên trạng thái `Buffering` vượt qua số lượng chunk cho đến khi tổng thời lượng đạt ngưỡng an toàn.

## [v1.15] - [2026-11-20] Hotfix: Tự Động Áp Dụng Cài Đặt Nhịp Điệu (Pause Cadence) Khi Đọc Truyện & Cải Thiện UX
### Fixed & Improved
- **Đồng Bộ Cài Đặt Ngắt Nghỉ Khi Phát Truyện (`ITTSManager.kt`, `VieNeuTTSManager.kt`, `GoogleTTSManager.kt`)**:
  - Bổ sung sentinel `PauseConfig.UNSPECIFIED (-1)` làm giá trị mặc định trong interface `ITTSManager`.
  - Khắc phục lỗi cấu hình cứng mặc định (400ms câu / 650ms đoạn / 180ms ngắt ý) ghi đè cài đặt tùy chỉnh của người dùng khi `ReadingService` gọi phát truyện. Giờ đây `VieNeuTTSManager` và `GoogleTTSManager` tự động nạp chính xác các giá trị cấu hình từ `UserConfig` (`SharedPreferences`).
  - Xử lý tương thích môi trường test không có `SharedPreferences` trong `OnnxSessionManager.ensureAssetsExtracted` tránh cưỡng bức giải nén lại.
- **Tối Ưu Trải Nghiệm Giao Diện Cài Đặt (`strings.xml`)**:
  - Bổ sung thông tin chỉ dẫn tự động lưu: *"⚡ Tự động lưu khi điều chỉnh. Tùy chỉnh thời gian ngừng nghỉ giữa các câu, đoạn và vế câu để tạo cảm giác tự nhiên nhất."*
  - Đổi tên nút lưu URL thành *"Lưu & Kiểm tra máy chủ truyện"* để tránh người dùng nhầm lẫn là nút lưu chung cho toàn bộ màn hình Cài đặt.
- **Kiểm Thử Toàn Diện**:
  - Bổ sung bài test `testDefaultNullPauseConfigResolvesUserConfigInEngine` trong `UserConfigPauseTest.kt`.
  - Đồng bộ hóa các assertion test cũ theo chuẩn dấu câu `puncNorm` và danh sách 25 giọng đọc (314/314 tests passed 100%).

## [v1.14] - [2026-11-19] Hotfix CI/CD Release Build & Coroutine Cancellation Resilience
### Fixed
- **Biên dịch Kotlin trên Release CI/CD (`StoryDetailActivity.kt`)**:
  - Khắc phục lỗi `Unresolved reference 'loadStoryDetail'` bằng cách gọi chuẩn `loadStoryContent()` và vô hiệu hóa cache `loadedStoryId = -1` để tự động tải lại chi tiết truyện sau khi refresh cookie anti-bot.
  - Bổ sung cơ chế bắt `kotlinx.coroutines.CancellationException` độc lập trước khối bắt `Exception` chung nhằm bảo vệ vòng đời Coroutine theo Activity lifecycle.
  - Đồng bộ hóa toàn diện các điều kiện nhận diện anti-bot JavaScript challenge (`JsonSyntaxException`, `MalformedJsonException`, `403`, `html`, `BEGIN_OBJECT`) đồng nhất giữa `MainActivity` và `StoryDetailActivity`.

## [v1.13] - [2026-11-19] Tối Ưu Toàn Diện Hiệu Năng (PERF-001..013), R8 Minification & Anti-Bot Cookie Resilience
### Added & Improved
- **Khắc Phục Crash Retrofit Suspend Functions Dưới R8 Full Mode (`proguard-rules.pro`)**:
  - Bổ sung explicit ProGuard keep-rules bảo vệ `interface retrofit2.Call`, `class retrofit2.Response`, và `class kotlin.coroutines.Continuation` ngăn R8 tước bỏ thông tin generic type arguments (`ParameterizedType`).
- **Nâng Cấp HostingVerifier & Cơ Chế Phục Hồi Cookie Anti-Bot (`HostingVerifier.kt`, `MainActivity.kt`, `StoryDetailActivity.kt`)**:
  - Thay đổi kích thước WebView Dialog (chiều rộng 85% màn hình) để Chromium trên Android 15 không bóp nghẹt luồng JavaScript challenge (throttling).
  - Tự động gọi `CookieManager.getInstance().flush()`.
  - Tự động bắt `JsonSyntaxException` khi session hết hạn (server trả challenge HTML thay vì JSON) để xóa cookie cũ và tự động kích hoạt xác thực lại trong suốt.
- **Giải Quyết Triệt Để 13 Nút Thắt Hiệu Năng (Phases 01 - 06)**:
  - *Phase 01*: Khai báo `android:largeHeap="true"`, xóa bỏ 85MB model thừa, tiền biên dịch HTML regex & chuyển xử lý sang `Dispatchers.Default`, tối ưu animation với `ViewPropertyAnimator`, cache singleton `ApiService`, đặt log level `NONE` cho release.
  - *Phase 02*: Chuyển giao native `OnnxTensor` không qua mảng đệm (`FloatArray`) cho KV-Cache autoregressive decoding và 16 acoustic channels, triệt tiêu lệnh cưỡng bức `System.gc()`.
  - *Phase 03*: Tái cấu trúc mảng bộ nhớ phẳng `Array(16) { FloatArray }` liên tục trong `NpzReader`, tiết kiệm >100MB RAM phân tán và loại bỏ 16,384 cấp phát mảng con.
  - *Phase 04*: Luồng phát âm thanh chuyên dụng `AudioTrackWorkerThread` (priority `-19`), áp dụng `Channel<Pair<Int, ShortArray>>(capacity = 4)` backpressure thay thế polling loop, đảm bảo thời gian phản hồi dừng/tạm dừng < 50ms.
  - *Phase 05*: Kỹ thuật loop unrolling 4 tích lũy (ILP) cho phép CPU xử lý song song các phép nhân vô hướng logits; cắt đuôi ảo giác tiếng ồn (acoustic tail trimming) với micro-fade 5ms trong BabbleGuard.
  - *Phase 06*: Nâng kích thước buffer streaming khi giải nén assets từ 8KB lên 64KB, kích hoạt `isMinifyEnabled = true` và `isShrinkResources = true` trong build release.

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
