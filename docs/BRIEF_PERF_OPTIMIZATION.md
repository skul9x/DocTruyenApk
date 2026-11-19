# 💡 BRAINSTORM & TECHNICAL BRIEF: Khắc Phục Toàn Diện Hiệu Năng DocTruyen
**Nguồn tham chiếu:** [audit_per.md](file:///home/skul9x/Desktop/Code/DocTruyenApk-main/audit_per.md)  
**Mục tiêu:** Khắc phục triệt để 13 vấn đề hiệu năng (P0, P1, P2), áp dụng chuẩn Best Practices quốc tế cho On-Device Neural TTS & Mobile Audio Streaming.  
**Ngày lập:** 19/11/2026

---

## 1. TỔNG HỢP VẤN ĐỀ & BEST PRACTICES QUỐC TẾ (RESEARCH FINDINGS)

### Nhóm 1: On-Device AI Inference & KV-Cache Management (PERF-001, PERF-002, PERF-008)
- **Vấn đề cốt lõi:** Vòng lặp autoregressive decode (200-300 frames/câu) hiện sao chép 24 tensors qua lại giữa native C++ và JVM heap qua mảng `FloatArray` (`CacheTensor.fromOnnxTensor`), gây phân bổ >3.2GB rác/câu, buộc phải gọi `System.gc()` định kỳ mỗi 100 frames. Tương tự, 16 kênh acoustic cũng lặp lại việc cấp phát này.
- **Industry Best Practice (ONNX Runtime Java / Mobile):**
  - **Zero-Copy Ping-Pong Result Chaining:** Trong ONNX Runtime Java API, `session.run()` trả về `OrtSession.Result` chứa các `OnnxTensor` native. Ta có thể tái sử dụng trực tiếp các tensor đầu ra này làm tham số `inputs` cho bước kế tiếp mà **không cần trích xuất về `FloatArray` trong Java Heap**.
  - **Quản lý vòng đời (Dual-Buffer / Ping-Pong):** Vì `Result.close()` sẽ giải phóng tất cả các tensor con của nó, mô hình chuẩn là giữ `currentResult` của frame $t-1$, nạp vào frame $t$ tạo ra `nextResult`, sau đó mới `close()` `currentResult` và gán `currentResult = nextResult`.
  - **Loại bỏ `System.gc()`:** Khi heap churn giảm từ 3.2GB về <10MB, áp lực rác hoàn toàn biến mất, triệt tiêu các đợt gián đoạn Stop-The-World (15-80ms).

### Nhóm 2: Memory Footprint & OOM Elimination (PERF-003, PERF-004, PERF-005)
- **Vấn đề cốt lõi:**
  - `NpzReader.get3D("audio_emb")` tạo 16,384 đối tượng `FloatArray(768)` riêng lẻ, cộng với bản sao phẳng `flatAudioEmbeddings`, chiếm cùng lúc 3 bản sao ~150MB trên heap.
  - Thiếu `android:largeHeap="true"` gây OOM tức thì trên máy RAM $\le 3\text{GB}$.
  - Đóng gói 85MB model chết trong assets (`denoiser.onnx` 41MB và `moss_audio_tokenizer_encode.*` 44MB) không dùng ở bất kỳ đâu.
- **Industry Best Practice:**
  - Thêm `android:largeHeap="true"` vào `AndroidManifest.xml`.
  - Tái cấu trúc `NpzReader`: Bỏ hoàn toàn mảng 3 chiều `Array<Array<FloatArray>>`. Nạp thẳng từ file zip vào mảng phẳng 1 chiều hoặc mảng theo kênh `Array<FloatArray>`, dùng offset toán học `(vocab * H + h)`. Tiết kiệm ngay >100MB RAM thường trú.
  - Xóa bỏ triệt để 85MB assets rác, giảm 85MB APK và 170MB dung lượng lưu trữ trên flash.
  - Tối ưu hóa bộ đệm sao chép file model từ 8KB lên 64KB/128KB và bổ sung kiểm tra version code để tự động cập nhật khi lên bản mới.

### Nhóm 3: Audio Streaming & Threading Concurrency (PERF-006, PERF-011)
- **Vấn đề cốt lõi:**
  - `AudioTrack.write(WRITE_BLOCKING)` chạy trực tiếp trên `Dispatchers.Default`, khóa chết 1 coroutine worker thread dành cho tính toán CPU trong nhiều giây, làm chậm phản ứng Pause/Stop.
  - Vòng lặp phát audio dùng `ConcurrentLinkedQueue` không giới hạn dung lượng và thăm dò tích cực bằng `delay(20)` (đánh thức CPU 50 lần/giây khi rảnh).
- **Industry Best Practice (Android AudioTrack & Kotlin Coroutines):**
  - **Dedicated Audio Thread:** Luôn chạy `WRITE_BLOCKING` trên một thread chuyên biệt có độ ưu tiên cao (`android.os.Process.THREAD_PRIORITY_AUDIO` hoặc `Executors.newSingleThreadExecutor("AudioTrackWorker")`). Điều này giải phóng hoàn toàn `Dispatchers.Default` cho AI synthesis.
  - **Kotlin Channel with Backpressure:** Thay thế `ConcurrentLinkedQueue` bằng `Channel<PlaybackChunk>(capacity = 3..4)`. Khi queue rỗng, consumer sẽ suspend thụ động (không tốn CPU, không cần `delay(20)`). Khi queue đầy, producer tự động hãm tốc độ sinh frame (Backpressure), ngăn tràn RAM.

### Nhóm 4: Toán học & Vectorization (PERF-007, PERF-013)
- **Vấn đề cốt lõi:**
  - Vòng lặp tính tích vô hướng ma trận acoustic $[1024 \times 768]$ chạy thuần Kotlin (2.5 tỷ phép tính FLOPs/câu) với 1 accumulator đơn, gây nghẽn pipeline CPU.
  - BabbleGuard phát hiện câu ngắn có nguy cơ babble thì retry toàn bộ 12-layer AR loop từ đầu 2 lần (làm chậm 3x).
- **Industry Best Practice:**
  - **Instruction-Level Parallelism (ILP) / Loop Unrolling & SIMD:** Unroll vòng lặp ma trận với 4-8 accumulators độc lập để tận dụng tối đa các đơn vị FMA (Fused Multiply-Add) song song của vi kiến trúc ARM Cortex. Tùy chọn nâng cao: ủy thác phép nhân ma trận cho Rust NDK (`libsea_g2p_android.so`) có cờ vector hóa tự động NEON.
  - **Acoustic Tail Trimming:** Trong `BabbleGuard`, dùng thông tin burst energy envelope đã tính toán để cắt tỉa (trim) phần đuôi hallucination dư thừa trực tiếp trên mảng PCM, chỉ retry nơ-ron nếu không tìm thấy burst hợp lệ.

### Nhóm 5: UI Responsiveness, Network & Build Hygiene (PERF-009, PERF-010, PERF-012)
- **Vấn đề cốt lõi:**
  - `StoryDetailActivity` chạy 5 `Regex` (biên dịch lại mỗi lần) và `Html.fromHtml` trên Main UI Thread gây lag giật khi mở chương truyện lớn.
  - `StoryAdapter` nạp XML animation `fade_in` trong mỗi lần `onBindViewHolder`.
  - `ApiService` in headers trong bản release và tạo dynamic proxy lặp lại.
  - Tắt R8 Minification (`isMinifyEnabled = false`) và thư viện `.so` chưa strip debug symbols.
- **Industry Best Practice:**
  - Chuyển toàn bộ Regex làm sạch HTML và `Html.fromHtml` sang `withContext(Dispatchers.Default)`, tiền biên dịch Regex tĩnh trong companion object.
  - Thay thế XML animation bằng `ViewPropertyAnimator` nhẹ nhàng.
  - Đặt `HttpLoggingInterceptor.Level.NONE` trên Release, cache dynamic proxy của `ApiService`.
  - Cấu hình Proguard chuẩn cho Gson, Retrofit, Glide, ONNX Runtime và bật `isMinifyEnabled = true`, strip symbols native.

---

## 2. PHÂN KỲ TRIỂN KHAI ĐỀ XUẤT (3 PHASES)

### 🚀 Phase 1: Quick Wins & Cứu Vãn Bộ Nhớ (Zero-Risk & P0 Immediate)
- [x] Thêm `android:largeHeap="true"` vào `AndroidManifest.xml`.
- [x] Xóa bỏ 85MB models rác (`denoiser.onnx`, `moss_audio_tokenizer_encode.*`).
- [x] Tiền biên dịch 5 Regex tĩnh và chuyển xử lý HTML sang `Dispatchers.Default` trong `StoryDetailActivity`.
- [x] Thay thế XML animation trong `StoryAdapter` bằng `ViewPropertyAnimator`.
- [x] Tắt log header trong Release và cache `ApiService` instance.

### ⚡ Phase 2: Core Engine Architecture & Audio Pipeline (P0 & P1 Critical)
- [x] **Zero-Copy KV-Cache Ping-Pong trong `VieNeuOnnxEngine`:** Loại bỏ chuyển đổi `FloatArray`, tái sử dụng native `OnnxTensor` giữa các frames cho cả backbone và acoustic frames. Xóa bỏ hoàn toàn lệnh `System.gc()`.
- [x] **Refactor `NpzReader` & Audio Embeddings:** Bỏ mảng 3 chiều 16,384 `FloatArray`, nạp trực tiếp mảng phẳng, tiết kiệm >100MB heap.
- [x] **Audio Dedicated Thread & Channel Backpressure:** Tách `AudioTrack.write` ra khỏi `Dispatchers.Default`, thay `ConcurrentLinkedQueue` bằng `Channel<PlaybackChunk>(capacity = 4)`.
- [x] **BabbleGuard Acoustic Trimming:** Cắt tỉa đuôi âm học thay vì chạy lại 2 lần neural synthesis loop.

### 🔧 Phase 3: High-Performance Computing & Release Polish (P1 & P2)
- [x] **Tối ưu phép nhân ma trận Acoustic:** Áp dụng loop unrolling 4x/8x ILP trong Kotlin hoặc JNI Rust NEON.
- [x] **Tối ưu bộ đệm Copy Asset:** Nâng buffer từ 8KB lên 64KB/128KB và thêm version check tự động cập nhật.
- [x] **Cấu hình R8 / Proguard & Strip Native Binaries:** Hoàn thiện `proguard-rules.pro` và bật `isMinifyEnabled = true`.

