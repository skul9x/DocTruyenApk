# 💡 BRIEF: Tối Ưu Hóa & Khắc Phục Toàn Diện Lỗi R8 / Retrofit / Network (Fix All)

**Dự án:** DocTruyen & VieNeu-TTS Offline Mobile App  
**Ngày tạo:** 20/11/2026  
**Tác giả:** skul9x & Antigravity Detective  
**Trạng thái:** Sẵn sàng chuyển sang `/plan`

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT (ROOT CAUSE & PAIN POINTS)

1. **Lỗi Runtime Crash trên Bản Release v1.14 (`ClassCastException`):**
   - Khi kích hoạt R8 Minification (`isMinifyEnabled = true`) kèm `proguard-android-optimize.txt`, app bị crash khi mở màn hình chính hoặc bấm "Thử lại":
     ```text
     java.lang.Class cannot be cast to java.lang.reflect.ParameterizedType
     ```
   - **Nguyên nhân cốt lõi:** Các hàm API trong `ApiService.kt` đều là Kotlin Coroutines `suspend fun`. Khi biên dịch bytecode, Kotlin tự động gắn tham số ẩn `Continuation<T>`. R8 ở chế độ Full Mode đã cắt gọt (strip) metadata Generic Signature của `kotlin.coroutines.Continuation` và interface methods của Retrofit vì thiếu các chỉ thị bảo vệ chuyên biệt. Khi Retrofit dùng Java Reflection để xác định kiểu dữ liệu trả về, nó chỉ tìm thấy raw `Class` thay vì `ParameterizedType`, ném ra `ClassCastException`.

2. **Dữ Liệu Mạng Thiếu Tính Bền Vững (Data Model Vulnerabilities):**
   - Các data class trong `ApiService.kt` (`Story`, `StoryListResponse`, `StoryDetailResponse`, `RandomStoryResponse`) chưa được gắn annotation `@Keep` hoặc `@SerializedName` cho toàn bộ các trường (ví dụ `val id: Int`, `val title: String`). Nếu R8 đổi tên trường (`id` $\to$ `a`), Gson parser sẽ trả về null hoặc gán giá trị mặc định.

3. **Cơ Chế Bắt Lỗi & Giao Diện Người Dùng Chưa Thân Thiện (Raw Exception Display):**
   - Khi gặp lỗi mạng, `MainActivity` hiển thị trực tiếp `e.message` (chuỗi lỗi kỹ thuật Java khó hiểu như ảnh user gửi) thay vì câu thông báo bằng tiếng Việt dễ hiểu kèm hướng xử lý cho người dùng.

---

## 2. NGHIÊN CỨU TRỰC TUYẾN & TIÊU CHUẨN BEST PRACTICES (2025 - 2026)

Dựa trên nghiên cứu tài liệu chính thức từ Square Retrofit, Google Android Developer và cộng đồng R8/ProGuard:

### 2.1. Chuẩn Quy Tắc ProGuard / R8 Của Square Retrofit Cho Kotlin Coroutines
Theo khuyến nghị chính thức trong file cấu hình gốc `retrofit2.pro` từ Square:
- Giữ lại đầy đủ các thuộc tính Reflection:
  ```pro
  -keepattributes Signature, InnerClasses, EnclosingMethod
  -keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
  -keepattributes AnnotationDefault
  ```
- **Bắt buộc:** Bảo toàn Generic Type cho `Continuation` của Coroutines và kiểu trả về của Retrofit:
  ```pro
  -keep,allowoptimization,allowshrinking,allowobfuscation class kotlin.coroutines.Continuation
  -keep,allowobfuscation,allowshrinking interface retrofit2.Call
  -keep,allowobfuscation,allowshrinking class retrofit2.Response
  ```
- Giữ interface methods và các dynamic proxy do Retrofit tạo ra khi chạy R8 Full Mode:
  ```pro
  -keepclassmembers,allowshrinking,allowobfuscation interface * {
      @retrofit2.http.* <methods>;
  }
  -if interface * { @retrofit2.http.* <methods>; }
  -keep,allowobfuscation interface <1>
  -if interface * { @retrofit2.http.* public *** *(...); }
  -keep,allowoptimization,allowshrinking,allowobfuscation class <3>
  ```
- Bảo vệ `TypeToken` của Gson:
  ```pro
  -keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
  ```

### 2.2. Chuẩn Data Model Serialization Trong Android
- Sử dụng `@androidx.annotation.Keep` trên toàn bộ các lớp DTO/Response mạng.
- Gắn `@SerializedName("field_name")` tường minh 100% cho mọi thuộc tính, độc lập hoàn toàn với việc R8 làm rối mã nguồn.

