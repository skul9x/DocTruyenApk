# 💡 BRIEF: Đợt 1 — Khắc Phục Lỗi Logic Độ Trễ Cao, Lãng Phí Prewarm & Nguy Cơ ANR (P2, P1, P3, P7)

**Ngày tạo:** 2026-11-22  
**Dự án:** DocTruyen & VieNeu-TTS Engine Integration (Android Native / Kotlin)  
**Tài liệu tham chiếu:** [fix.md](file:///home/skul9x/Desktop/Code/DocTruyenApk-1.12/fix.md)  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT

Trong quá trình trải nghiệm đọc truyện bằng giọng đọc AI cục bộ (VieNeu-TTS on-device), người dùng đang gặp phải các vấn đề nghiêm trọng:

1. **TTFT (Time To First Audio) bị kéo dài bất thường (5–7 giây):**
   - **Vấn đề P2:** Người dùng mở truyện, hệ thống prewarm bắt đầu tính toán ngầm chunk 0. Khi người dùng bấm nút **Play**, hàm `cancelPrewarm()` lập tức hủy job. Do prewarm đang bị bọc trong `NonCancellable`, nó vẫn tính ngầm tiếp 3–7 giây và chiếm chặt engine lock. Nhưng khi tính xong, lệnh `if (!isActive) break` chạy TRƯỚC khi đưa kết quả vào Cache! Kết quả đã tính bị vứt bỏ hoàn toàn. Sau đó, Audio Pipeline chính khởi động lại phải xếp hàng chờ lock rồi tính toán lại đúng chunk 0 từ đầu.
   - **Vấn đề P7:** Các mô hình ONNX đóng gói sẵn trong APK (`vieneu_prefill.onnx`, `vieneu_decode_step.onnx`) thực chất đã được lượng tử hóa INT8 (chứa hàng chục `MatMulInteger`), nhưng không mang chuỗi metadata `"quantization": "int8"`. Runtime quét file thất bại nên nhận định nhầm là mô hình FP32 nặng nề, tự động tăng cấu hình pre-buffer từ 2000ms lên tới **7500ms** (7,5 giây). Người dùng phải chờ đợi rất lâu âm thanh mới bắt đầu phát ra.

2. **Tạm dừng và tiếp tục (Pause / Resume) gây giật, lặp câu và tốn pin:**
   - **Vấn đề P1:** Khi bấm Pause hoặc khi mất Audio Focus tạm thời (chuông điện thoại, thông báo), hàm `pause()` hủy bỏ toàn bộ coroutine job và gọi `drainChannel()`, vứt bỏ toàn bộ các chunk âm thanh đã tổng hợp trước đó trong hàng đợi (mất từ 1–4 chunk, tương đương hàng chục giây CPU tính toán).
   - Khi Resume, pipeline lại kích hoạt tổng hợp lại từ đầu chunk đang phát. Nếu chunk trước đó đang phát dở, driver `AudioTrack` vẫn còn tồn đọng một phần buffer phần cứng, dẫn đến tình trạng phát lặp lại âm thanh của câu cũ.

3. **Nguy cơ đóng băng ứng dụng và crash ANR (Application Not Responding):**
   - **Vấn đề P3:** Các thao tác đóng, hủy hoặc chuyển đổi chế độ engine (`initTTS`, `switchPrecision`, `onDestroy`, `onTaskRemoved`, `close()`) đều diễn ra trên Main Thread (UI thread). Hàm `close()` phải tranh chấp và chờ `lock.withLock` với hàm tổng hợp `infer` đang chạy (mất từ 3–7 giây). Quá 5 giây trên Main thread sẽ dẫn đến hộp thoại ANR và bị Android OS kill ứng dụng.

---

## 2. GIẢI PHÁP ĐỀ XUẤT (BEST PRACTICES)

Nghiên cứu và áp dụng các Best Practices chuẩn của Android AudioTrack, Kotlin Coroutines Channel Backpressure và ONNX Runtime Mobile:

1. **Sửa Logic Prewarm (P2 - Tránh lãng phí chu kỳ tính toán):**
   - Không hủy bỏ mù quáng `cancelPrewarm()` khi bấm Play.
   - Di chuyển lệnh lưu cache `PrewarmedChunkCache.put(...)` lên TRƯỚC bước kiểm tra `if (!isActive) break`. Dù prewarm job có nhận tín hiệu hủy, kết quả đã hoàn thành suy luận vẫn phải được cất vào cache an toàn.
   - Pipeline phát âm thanh khi khởi chạy sẽ kiểm tra Cache trước; nếu Chunk 0 đã có sẵn hoặc đang tính, nó sẽ tái sử dụng ngay lập tức thay vì bắt engine tính lại.

2. **Tối ưu Cơ chế Pause / Resume Pipeline (P1 - Giữ nguyên Buffer & Tránh Lặp Audio):**
   - Áp dụng cơ chế **Cooperative Suspension (Tạm dừng phối hợp)** qua cờ hoặc trạng thái thay vì hủy bỏ phá hủy (`cancel()`) toàn bộ `synthesisJob` và xóa sạch `audioChannel`.
   - Khi Pause: Chỉ cần gọi `audioPlayer.pause()`. Giữ nguyên các chunk đã được tổng hợp trong Channel (cơ chế backpressure giới hạn dung lượng của Channel sẽ tự động làm Producer tạm dừng khi đầy buffer).
   - Khi Resume: Chỉ cần gọi `audioPlayer.play()`, âm thanh lập tức phát tiếp từ vị trí tạm dừng mà không mất dù chỉ 1ms CPU để tính lại chunk cũ.

3. **Chuyển toàn bộ Lifecycle & Teardown của Engine ra Background Thread (P3 - Triệt tiêu nguy cơ ANR):**
   - Tuyệt đối không gọi `close()`, `shutdown()`, `initTTS()` hoặc `switchPrecision()` trực tiếp trên Main Thread.
   - Bọc toàn bộ các thao tác giải phóng tài nguyên ONNX vào `Dispatchers.IO` hoặc một dedicated single-thread worker.
   - Thêm cơ chế cooperative cancellation flag (`@Volatile var isInterrupted = false`) để ngắt vòng lặp suy luận an toàn khi có yêu cầu teardown.
   - Loại bỏ các lệnh gọi cưỡng bức `System.gc()` trên Main Thread.

4. **Sửa Nhận Diện Lượng Tử Hóa INT8 (P7 - Chuẩn hóa Pre-buffer & Telemetry):**
   - Tinh chỉnh hàm `detectQuantizationType()` trong `OnnxSessionManager.kt`:
     - Kiểm tra nếu tên file hoặc thư mục cha chứa `int8` HOẶC mô hình chứa các toán tử lượng tử hóa đặc trưng (`MatMulInteger`, `DynamicQuantizeLinear`), nhận diện chính xác là `QuantizationType.INT8`.
   - Đảm bảo nhánh `computeAdaptiveTargetDurationMs` nhận đúng `INT8`, tự động đưa mức pre-buffer về ngưỡng tối ưu 2000ms – 2500ms, giảm ngay 5 giây chờ đợi ban đầu cho người dùng.

---

## 3. PHẠM VI & DANH SÁCH TÍNH NĂNG (SCOPE)

### 🚀 MVP Đợt 1 (Bắt buộc hoàn thành trong gói này):
- [ ] **Feature P2:** Sửa cơ chế Prewarm trong `VieNeuTTSManager.kt` và `TTSAudioPipeline.kt`. Đảm bảo chunk đã tính không bị vứt bỏ, Play bấm là ăn ngay từ Cache.
- [ ] **Feature P1:** Refactor cơ chế Pause/Resume trong `TTSAudioPipeline.kt` & `AudioPlayer.kt`. Không xả rỗng channel khi Pause, không lặp lại âm thanh khi Resume.
- [ ] **Feature P3:** Di chuyển toàn bộ teardown/shutdown/close của `ReadingService.kt`, `VieNeuTTSManager.kt` và `VieNeuOnnxEngine.kt` sang Coroutine Background Dispatcher (`Dispatchers.IO`).
- [ ] **Feature P7:** Bổ sung logic nhận diện op INT8 thông minh trong `OnnxSessionManager.kt`. Cấu hình đúng `targetPrebufferMs` tối ưu (2000ms).
- [ ] **Unit Tests:** Viết bộ kiểm thử tự động (Unit Test Suite) xác minh tính đúng đắn của 4 lỗi logic trên trước khi đóng gói.

### 🎁 Phase Tiếp Theo (Đợt 2 - Làm sau):
- [ ] **P4:** Đưa việc parse HTML, Regex và chuẩn bị text sang Background thread (`Dispatchers.Default`), chia nhỏ TextView / RecyclerView.
- [ ] **M13:** Chuyển ProgressBar từ `indeterminate="true"` sang chế độ determinate hiển thị phần trăm thật.
- [ ] **M8 & M12:** Khắc phục rò rỉ WebView trong `HostingVerifier.kt` và WakeLock timeout trong `ReadingService.kt`.

---

## 4. ƯỚC TÍNH RỦI RO & PHÂN TÍCH KỸ THUẬT

- **Độ phức tạp:** Trung bình (Code logic Kotlin Coroutines & Threading). Không đụng vào model nhị phân ONNX gốc hay viết lại JNI C++/Rust.
- **Rủi ro kỹ thuật:**
  - *Race condition khi Resume/Pause:* Cần quản lý state giữa `AudioTrack` và Coroutine Channel một cách thread-safe (dùng Mutex hoặc Atomic state).
  - *Backpressure của Channel:* Cần đảm bảo dung lượng Channel (capacity) hợp lý (ví dụ: 3–4 chunks) để không gây tràn RAM khi tạm dừng lâu.
- **Lợi ích dự kiến:**
  - TTFT giảm từ ~5.0–7.5 giây xuống **~1.5–2.5 giây**.
  - Không còn hiện tượng đơ ứng dụng (ANR) khi thoát app hoặc đổi giọng/đổi độ chính xác.
  - Thao tác Pause / Resume mượt mà tức thì, không gây nóng máy.

---

## 5. BƯỚC TIẾP THEO

→ Chuyển giao sang workflow `/plan` để phân rã chi tiết thành các Phase thực thi code và kịch bản Test tự động.
