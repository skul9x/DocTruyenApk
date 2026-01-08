# 📂 Cấu trúc dự án DocTruyen

## Tổng quan

```
DocTruyen/
├── 📄 README.md                    # Tài liệu hướng dẫn
├── 📄 structure.md                 # File này
├── 📄 build.gradle.kts             # Gradle config (project)
├── 📄 settings.gradle.kts          # Gradle settings
│
└── 📁 app/                         # Module chính
    ├── 📄 build.gradle.kts         # Gradle config (app)
    │
    └── 📁 src/main/
        ├── 📄 AndroidManifest.xml  # Manifest
        │
        ├── 📁 java/com/skul9x/doctruyen/
        │   │
        │   ├── 📄 MainActivity.kt           # Màn hình chính (danh sách truyện)
        │   ├── 📄 StoryDetailActivity.kt    # Màn hình đọc/nghe truyện
        │   ├── 📄 SettingsActivity.kt       # Màn hình cài đặt
        │   ├── 📄 DebugLogActivity.kt       # Màn hình xem log debug
        │   │
        │   ├── 📁 adapter/
        │   │   └── 📄 StoryAdapter.kt       # RecyclerView adapter cho list truyện
        │   │
        │   ├── 📁 network/
        │   │   ├── 📄 ApiService.kt         # Retrofit interface + data classes
        │   │   └── 📄 HostingVerifier.kt    # WebView-based cookie authentication
        │   │
        │   ├── 📁 service/
        │   │   └── 📄 ReadingService.kt     # Foreground service cho TTS playback
        │   │
        │   ├── 📁 tts/
        │   │   └── 📄 TTSManager.kt         # Text-to-Speech wrapper
        │   │
        │   └── 📁 utils/
        │       ├── 📄 UserConfig.kt         # SharedPreferences wrapper
        │       ├── 📄 DebugLogger.kt        # In-app logging utility
        │       └── 📄 AuthenticatedGlideUrl.kt # Glide URL với headers
        │
        └── 📁 res/
            ├── 📁 layout/
            │   ├── 📄 activity_main.xml         # Layout màn hình chính
            │   ├── 📄 activity_story_detail.xml # Layout màn hình chi tiết
            │   ├── 📄 activity_settings.xml     # Layout màn hình cài đặt
            │   ├── 📄 activity_debug_log.xml    # Layout màn hình debug
            │   ├── 📄 item_story.xml            # Layout item trong list
            │   └── 📄 dialog_webview.xml        # Dialog cho WebView auth
            │
            ├── 📁 values/
            │   ├── 📄 strings.xml               # Chuỗi văn bản (đa ngôn ngữ ready)
            │   ├── 📄 colors.xml                # Bảng màu
            │   └── 📄 themes.xml                # Theme Material Design 3
            │
            ├── 📁 drawable/
            │   ├── 📄 bg_*.xml                  # Background drawables
            │   ├── 📄 gradient_*.xml            # Gradient overlays
            │   └── 📄 ic_*.xml                  # Vector icons
            │
            └── 📁 mipmap-*/
                └── 📄 ic_launcher*.png          # App icons (các kích thước)
```

---

## 📦 Chi tiết từng thành phần

### 🎯 Activities (Màn hình)

| File | Chức năng |
|------|-----------|
| `MainActivity.kt` | Hiển thị danh sách truyện, search, sort, pagination |
| `StoryDetailActivity.kt` | Hiển thị nội dung truyện, điều khiển TTS playback |
| `SettingsActivity.kt` | Cấu hình URL server, voice settings, debug access |
| `DebugLogActivity.kt` | Xem và chia sẻ log lỗi để debug |

### 🔌 Network Layer

| File | Chức năng |
|------|-----------|
| `ApiService.kt` | Định nghĩa Retrofit API interface, data classes (Story, Response) |
| `HostingVerifier.kt` | Xử lý authentication qua WebView để lấy cookies từ free hosting |

