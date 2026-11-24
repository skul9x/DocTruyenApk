# 💡 TECHNICAL BRIEF: KHẮC PHỤC TRIỆT ĐỂ LỖI CRITICAL, HIGH & MEDIUM (TTS PERFORMANCE & STABILITY)

**Ngày lập:** 2026-11-24  
**Chủ đề:** Tối ưu hóa hiệu năng suy luận ONNX, triệt tiêu độ trễ chuyển truyện (7.5s -> <50ms), sửa lỗi lượng tử hóa và dọn rò rỉ bộ nhớ.  
**Tham chiếu bệnh án:** `docs/reports/audit_20261124_1741.md`  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT & MỤC TIÊU CỐT LÕI

| Mã lỗi | Mức độ | Vấn đề lâm sàng | Mục tiêu sau khi sửa |
| :--- | :--- | :--- | :--- |
| **AUD-001** | 🔴 **Critical** | Bấm Stop / Đổi truyện / Seek nhưng CPU vẫn cày AI ngầm 3s–7.5s, làm khóa cứng lệnh phát truyện mới | Ngắt nhịp suy luận tức thì trong **< 20ms**, giải phóng lock ngay lập tức, triệt tiêu hoàn toàn độ trễ khi đổi truyện. |
| **AUD-002** | 🟠 **High** | Nhận diện sai model FP32 thành INT8 do file `vieneu_backbone_shared.data`, làm tụt buffer từ 7.5s xuống 2s gây vấp đĩa | Nhận diện chính xác 100% FP32 vs INT8; khôi phục prebuffer 7,500ms cho FP32 để phát mượt không khựng. |
| **AUD-003** | 🟡 **Medium** | Cấp phát 9,000 JNI `OnnxTensor` vô nghĩa trong vòng lặp 15-channel/frame của mỗi câu văn | Tái sử dụng mảng tensor vị trí tĩnh (15 tensors), giảm 9,000 lần JNI allocation/chunk, hạ CPU RTF. |
| **AUD-004** | 🟡 **Medium** | `ReadingService` giữ strong reference callback làm rò rỉ `StoryDetailActivity` và View hierarchy | Tự động dọn sạch callback trong `onDestroy()`, kết hợp `WeakReference` phòng thủ chống leak RAM. |
| **AUD-005/006** | 🔵 **Low** | Thứ tự guard `isClosed` và lỗi đọc scalar 0-D trong `NpzReader.kt` làm rớt unit tests | Đảo thứ tự guard và fix parser scalar để khôi phục 100% test pass. |

---

## 2. NGHIÊN CỨU BEST PRACTICES TỪ THẾ GIỚI (ONLINE RESEARCH FINDINGS)

### A. ONNX Runtime Android: Cơ chế ngắt suy luận an toàn (Safe Cancellation)
- **Cảnh báo từ ONNX Runtime Team:** Tuyệt đối không được gọi `session.close()` hoặc ngắt luồng thô bạo bằng `Thread.interrupt()` khi `session.run()` đang thực thi trong C++ native layer — hành vi này sẽ gây lỗi bộ nhớ **`SIGSEGV (Segmentation Fault)`** làm crash app ngay lập tức.
- **Best Practice chuẩn mực:**
  1. Sử dụng cờ ngắt hợp tác (Cooperative Cancellation Flag) tại các mốc checkpoint giữa các khung hình (frames).
  2. Bỏ `withContext(NonCancellable)` khỏi vòng lặp phát nhạc thông thường (chỉ giữ cho tác vụ prewarm chunk 0 nếu cần).
  3. Bổ sung hàm `resetCancel()` trong Engine để sẵn sàng nhận lệnh phát mới ngay khi chunk cũ bị hủy.

### B. ONNX Runtime Java API: Tối ưu bộ nhớ JNI & Tensor Pooling
- **Vấn đề hiệu năng JNI:** Hàm `OnnxTensor.createTensor()` mỗi lần gọi đều tạo một native peer structure và sao chép dữ liệu qua ranh giới JNI.
- **Best Practice chuẩn mực:**
  - Với các tensor input có kích thước và giá trị bất biến (như `position_ids` của 15 channels: $ch \in [1..15]$ với giá trị tĩnh $ch + 1$), tạo sẵn 1 mảng tĩnh `Array(16) { ... }` khi khởi tạo Engine và tái sử dụng cho toàn bộ 600 frames.

