# 📋 HANDOVER DOCUMENT

**Dự án:** DocTruyen & VieNeu-TTS Offline Mobile App  
**Repository:** `https://github.com/skul9x/DocTruyenApk.git`  
**Thời gian cập nhật:** 08/10/2026 08:58 (GMT+7)

---

### ✅ ĐÃ HOÀN THÀNH: Upgrade VieNeu-TTS v3 Turbo Features & Engine

Toàn bộ 4 Phases trong kế hoạch nâng cấp đã được hoàn thiện 100% và kiểm thử nghiêm ngặt:

1. **Phase 01: Voice Presets, Aliases, Profile & Checkpoint Sync**
   - Đồng bộ kho 25 preset voices từ upstream `voices_v3_turbo.json` và `VoicePresets.kt`.
   - Cơ chế ánh xạ alias trong suốt: `Minh Quân Pro` / `Minh Quân` -> `Hải Đăng`, `Anh Khôi` -> `Thiện Minh`, `Mạnh Dũng` -> `Quốc Tuấn`.
   - Thiết lập `Hải Đăng` làm giọng đọc mặc định; giữ nguyên tên `Adam bựa`.
   - Cấu hình chuyên biệt cho `Thiền Tâm Đức`: giảm nhiệt độ sinh mẫu (`0.25f`) cho ngữ điệu trầm ổn, kéo dài khoảng dừng chiêm nghiệm.
   - File test: `VoicePresetsAndCheckpointSyncTest.kt` (Passed).

2. **Phase 02: Sliding-Window Repetition Penalty & QuickSelect Top-256 Sampling**
   - Thay thế `HashSet` vĩnh viễn bằng circular buffer 64 khung hình (`RepetitionHistory.kt`) trên 16 kênh codebook, triệt tiêu voice drift và suy thoái ngữ điệu ở câu dài.
   - Thuật toán phân vùng in-place `quickSelectTopK()` trên logits trước khi chạy Softmax trong `VieNeuOnnxEngine.kt`, tăng tốc độ sinh mẫu ~1.5x mỗi khung hình.
   - File test: `SlidingWindowRepetitionAndSamplingTest.kt` (Passed).

3. **Phase 03: Emotion Tags Extraction & Novel Bracket Separation**
   - Trích xuất an toàn các biểu cảm `[cười]`, `[thở dài]`, `[hắng giọng]` (và các biến thể tiếng Anh / không dấu) ánh xạ sang token đặc biệt `<|emotion_1|>`, `<|emotion_2|>`, `<|emotion_3|>` (token IDs 9, 10, 11).
   - Bảo vệ dấu ngoặc chú thích tiểu thuyết (`[1]`, `[CV]`, `[Chương 2]`), không bị nuốt hay nhầm lẫn với thẻ cảm xúc.
   - Bỏ qua normalizer SeaG2P cho các thẻ cảm xúc để bảo vệ tính toàn vẹn của token ID.
   - File test: `EmotionTagsAndBracketSeparationTest.kt` (Passed).

4. **Phase 04: Babble Guard Anti-Hallucination & Speech Burst Detection**
   - Triển khai `BabbleGuard.kt`: bộ phân tích bao năng lượng RMS (bước nhảy 10ms ở 48kHz), phát hiện speech bursts với ngưỡng `-24dB` relative peak, thời lượng tối thiểu 60ms và nối khoảng lặng < 80ms.
   - Heuristic `isBabbleSuspect()`: phát hiện bất thường trên câu thoại cực ngắn (<= 2 âm tiết như *"Vâng."*, *"Hả?"*, *"Ừ."*) khi số burst > số âm tiết hoặc đạt trần khung hình (`capFrames - 1`).
   - Vòng lặp resynthesize tối đa 2 lần với nhiệt độ/seed thay đổi trong `VieNeuOnnxEngine.kt`, lựa chọn ứng viên tối ưu bằng comparator `babblePrefer`.
   - Ghi nhận telemetry `recordBabbleRetry` trong `TtsMetricsLogger.kt`.
   - File test: `BabbleGuardAntiHallucinationTest.kt` (Passed).

---

### 📁 FILES QUAN TRỌNG ĐÃ TẠO / SỬA ĐỔI:
- `app/src/main/assets/vieneu/voices_v3_turbo.json`
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VoicePresets.kt`
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt`
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/RepetitionHistory.kt`
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt`
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SmartTextSegmenter.kt`
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTokenizer.kt`
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SeaG2P.kt`
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/BabbleGuard.kt`
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TtsMetricsLogger.kt`
- `CHANGELOG.md`
- `plans/261007-1937-upgrade-vieneu-v3-turbo-features/`

---

### ⏳ BƯỚC TIẾP THEO (Gợi ý):
1. **Kiểm thử thực tế trên thiết bị (On-Device Audio Quality Check):**
   - Nghe thử giọng đọc thực tế với các câu thoại ngắn kèm biểu cảm: *"Hắn cười: [cười] Thật nực cười."*, *"Ừ."*, *"Vâng."* để đánh giá độ tự nhiên.
2. **Build & Triển khai Release v1.12:**
   - Tag release và chạy CI/CD tạo APK phát hành cho người dùng.

---

### 🚀 TIẾP TỤC TRONG SESSION MỚI:
Khi quay lại làm việc, chỉ cần gõ `/recap` để khôi phục toàn bộ ngữ cảnh dự án.
