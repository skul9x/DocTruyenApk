# 📋 HANDOVER DOCUMENT

**Dự án:** DocTruyen & VieNeu-TTS Offline Mobile App  
**Repository:** `https://github.com/skul9x/DocTruyenApk.git` (Branch `main`, Commit: `b9214c6`)  
**Thời gian cập nhật:** 21/08/2026 11:51 (GMT+7)

---

### ✅ ĐÃ HOÀN THÀNH (Release v1.6):
1. **Early G2P Dictionary Pre-warming & Phoneme LRU Cache (Phase 01):**
   - Nạp sớm từ điển `sea_g2p.bin` và khởi động JNI Rust SeaG2P ngay trong `MainActivity.onCreate()` và `VieNeuTTSManager.initManager()`.
   - Bộ nhớ đệm thread-safe LRU Cache (512 entries) tránh gọi JNI lặp lại cho các từ thông dụng.
   - Giảm độ trễ G2P ở chunk đầu tiên từ **129ms xuống < 5ms**.
   - Test: [EarlyG2PWarmingAndCacheTest.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/test/java/com/skul9x/doctruyen/tts/vieneu/EarlyG2PWarmingAndCacheTest.kt) (100% Passed).

2. **Native Memory Lifecycle Management & Safe Resource Teardown (Phase 02):**
   - Đóng tuần tự `OrtSession` và `SessionOptions` trong `OnnxSessionManager.close()` tránh rò rỉ bộ nhớ native C++.
   - Tái chế `CacheTensor` (`pk.recycle()`, `pv.recycle()`) qua 16 kênh âm thanh trong `acousticFrame()`, giải phóng `CacheTensor.Pool` và working buffer khi đóng engine.
   - Thêm hook `onTrimMemory(level)` trong `ReadingService` để dọn dẹp `PrewarmedChunkCache` và `SeaG2P` cache khi hệ thống báo thiếu RAM.
   - Test: [NativeMemoryLifecycleManagementTest.kt](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/app/src/test/java/com/skul9x/doctruyen/tts/vieneu/NativeMemoryLifecycleManagementTest.kt) (100% Passed).

3. **Phân tích Telemetry Diagnostic (`new-log.txt`):**
   - Đánh giá phiên đọc thực tế trên OnePlus CPH2691 (SM8650): 22 chunks, 60.7s audio, RTF 0.96x, 0 lần vấp tiếng (0 starvation).
   - Xác định phương án tối ưu giảm TTFT từ 5.9s xuống < 1.2s bằng cơ chế Fast-Start Pre-buffering.

4. **Đồng bộ mã nguồn & Release v1.6:**
   - Push commit `b9214c6` lên `origin/main` (không force push).
   - Soạn thảo và cập nhật [CHANGELOG.md](file:///c:/Users/Administrator/Downloads/DocTruyenApk-main/CHANGELOG.md) cho Release v1.6.

---

### ⏳ BƯỚC TIẾP THEO (Gợi ý nâng cấp):
- **Fast-Start Pre-buffering:** Cho phép phát ngay sau khi hoàn thành Chunk 1 (nếu thời lượng audio >= 1.5s) trên thiết bị mạnh thay vì bắt buộc chờ đủ 3 chunks / 7.0s, giúp TTFT đạt < 1.2s.
- **SmartTextSegmenter target tuning:** Giảm `DEFAULT_MAX_CHARS` từ 75 xuống 45 ký tự để tránh các chunk dài có RTF > 1.0x.

---

### 🚀 TIẾP TỤC TRONG SESSION MỚI:
Khi quay lại làm việc, chỉ cần gõ `/recap` để khôi phục toàn bộ ngữ cảnh dự án.
