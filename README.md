# 📚 DocTruyen - Kho Truyện Bé

<p align="center">
  <img src="app/src/main/ic_launcher-playstore.png" width="120" alt="DocTruyen Logo"/>
</p>

<p align="center">
  <strong>Ứng dụng đọc và nghe truyện cổ tích cho trẻ em</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green?style=flat-square&logo=android" alt="Platform"/>
  <img src="https://img.shields.io/badge/Language-Kotlin-purple?style=flat-square&logo=kotlin" alt="Language"/>
  <img src="https://img.shields.io/badge/Min%20SDK-24-blue?style=flat-square" alt="Min SDK"/>
  <img src="https://img.shields.io/badge/License-MIT-yellow?style=flat-square" alt="License"/>
</p>

---

## ✨ Tính năng chính

| Tính năng | Mô tả |
|-----------|-------|
| 📖 **Đọc truyện** | Hiển thị nội dung truyện với định dạng đẹp, dễ đọc |
| 🔊 **Nghe truyện (TTS)** | Text-to-Speech với giọng đọc tiếng Việt tự nhiên |
| 🎲 **Truyện ngẫu nhiên** | Khám phá truyện mới mỗi ngày |
| 🔍 **Tìm kiếm** | Tìm nhanh truyện yêu thích với debounce search |
| 🔄 **Sắp xếp** | Sắp xếp theo thời gian hoặc tên A-Z |
| ⚙️ **Tùy chỉnh giọng đọc** | Điều chỉnh tốc độ, cao độ, chọn giọng đọc |
| 🌙 **Giao diện đẹp** | Material Design 3 với màu sắc sinh động |

---

## 📱 Screenshots

| Màn hình chính | Chi tiết truyện | Cài đặt |
|:--------------:|:---------------:|:-------:|
| *Danh sách truyện* | *Đọc & Nghe* | *Tùy chỉnh* |

---

## 🛠️ Công nghệ sử dụng

- **Kotlin** - Ngôn ngữ chính
- **Material Design 3** - UI Components
- **Retrofit + OkHttp** - API Client với Cookie handling
- **Glide** - Image loading với authentication
- **Coroutines** - Async programming
- **Android TTS** - Text-to-Speech engine
- **Foreground Service** - Background playback

---

## 🏗️ Kiến trúc

```
┌─────────────────────────────────────────────────────────────┐
│                      Presentation Layer                      │
│  ┌─────────────┐  ┌──────────────────┐  ┌────────────────┐  │
│  │ MainActivity│  │StoryDetailActivity│  │SettingsActivity│  │
│  └──────┬──────┘  └────────┬─────────┘  └───────┬────────┘  │
└─────────┼──────────────────┼────────────────────┼───────────┘
          │                  │                    │
┌─────────┼──────────────────┼────────────────────┼───────────┐
│         │          Service Layer                │           │
│         │    ┌─────────────┴────────────┐       │           │
│         │    │     ReadingService       │       │           │
│         │    │  (Foreground + TTS)      │       │           │
│         │    └─────────────┬────────────┘       │           │
└─────────┼──────────────────┼────────────────────┼───────────┘
          │                  │                    │
┌─────────┼──────────────────┼────────────────────┼───────────┐
│         │           Data Layer                  │           │
│  ┌──────┴──────┐  ┌────────┴────────┐  ┌────────┴────────┐  │
│  │RetrofitClient│  │  HostingVerifier │  │   UserConfig   │  │
│  │  (API)      │  │  (Cookie Auth)   │  │ (SharedPrefs)  │  │
│  └─────────────┘  └─────────────────┘  └─────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

---

## 🚀 Cài đặt & Chạy

### Yêu cầu
- Android Studio Arctic Fox trở lên
- JDK 17+
- Android SDK 24+

### Bước thực hiện

```bash
# 1. Clone repository
git clone https://github.com/skul9x/doctruyen.git

# 2. Mở project trong Android Studio

# 3. Sync Gradle

# 4. Build & Run
./gradlew assembleDebug
```

### Cấu hình Server

Trong file `UserConfig.kt`, cập nhật URL server mặc định:
```kotlin
private const val DEFAULT_URL = "https://your-server.com/truyen/"
```

---

## 📁 Cấu trúc thư mục

Xem chi tiết tại [structure.md](structure.md)

---

## 🔧 API Endpoints

| Endpoint | Method | Mô tả |
|----------|--------|-------|
| `?action=list` | GET | Lấy danh sách truyện (pagination) |
| `?action=detail&id=X` | GET | Lấy chi tiết truyện |
| `?action=random` | GET | Lấy truyện ngẫu nhiên |

### Request Headers (Bắt buộc)
```
X-Requested-With: com.skul9x.doctruyen
Cookie: <từ HostingVerifier>
```

---

## 🐛 Debug

Ứng dụng có tích hợp **Debug Logger** để theo dõi:
- API requests/responses
- Cookie handling
- Error tracking

Truy cập: **Cài đặt → Xem Nhật Ký Lỗi**

---

## 📜 License

```
MIT License

Copyright (c) 2024 skul9x

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction...
```

---

## 👨‍💻 Tác giả

**skul9x** - *Developer*

---

<p align="center">
  Made with ❤️ for kids in Vietnam 🇻🇳
</p>
