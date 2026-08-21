# 📋 HANDOVER DOCUMENT

**Dự án:** DocTruyen & VieNeu-TTS Offline Mobile App  
**Repository:** `https://github.com/skul9x/DocTruyenApk.git` (Branch `main`)  
**Thời gian cập nhật:** 21/08/2026 10:45 (GMT+7)

---

### ✅ ĐÃ HOÀN THÀNH:
1. **Tri-Layer Concurrency Defense & Activity Multi-Instance Fix (Phases 01-03):**
   - **Phase 01 (Manifest & Activity Layer):** Đặt `android:launchMode="singleTop"` cho `StoryDetailActivity`, override `onNewIntent()` cập nhật intent và chỉ reload khi `storyId` thay đổi. Test: `StoryDetailSingleTopTest.kt` (4/4 passed).
   - **Phase 02 (Service Layer):** Thêm guard kiểm tra `preparedStoryId`, `preparedVoice`, `isPreparingStory` trong `ReadingService.prepareStory()` chống trigger trùng lặp ONNX pipeline. Test: `ReadingServicePrepareGuardTest.kt` (3/3 passed).
   - **Phase 03 (Network & Content Layer):** Thêm cache guard `loadedStoryId` và in-flight guard `isLoadingContent` trong `StoryDetailActivity.loadStoryContent()`. Test: `StoryDetailContentCacheGuardTest.kt` (4/4 passed).
   - **On-Device Verification (`log6.txt`):** Gọi API detail đúng 1 lần (thay vì 5 lần như ở log5.txt), phát âm mượt mà, 0 buffer underruns.
2. **User-Selectable Model Precision & Dynamic Switching (INT8 / FP32):**
   - UI `cardModelPrecision` trong `SettingsActivity` hỗ trợ chuyển đổi tức thì giữa INT8 (nhanh & mát) và FP32 (chất lượng gốc).
   - Dynamic hot-reloading qua `ACTION_SWITCH_PRECISION` trong `ReadingService` & `VieNeuTTSManager`.
   - Giảm 30.2% RAM (802MB vs 1148MB), RTF đạt 0.90x trên Snapdragon 8 Gen 3 (OnePlus SM8650).
3. **AI Self-Diagnostic Logging & Telemetry:**
   - Thu thập thông số thiết bị (`DevicePerformanceHelper`: RAM, SoC, Battery Temp, Thermal Throttling Status).
   - Latency Breakdown 4 chặng: SeaG2P, ONNX Backbone, Decoder Codec, Audio/PCM.
   - AI Diagnostic Engine tự động phát hiện cờ bất thường và xuất khuyến nghị điều chỉnh.

---

### ⏳ BƯỚC TIẾP THEO:
- Tinh chỉnh bổ sung bộ nhớ đệm từ điển phiên âm G2P (G2P lookup dictionary cache) nếu muốn rút ngắn TTFT hơn nữa.
- Tối ưu giải phóng native tensor/PCM buffer lifecycle.

---

### 🚀 TIẾP TỤC TRONG SESSION MỚI:
Khi quay lại, chỉ cần gõ `/recap` để khôi phục toàn bộ ngữ cảnh.

