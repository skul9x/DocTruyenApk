# 📋 HANDOVER DOCUMENT

**Dự án:** DocTruyen & VieNeu-TTS Offline Mobile App  
**Repository:** `https://github.com/skul9x/DocTruyenApk.git` (Branch `main`)  
**Thời gian cập nhật:** 19/08/2026 10:28 (GMT+7)

---

### ✅ ĐÃ HOÀN THÀNH:
1. **VieNeu-TTS Engine & Pipeline 100% Offline (Phases 01 - 05):**
   - `SmartTextSegmenter`: Phân tách câu ngữ nghĩa, bảo vệ từ viết tắt tiếng Việt (`TP.HCM`, `BS.`, `TS.`, `GS.`), phân loại kiểu ngắt nghỉ (`COMMA_CLAUSE`, `SENTENCE_MID`, `SENTENCE_FINAL`, `DIALOGUE_TURN`, `PARAGRAPH_BREAK`).
   - `TTSAudioPipeline` & `PcmUtils`: Chèn khoảng lặng atomic, micro-fade 5ms chống click/pop, dynamic lookahead pre-buffering.
   - `TtsMetricsLogger`: Đo đạc TTFT, RTF, buffer underruns, queue dwell time.
   - `SettingsActivity`: Tùy biến thời gian ngắt nghỉ (Cadence Settings) và Debug Telemetry.
   - On-Device Benchmark: TTFT **2.24s**, Mean RTF **0.443x**, **0 Buffer Underruns**, Peak RAM **~520MB**.
2. **Tài liệu & Readme:**
   - [README.md](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/README.md) chi tiết sơ đồ Mermaid, kiến trúc, bảng benchmark và hướng dẫn build.
   - [CHANGELOG.md](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/CHANGELOG.md) ghi nhận đầy đủ phiên bản.
3. **CI/CD & GitHub Actions:**
   - [`.github/workflows/android.yml`](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/.github/workflows/android.yml): Tự động hóa CI/CD, slugify tên ứng dụng, tự động tăng version (`v1.0` -> `v1.1` -> ...), giải mã keystore từ Base64, ký APK và tự động tạo GitHub Release với file `<slug-app-name>-<version>.apk`.
   - Cấu hình [app/build.gradle.kts](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/app/build.gradle.kts) hỗ trợ release signing qua project properties.
   - Bổ sung `*.jks` / `*.keystore` vào [.gitignore](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/.gitignore).
4. **Git Remote:**
   - Đã commit và push toàn bộ mã nguồn lên `origin/main` thành công.

---

### 🔑 GHI CHÚ GITHUB SECRETS:
Cần đảm bảo 4 secrets sau đã có trong GitHub Repository:
- `KEYSTORE_BASE64`
- `STORE_PASSWORD`: `@Colenao123@`
- `KEY_ALIAS`: `key0`
- `KEY_PASSWORD`: `@Colenao123@`

---

### 🚀 TIẾP TỤC TRONG SESSION MỚI:
Khi quay lại, chỉ cần gõ `/recap` để khôi phục toàn bộ ngữ cảnh.
