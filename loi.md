# 📋 DANH SÁCH LỖI VÀ VẤN ĐỀ HIỆU NĂNG CẦN XỬ LÝ (loi.md)
**Dự án:** DocTruyenApk (VieNeu-TTS Android Reader)  
**Thời gian lập:** 21/08/2026  
**Người lập:** Khang (Security & Mobile Performance Engineer)  

---

## 📊 1. TỔNG QUAN

| Mức độ | Số lượng | Trạng thái |
|---|---|---|
| 🔴 **Nghiêm trọng (Critical)** | 2 | Cần sửa ngay |
| 🟡 **Cảnh báo (Warning)** | 4 | Nên sửa để tối ưu |
| 🟢 **Khuyến nghị (Suggestion)** | 2 | Tùy chọn cải thiện |

---

## 🔴 2. CÁC LỖI NGHIÊM TRỌNG (CRITICAL ISSUES)

### 🔴 Lỗi 1: Bắt nhầm `CancellationException` kích hoạt nhầm Android TTS Fallback
- **Vị trí file:** [VieNeuTTSManager.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt#L145-L150)
- **Triệu chứng thực tế từ log:**
  ```text
  [13:56:49] 🧹 onBackPressed: Cancelling active pre-warm
  [13:56:50] ❌ VieNeuTTSManager: Failed to initialize VieNeu engine: Job was cancelled. Attempting Android TTS fallback...
  kotlinx.coroutines.JobCancellationException: Job was cancelled; job=SupervisorJobImpl{Cancelling}
  ```
- **Nguyên nhân kỹ thuật:**
  Trong hàm `initManager()`, khối Coroutine `scope.launch` có `catch (e: Throwable)`. Khi người dùng rời màn hình (bấm Back), tác vụ khởi tạo bị hủy một cách bình thường (`CancellationException`). Tuy nhiên, `catch (e: Throwable)` bắt luôn cả ngoại lệ hủy này và xem như "khởi tạo VieNeu thất bại", tự động gọi `tryFallbackAndroidTTS()` để kích hoạt chuyển sang giọng đọc máy robot cũ của Android.
- **Hậu quả:** Ứng dụng tự ý đổi động cơ đọc sang Android TTS mặc dù VieNeu hoàn toàn bình thường, làm sai lệch cài đặt và giảm trải nghiệm người dùng.
- **Cách khắc phục:**
  Kiểm tra và bỏ qua `CancellationException` trước khi gọi fallback:
  ```kotlin
  catch (e: kotlinx.coroutines.CancellationException) {
      // Bỏ qua vì đây là hành vi hủy job hợp lệ khi rời màn hình
  } catch (e: Throwable) {
      DebugLogger.logError(TAG, "Failed to initialize VieNeu engine: ${e.message}", e)
      tryFallbackAndroidTTS()
  }
  ```

---

### 🔴 Lỗi 2: Bão luồng CPU (Thread Contention) làm tốc độ đọc RTF > 1.0x
- **Vị trí file:** [TTSAudioPipeline.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt#L128) & [OnnxSessionManager.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt#L148)
- **Triệu chứng thực tế từ log:**
  ```text
  • Device: OnePlus CPH2691 | SoC: SM8650 (8 Cores) | Threads: 8
  • Overall RTF: 1.01x > 1.0x (Tốc độ tổng hợp chậm hơn thời gian nói thực tế)
  ⚠️ [AI_FLAG: CPU_BOUND_RTF_HIGH] RTF trung bình = 1.01x. Thiết bị bị nghẽn CPU.
  ```
- **Nguyên nhân kỹ thuật:**
  Trong `TTSAudioPipeline.kt` dòng 128:
  ```kotlin
  val engineThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
  ```
  Lệnh này trả về toàn bộ 8 cores. Trên kiến trúc chip di động (big.LITTLE như Snapdragon 8 Gen 3), chỉ có 4 nhân hiệu năng cao (Big/Prime cores) và 4 nhân tiết kiệm điện (LITTLE cores). Ép ONNX Runtime chạy 8 threads đồng thời khiến các nhân yếu bị quá tải, gây nghẽn chuyển đổi ngữ cảnh (Context Switching) và tranh chấp tài nguyên (Thread Contention), làm giảm tốc độ suy luận.
- **Hậu quả:** Giọng nói tạo ra bị trễ, máy nóng hơn và tốn pin hơn.
- **Cách khắc phục:**
  Sử dụng `DevicePerformanceHelper.getBigCoreCount()` (giới hạn từ 2 đến 4 threads) để ONNX chỉ chạy trên các nhân mạnh nhất:
  ```kotlin
  val engineThreads = DevicePerformanceHelper.getBigCoreCount().coerceIn(2, 4)
  ```

---

## 🟡 3. CÁC LỖI CẢNH BÁO HIỆU NĂNG & BỘ NHỚ (WARNINGS)

### 🟡 Lỗi 3: Cấp phát rác (GC Churn) liên tục trong `DebugLogger.kt`
- **Vị trí file:** [DebugLogger.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/utils/DebugLogger.kt#L36)
- **Nguyên nhân kỹ thuật:**
  Hàm `DebugLogger.log()` được gọi liên tục mỗi khi nạp ảnh, gửi request mạng, hay đọc từng chunk TTS. Mỗi lần gọi, dòng code sau luôn thực thi:
  ```kotlin
  _logs.postValue(ArrayList(logBuffer))
  ```
  Nó sao chép toàn bộ 500 phần tử sang một `ArrayList` mới trên bộ nhớ Heap.
- **Hậu quả:** Sinh ra hàng nghìn mảng rác tạm thời trong thời gian ngắn, ép trình gom rác (Garbage Collector) của Android chạy liên tục, gây giật khung hình (Micro-jank) khi cuộn ứng dụng.
- **Cách khắc phục:**
  Chỉ `postValue()` khi có giao diện đang lắng nghe (mở màn hình log):
  ```kotlin
  if (_logs.hasActiveObservers()) {
      _logs.postValue(ArrayList(logBuffer))
  }
  ```

---

### 🟡 Lỗi 4: Phân tích HTML trùng lặp (Double Parsing) trên luồng chính
- **Vị trí file:** [StoryDetailActivity.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/StoryDetailActivity.kt#L330-L337)
- **Nguyên nhân kỹ thuật:**
  ```kotlin
  // Lần 1: Parse HTML thành Spanned rồi ép thành String cho TTS
  storyContent = android.text.Html.fromHtml(sanitizedContent, android.text.Html.FROM_HTML_MODE_LEGACY).toString()

  // Lần 2: Parse lại lần nữa từ đầu để gán vào TextView
  tvContent.text = android.text.Html.fromHtml(sanitizedContent, android.text.Html.FROM_HTML_MODE_LEGACY)
  ```
  `Html.fromHtml()` sử dụng parser SAX/XML nặng. Với truyện dài, việc parse 2 lần liên tiếp trên Main Thread làm đơ giao diện 100-300ms.
- **Cách khắc phục:**
  Parse một lần duy nhất và tái sử dụng kết quả:
  ```kotlin
  val spannedContent = android.text.Html.fromHtml(sanitizedContent, android.text.Html.FROM_HTML_MODE_LEGACY)
  storyContent = spannedContent.toString()
  tvContent.text = spannedContent
  ```

---

### 🟡 Lỗi 5: RecyclerView chưa bật `setHasFixedSize(true)` và spam log ảnh khi cuộn
- **Vị trí file:**
  - [MainActivity.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/MainActivity.kt#L111-L113)
  - [StoryAdapter.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/adapter/StoryAdapter.kt#L83)
- **Nguyên nhân kỹ thuật:**
  1. `recyclerView` trong `MainActivity` chưa bật `setHasFixedSize(true)` dù card truyện có kích thước cố định trong lưới 2 cột.
  2. `StoryAdapter.kt` gọi `DebugLogger.log("IMAGE", ...)` trong `bind()` và trong listener Glide cho mọi ảnh mỗi khi người dùng cuộn.
- **Hậu quả:** Gây tính toán lại kích thước khung nhìn (`requestLayout`) và spam hàng trăm log object vào bộ nhớ khi lướt danh sách truyện.
- **Cách khắc phục:**
  - Thêm `recyclerView.setHasFixedSize(true)` trong `setupRecyclerView()`.
  - Bọc các log ảnh trong `if (BuildConfig.DEBUG)` hoặc bỏ log thừa trong `onBindViewHolder`.

---

### 🟡 Lỗi 6: Tái cấp phát JNI Dummy Tensor liên tục trong `VieNeuOnnxEngine.kt`
- **Vị trí file:** [VieNeuOnnxEngine.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt#L349-L350)
- **Nguyên nhân kỹ thuật:**
  Trong `acousticFrame()`, các tensor rỗng `emptyPastK` và `emptyPastV` (shape `[1, 8, 0, 96]`) được tạo mới và đóng qua JNI trong mọi bước frame âm thanh:
  ```kotlin
  val emptyPastK = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(0)), longArrayOf(1, nHLoc.toLong(), 0, hdLoc.toLong()))
  val emptyPastV = OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(0)), longArrayOf(1, nHLoc.toLong(), 0, hdLoc.toLong()))
  ```
- **Hậu quả:** Tăng chi phí JNI bridge overhead và gây phân mảnh bộ nhớ native heap khi chạy hàng trăm frames.
- **Cách khắc phục:** Lưu trữ đối tượng tensor rỗng hoặc chia sẻ bộ đệm tensor thay vì tạo mới liên tục.

---

## 🟢 4. CÁC KHUYẾN NGHỊ TỐI ƯU (SUGGESTIONS)

1. 💡 **Tối ưu luồng đọc mảng bytes trong `NpzReader.kt`:**
   - Trong [NpzReader.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/NpzReader.kt#L154-L162), hàm `readAllBytes()` sử dụng `ByteArrayOutputStream` tăng dần từng 8KB. Có thể cấp phát mảng với kích thước chính xác dựa trên `entry.size` để giảm bớt việc re-allocate mảng.
2. ✅ ~~**Bảo vệ chống khựng (Anti-starvation) khi không dùng Prewarm:**~~
   - **[ĐÃ ĐƯỢC XỬ LÝ]** Trong [TTSAudioPipeline.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt#L434-L444), code hiện tại đã có logic anti-starvation với `minDurationMs = 3500L` cho 2 chunks, `preBufferChunkCount`, và `hasQualifiedChunks` check trước khi mở loa. Không cần sửa thêm.

---

## 🎯 5. BẢNG PHÂN CÔNG SỬA LỖI (ACTION MATRIX)

| STT | File cần sửa | Lỗi xử lý | Thời gian dự kiến |
|---|---|---|---|
| 1 | `VieNeuTTSManager.kt` | Bỏ qua `CancellationException` để không kích hoạt nhầm Android TTS fallback | 3 phút |
| 2 | `TTSAudioPipeline.kt` & `OnnxSessionManager.kt` | Chỉnh số luồng ONNX theo `getBigCoreCount()` (2-4 cores) để hạ RTF < 1.0x | 3 phút |
| 3 | `DebugLogger.kt` | Thêm kiểm tra `_logs.hasActiveObservers()` tránh GC churn | 2 phút |
| 4 | `StoryDetailActivity.kt` | Parse `Html.fromHtml()` 1 lần duy nhất | 2 phút |
| 5 | `MainActivity.kt` & `StoryAdapter.kt` | Bật `setHasFixedSize(true)` và dọn dẹp log spam ảnh | 3 phút |
