# 💡 BRIEF: TỐI ƯU HÓA BẢN RELEASE GITHUB (CHỈ CHỨA FP32 & ARM64-V8A)

**Ngày tạo:** 2026-11-24  
**Chủ đề:** Tinh gọn bản phát hành GitHub Actions Release — Đồng bộ dung lượng nhẹ như bản Debug (~254 MB), loại bỏ mô hình INT8 dư thừa và loại bỏ các kiến trúc 32-bit / x86.  

---

## 1. VẤN ĐỀ CẦN GIẢI QUYẾT & MỤC TIÊU CỐT LÕI

| Vấn đề hiện tại | Nguyên nhân kỹ thuật | Mục tiêu mong muốn |
| :--- | :--- | :--- |
| **File APK Release quá nặng (~495.7 MB)** | Pipeline GitHub Actions chạy `tools/quantize_models.py` sinh thêm thư mục `backbone_int8` (~150 MB) và đóng gói **cả 2 bộ model (FP32 + INT8)** cùng lúc vào APK. | **Loại bỏ mô hình INT8**, chỉ giữ lại duy nhất mô hình **FP32** nguyên bản chất lượng cao. |
| **Đóng gói đa kiến trúc (Universal APK)** | Build release đóng gói toàn bộ thư viện `.so` cho cả 3 kiến trúc: `arm64-v8a`, `armeabi-v7a`, `x86_64` (mỗi kiến trúc tốn ~40-50MB thư viện ONNX + SeaG2P). | **Chỉ nhúng thư viện 64-bit (`arm64-v8a`)**, giảm dung lượng, phù hợp với 99% smartphone Android hiện đại. |
| **Dung lượng mục tiêu** | Bản Release cũ: ~496 MB | **Hạ xuống ~250 MB - 254 MB** (tương đương bản `app-arm64-v8a-debug.apk`). |
| **Thời gian chạy GitHub Actions** | Tốn thời gian setup Python, cài dependencies và chạy script quantize ONNX (~3-5 phút). | Rút ngắn quy trình CI/CD đi 3-5 phút mỗi lần push code. |

---

## 2. PHÂN TÍCH KỸ THUẬT & KHẢ THI (TECHNICAL FEASIBILITY)

### A. Kiểm tra tính tương thích của App khi bỏ INT8:
- **Cơ chế tải model của `OnnxSessionManager.kt`:**
  App đã được lập trình sẵn cơ chế: Kiểm tra thư mục `backbone_int8`, nếu không tìm thấy sẽ tự động fallback về thư mục chuẩn `backbone` (FP32).
- **Trải nghiệm âm thanh (TTS Audio Pipeline):**
  Khi chạy FP32 trên chip 64-bit, hệ thống tự động kích hoạt prebuffer chuẩn 7,500ms, đảm bảo chất lượng giọng đọc tự nhiên nhất, không bị suy giảm chất lượng âm thanh như INT8.
- **Kết luận:** Hoàn toàn an toàn, không gây crash hay lỗi runtime.

### B. Kiểm tra tính tương thích khi chỉ hỗ trợ ARM64 (`arm64-v8a`):
- Toàn bộ thiết bị Android chạy từ Android 8.0 trở lên và có cấu hình chạy được mô hình AI giọng nói VieNeu ONNX thực tế đều là chip 64-bit (ARMv8 trở lên).
- Việc bỏ 32-bit (`armeabi-v7a`) và `x86_64` (chỉ dùng cho giả lập PC) giúp APK nhẹ hơn ~80MB - 90MB native binaries.

---

## 3. CÁC PHƯƠNG ÁN THỰC HIỆN (BRAINSTORMING OPTIONS)

### 🔹 Phương án 1: Cấu hình tinh gọn triệt để từ Gradle + GitHub Actions (Khuyên dùng ⭐)
- **Trong `app/build.gradle.kts`:**
  - Đặt `ndk { abiFilters += listOf("arm64-v8a") }` (chỉ build duy nhất arm64-v8a).
  - Tắt `splits.abi` hoặc đặt mặc định cho arm64-v8a, giúp cả bản Debug lẫn Release xuất ra 1 file APK chuẩn, không phân mảnh.
- **Trong `.github/workflows/android.yml`:**
  - Xóa bỏ hoàn toàn các bước:
    - `Set up Python 3.10`
    - `Install Quantization Dependencies`
    - `Quantize VieNeu ONNX Models to Dynamic INT8`
    - `Verify Quantized Assets`
  - Giữ lại các bước chuẩn: Decode keystore, assembleRelease, đổi tên và upload lên GitHub Release.
- **Ưu điểm:**
  - Sạch sẽ, nhất quán giữa môi trường Local và CI/CD.
  - File xuất ra chuẩn `doctruyenapk-<version>.apk` dung lượng chỉ **~254 MB**.
  - Tối ưu tốc độ build CI/CD nhanh nhất (tiết kiệm tài nguyên GitHub Actions).

---

### 🔹 Phương án 2: Chỉ sửa GitHub Actions Workflow (Giữ nguyên cấu hình Gradle)
- **Trong `app/build.gradle.kts`:** Giữ nguyên cấu hình `splits.abi` hiện tại (build cả arm64, armv7, x86_64, universal).
- **Trong `.github/workflows/android.yml`:**
  - Xóa các bước quantize INT8.
  - Sửa bước `Prepare and Rename APK`: Chọn file `app-arm64-v8a-release.apk` để đổi tên và phát hành (bỏ qua bản universal và 32-bit).
- **Ưu điểm:** Vẫn cho phép local build ra file cho giả lập nếu cần.
- **Nhược điểm:** CI vẫn phải build và ký cả 4 file APK rồi mới chọn 1 file, tốn thêm thời gian build không cần thiết.

---

## 4. TỔNG KẾT PHẠM VI CÔNG VIỆC (SCOPE & TASKS)

### 🚀 MVP Tasks:
- [ ] Xóa các bước setup Python và quantize INT8 trong `.github/workflows/android.yml`.
- [ ] Cập nhật target APK trong `.github/workflows/android.yml` để chọn bản `arm64-v8a` (hoặc cấu hình `app/build.gradle.kts` tinh gọn chỉ target `arm64-v8a`).
- [ ] Kiểm tra build thử nghiệm local để đảm bảo assembleRelease/assembleDebug ra đúng file APK 64-bit nhẹ ~254 MB.
- [ ] Kiểm tra toàn bộ Unit Tests của project để đảm bảo không bị ảnh hưởng.

---

## 5. ƯỚC TÍNH SƠ BỘ
- **Độ phức tạp:** Đơn giản (Chỉ tinh chỉnh workflow CI/CD và cấu hình build).
- **Rủi ro:** Không có rủi ro về mã nguồn hay logic suy luận TTS.
- **Kết quả đầu ra:** File APK Release tải từ GitHub sẽ có dung lượng ~254 MB (giống hệt bản Debug ARM64 bạn vừa build).

---

## 6. BƯỚC TIẾP THEO
→ Chuyển sang `/plan` để lên kế hoạch sửa đổi chi tiết các file và kiểm thử.