### 🔊 TTS Layer

| File | Chức năng |
|------|-----------|
| `TTSManager.kt` | Wrapper cho Android TTS, quản lý state, chunk splitting |
| `ReadingService.kt` | Foreground Service giữ TTS chạy khi app ở background |

### 🛠️ Utilities

| File | Chức năng |
|------|-----------|
| `UserConfig.kt` | Lưu/đọc settings từ SharedPreferences (URL, TTS config) |
| `DebugLogger.kt` | Singleton lưu log trong memory, hỗ trợ export/share |
| `AuthenticatedGlideUrl.kt` | Custom Glide URL với headers để load ảnh từ protected server |

---

## 🔄 Luồng dữ liệu chính

```
┌──────────────┐     ┌─────────────────┐     ┌──────────────┐
│  User Input  │ ──▶ │   Activity      │ ──▶ │ RetrofitClient│
│  (tap/scroll)│     │ (UI Controller) │     │   (API)       │
└──────────────┘     └────────┬────────┘     └───────┬───────┘
                              │                      │
                              │                      ▼
                              │              ┌──────────────┐
                              │              │   api.php    │
                              │              │   (Server)   │
                              │              └───────┬──────┘
                              │                      │
                              ▼                      ▼
                     ┌────────────────┐     ┌──────────────┐
                     │  StoryAdapter  │ ◀── │ JSON Response│
                     │ (RecyclerView) │     │   (Stories)  │
                     └────────────────┘     └──────────────┘
```

---

## 🎨 Layouts

| Layout | Sử dụng trong | Mô tả |
|--------|---------------|-------|
| `activity_main.xml` | MainActivity | AppBarLayout + SwipeRefresh + RecyclerView |
| `activity_story_detail.xml` | StoryDetailActivity | Image + Title + Content + TTS Controls |
| `activity_settings.xml` | SettingsActivity | Cards với URL input, Sliders cho TTS |
| `item_story.xml` | StoryAdapter | Card với Image + Title cho mỗi truyện |
| `dialog_webview.xml` | HostingVerifier | WebView để xác thực hosting |

---

## 🗄️ Data Classes

```kotlin
// Trong ApiService.kt

data class Story(
    val id: Int,
    val title: String,
    val image: String?
)

data class StoryDetail(
    val id: Int,
    val title: String,
    val content: String,      // HTML content
    val contentText: String,  // Plain text cho TTS
    val image: String?
)

data class StoryListResponse(
    val status: String,
    val data: List<Story>,
    val total: Int,
    val page: Int,
    val totalPages: Int
)
```

---

## ⚙️ Configuration

### SharedPreferences Keys (UserConfig.kt)

| Key | Type | Default | Mô tả |
|-----|------|---------|-------|
| `base_url` | String | `https://skul9x.free.nf/truyen/` | URL server API |
| `tts_speed` | Float | `1.0f` | Tốc độ đọc (0.5 - 2.0) |
| `tts_pitch` | Float | `1.0f` | Cao độ giọng (0.5 - 2.0) |
| `tts_voice` | String? | `null` | Tên giọng đọc đã chọn |
| `sort_order` | String | `"newest"` | Thứ tự sắp xếp |

---

## 📋 Dependencies chính

```kotlin
// Network
implementation("com.squareup.retrofit2:retrofit:2.9.0")
implementation("com.squareup.retrofit2:converter-gson:2.9.0")
implementation("com.squareup.okhttp3:okhttp:4.12.0")

// Image Loading
implementation("com.github.bumptech.glide:glide:4.16.0")

// UI
implementation("com.google.android.material:material:1.11.0")
implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

// Async
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
```

---

## 🧪 Testing

```bash
# Unit tests
./gradlew test

# Instrumented tests
./gradlew connectedAndroidTest

# Lint check
./gradlew lint
```

---

<p align="center">
  <em>Cập nhật lần cuối: Tháng 1, 2024</em>
</p>
