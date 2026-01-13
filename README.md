# DocTruyen - Ứng Dụng Đọc Truyện Android

**DocTruyen** là ứng dụng đọc truyện hiện đại trên nền tảng Android, mang đến trải nghiệm đọc và nghe truyện mượt mà. Ứng dụng tích hợp công nghệ **Text-to-Speech (TTS)** thông minh, cho phép người dùng nghe truyện mọi lúc mọi nơi, ngay cả khi tắt màn hình.

![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-0095D5?style=for-the-badge&logo=kotlin&logoColor=white)

## ✨ Tính Năng Nổi Bật

### 📖 Trải Nghiệm Đọc
-   **Kho truyện online**: Kết nối API để hiển thị danh sách truyện mới nhất.
-   **Hiển thị tối ưu**: Hỗ trợ render nội dung HTML, hiển thị ảnh minh họa sắc nét ngay trong nội dung truyện.
-   **Điều hướng dễ dàng**: Vuốt để tải lại (Swipe Refresh), cuộn vô tận (Infinite Scroll).

### 🎧 Text-to-Speech (TTS) - Nghe Truyện
-   **Phát nền (Background Playback)**: Service đọc truyện chạy ngầm giúp bạn vừa nghe truyện vừa làm việc khác hoặc tắt màn hình tiết kiệm pin.
-   **Điều khiển mạnh mẽ**:
    -   Thanh điều khiển (Media Controls) đầy đủ: Play, Pause, Resume, Stop.
    -   **Notification Controls**: Điều khiển trực tiếp từ thanh thông báo (Notification Panel) hỗ trợ Android 13+.
    -   **Thông minh**: Tự động đánh dấu vị trí đang đọc dở để đọc tiếp chính xác (Resume).

### 🔍 Tìm Kiếm & Tiện Ích
-   **Tìm kiếm toàn diện**: Hỗ trợ tìm theo tên truyện và tìm sâu trong nội dung (Content Search).
-   **Bảo mật hình ảnh**: Hệ thống `HostingVerifier` tự động xử lý request headers (Cookies, User-Agent) để tải được ảnh từ các nguồn chặn hotlink.
-   **Công cụ cho Developer**: Tích hợp sẵn `DebugLogActivity` để xem log ứng dụng ngay trên điện thoại mà không cần kết nối máy tính.

## 🛠 Tech Stack

Dự án sử dụng các công nghệ và thư viện Android mới nhất:

-   **Ngôn ngữ**: [Kotlin](https://kotlinlang.org/)
-   **Kiến trúc**: MVVM (Model-View-ViewModel) + Service-based Architecture.
-   **Giao diện**: XML Layouts, Material Design 3.
-   **Bất đồng bộ**: Kotlin Coroutines & Flow.
-   **Networking**:
    -   [Retrofit 2](https://square.github.io/retrofit/): REST Client.
    -   [OkHttp 3](https://square.github.io/okhttp/): HTTP Client.
    -   [Gson](https://github.com/google/gson): JSON Parsing.
-   **Image Loading**: [Glide 4.x](https://github.com/bumptech/glide) (Custom ModelLoader).
-   **Logging**: Custom DebugLogger.

## 🔐 Host Authentication

Ứng dụng kết nối với backend trên hosting `free.nf` có cơ chế bảo vệ bằng JavaScript cookie challenge. Luồng xác thực hoạt động như sau:

1. **WebView ẩn (`HostingVerifier`)**: Tạo WebView 1x1 pixel để load trang backend, cho phép JavaScript chạy và set cookie `__test`
2. **Lưu trữ cookie**: Cookie được lưu vào SharedPreferences (thông qua `UserConfig`) để tái sử dụng giữa các phiên
3. **Đính kèm header**: Mọi API request đều được thêm header `Cookie` và `User-Agent` khớp với WebView
4. **Phát hiện tự động (Reactive)**:
   - Retrofit Interceptor kiểm tra response body
   - Nếu response không phải JSON hợp lệ (server trả về trang Challenge) → Ném exception `FreeNfChallenge`
   - Activity/ViewModel bắt exception → Xóa cookie cũ → Kích hoạt `HostingVerifier` → Retry request

## 📱 Cấu Hình Yêu Cầu

-   **Min SDK**: 24 (Android 7.0 Nougat)
-   **Target SDK**: 35 (Android 15)
-   **Quyền truy cập**:
    -   `INTERNET`: Kết nối mạng.
    -   `FOREGROUND_SERVICE`: Chạy trình đọc truyện ngầm.
    -   `POST_NOTIFICATIONS`: Hiển thị thông báo điều khiển media.

## 🚀 Cài Đặt

1.  Clone repository:
    ```bash
    git clone https://github.com/skul9x/DocTruyenApk.git
    ```
2.  Mở project bằng **Android Studio**.
3.  Đợi Gradle sync hoàn tất các thư viện.
4.  Kết nối thiết bị hoặc máy ảo và nhấn **Run** (Shift + F10).

## 🤝 Đóng Góp

Mọi đóng góp (Pull Requests) hoặc báo lỗi (Issues) đều được hoan nghênh.
Sản phẩm được phát triển với mục đích học tập và chia sẻ.