### C. Android Foreground Service & Activity Callbacks
- **Best Practice từ Android Architecture Guides:**
  - Không bao giờ để Service dài hạn (Foreground Service) giữ Strong Reference tới lambda closure của Activity ngắn hạn.
  - Phải thực hiện **Deterministic Cleanup**: Đảm bảo `onDestroy()` của Activity gọi lệnh hủy đăng ký callback (`removeStateCallback()`), kết hợp dọn dẹp `progressCallback = null`.

---

## 3. PHÂN TÍCH CÁC PHƯƠNG ÁN ĐIỀU TRỊ (BRAINSTORMING OPTIONS)

### Ca bệnh 1: AUD-001 (Ngắt suy luận tức thì khi Stop / Đổi truyện)

* **Phương án A (Tối giản):** Chỉ bỏ `withContext(NonCancellable)` trong `TTSAudioPipeline`.
  - *Ưu điểm:* Dễ làm.
  - *Nhược điểm:* Khi coroutine bị cancel, lệnh `engine.inferWithTiming` vẫn đang chạy trong thread background cho đến hết frame hiện tại nếu không có cờ `cancelRequested` chủ động.
* **Phương án B (Toàn diện - ⭐ KHUYÊN DÙNG):**
  1. Bỏ `withContext(NonCancellable)` trong vòng lặp phát của `TTSAudioPipeline`.
  2. Thêm `fun resetCancel()` vào `VieNeuOnnxEngine`.
  3. Trong `TTSAudioPipeline.stopInternal()`, gọi ngay `engine.requestCancel()` để vòng lặp 600 frames ném `InterruptedException` và thoát ngay trong < 10ms.
  4. Trong `startPipeline()`, gọi `engine.resetCancel()` để chuẩn bị cho truyện mới.
  - *Đánh giá:* Triệt tiêu 100% độ trễ 7.5s, chuyển bài tức thì (<20ms), không lo crash.

---

### Ca bệnh 2: AUD-002 (Nhận diện lượng tử hóa chính xác FP32 vs INT8)

* **Phương án A:** Dựa vào đường dẫn thư mục: `parent.name.contains("backbone_int8")`.
* **Phương án B (Kết hợp - ⭐ KHUYÊN DÙNG):**
  - Xóa bỏ hoàn toàn điều kiện sai lầm `if (sharedData.exists()) return INT8`.
  - Kiểm tra `parent.name.contains("backbone_int8")` HOẶC quét bytecode tìm toán tử (`MatMulInteger`, `DynamicQuantizeLinear`, `QLinearMatMul`).
  - Nếu ở trong thư mục `backbone` và không có toán tử int8 $\rightarrow$ trả về `QuantizationType.FP32`.
  - *Đánh giá:* Sửa dứt điểm lỗi vấp âm thanh, đưa pre-buffer FP32 về đúng 7,500ms, giúp 4 unit tests pass ngay lập tức.

---

### Ca bệnh 3: AUD-003 (Tối ưu 9,000 JNI allocations/chunk)

* **Phương án A:** Giữ nguyên tạo mới từng frame.
* **Phương án B (Tạo Pool Tensor tĩnh - ⭐ KHUYÊN DÙNG):**
  - Trong `VieNeuOnnxEngine`, khai báo `cachedPosTensors = Array(16) { ch -> OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(ch.toLong())), longArrayOf(1, 1)) }`.
  - Trong vòng lặp channel $ch$: truyền `cachedPosTensors[ch + 1]` mà không cần `createTensor` và `close()`.
  - Giải phóng 15 tensors này trong `performResourceTeardown()`.
  - *Đánh giá:* Tiết kiệm 9,000 lần JNI alloc/dealloc mỗi câu, giảm tải bộ nhớ native và hạ nhiệt độ máy.

---

### Ca bệnh 4: AUD-004 (Chống rò rỉ Activity RAM trong Service)

* **Giải pháp chuẩn:**
  - Bổ sung `override fun onDestroy()` trong `StoryDetailActivity` gọi dọn dẹp `readingService?.removeStateCallback()`.
  - Trong `ReadingService`, sửa `removeStateCallback()` để xóa cả `stateCallback = null` và `progressCallback = null`.

---

## 4. BƯỚC TIẾP THEO

1. **Chuyển sang `/plan`** để lập kế hoạch chi tiết từng file cần sửa và các unit test kiểm chứng đi kèm.
2. **Triển khai code (`/code`)** theo phác đồ an toàn, kiểm tra regression và chạy lại bộ test suite lâm sàng.