### 2.3. Chuẩn Defensive Network Architecture
- Xử lý lỗi theo mô hình đa tầng:
  - `IOException` / `SocketTimeoutException` $\to$ "Mất kết nối mạng hoặc máy chủ phản hồi chậm. Vui lòng kiểm tra lại Wifi/4G."
  - `HttpException` (403, 500...) hoặc HTML Cookie Expiration $\to$ Tự động làm mới cookie qua `HostingVerifier` và thử lại.
  - Lỗi Parsing / Serialization $\to$ "Dữ liệu từ máy chủ không hợp lệ."
  - Lỗi không xác định $\to$ Ẩn stack trace kỹ thuật, hiển thị thông báo lịch sự kèm nút "Thử lại".

---

## 3. GIẢI PHÁP ĐỀ XUẤT ("FIX ALL" ARCHITECTURE)

Thiết kế giải pháp toàn diện gồm 4 trụ cột:

```
┌────────────────────────────────────────────────────────┐
│                      FIX ALL PLAN                      │
├──────────────────────────┬─────────────────────────────┤
│ 1. R8 & ProGuard Rules   │ • Cấu hình đầy đủ Square    │
│    Hardening             │   Retrofit + Coroutines     │
│                          │ • Giữ Continuation & Types  │
├──────────────────────────┼─────────────────────────────┤
│ 2. Data Models Safety    │ • Thêm @Keep cho DTOs       │
│                          │ • Gắn @SerializedName 100%  │
├──────────────────────────┼─────────────────────────────┤
│ 3. User-Friendly Error   │ • Error Translator Helper   │
│    Handling              │ • Auto Re-auth Cookie       │
│                          │ • Ẩn Technical Trace        │
├──────────────────────────┼─────────────────────────────┤
│ 4. Automated Testing     │ • Unit Test ProGuard Rules  │
│    & CI/CD Verification  │ • Release Build Regression  │
└──────────────────────────┴─────────────────────────────┘
```

---

## 4. CHI TIẾT TÍNH NĂNG & HẠNG MỤC SỬA LỖI

### 🚀 Hạng Mục Cốt Lõi (Core / Bắt Buộc Phải Có):
- [ ] **Task 1: Cập nhật `app/proguard-rules.pro` theo chuẩn Square Retrofit 2026**
  - Thêm rules bảo toàn `Continuation`, `retrofit2.Call`, `retrofit2.Response`, `TypeToken`.
  - Thêm rules bảo vệ các method có `@retrofit2.http.*` và interface proxy.
  - Thêm `-keepattributes AnnotationDefault`.
- [ ] **Task 2: Kiên cố hóa các Data Model trong `ApiService.kt`**
  - Gắn `@androidx.annotation.Keep` cho `Story`, `StoryListResponse`, `StoryDetailResponse`, `RandomStoryResponse`.
  - Bổ sung `@SerializedName` cho tất cả các trường: `id`, `title`, `image`, `content`, `status`, `data`, `total`, `page`.
- [ ] **Task 3: Cải thiện xử lý lỗi và trải nghiệm người dùng trong `MainActivity.kt` & `StoryDetailActivity.kt`**
  - Chuyển đổi mã lỗi kỹ thuật sang tiếng Việt thân thiện thông qua hàm `toUserFriendlyMessage(Throwable)`.
  - Tự động kích hoạt cơ chế `verifyHosting()` khi phát hiện lỗi xác thực mà không làm văng app.
- [ ] **Task 4: Mở rộng Unit Test Suite để kiểm thử tự động**
  - Cập nhật `AssetCleansingAndBuildOptimizationTest.kt` kiểm tra toàn bộ các keep rule mới của Retrofit Coroutines.
  - Chạy `./gradlew testReleaseUnitTest` xác nhận 100% pass.

### 🎁 Hạng Mục Nâng Cao (Code Quality & Robustness):
- [ ] **Task 5: Thiết lập `android.enableR8.fullMode=false` trong `gradle.properties` (tuỳ chọn dự phòng an toàn)**
  - Đảm bảo tính tương thích tuyệt đối nếu có bất kỳ thư viện bên thứ 3 nào sử dụng dynamic reflection.

---

## 5. ĐÁNH GIÁ ĐỘ PHỨC TẠP & RỦI RO

- **Độ phức tạp:** Thấp - Trung bình (chủ yếu cấu hình ProGuard, Data Model metadata và Error formatting).
- **Thời gian ước tính:** ~15 - 20 phút hoàn thành toàn bộ.
- **Rủi ro hồi quy (Regression Risk):** Cực kỳ thấp, vì không thay đổi logic tính toán TTS ONNX hay logic nghiệp vụ audio, mà chỉ bảo vệ các lớp dữ liệu và reflection metadata của Retrofit.

---

## 6. BƯỚC TIẾP THEO

→ Chuyển sang workflow `/plan` để tạo kế hoạch triển khai chi tiết từng bước, kiểm thử và build release mới.

