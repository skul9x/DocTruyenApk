# 📋 HANDOVER DOCUMENT

📍 **Đang làm:** Offline VieNeu-TTS v3 Integration for DocTruyenApk  
🔢 **Tiến độ:** Phase 01 - 05 Hoàn thành (100% Hoạt động trên thiết bị thực)

---

### ✅ ĐÃ XONG:
- **Phase 01: Setup & Assets** ✓ (Bundle ~285MB model ONNX int8 + config + tokenizer)
- **Phase 02: sea-g2p Phonemizer** ✓ (Cross-compile Rust `.so` cho `arm64-v8a`, `armeabi-v7a`, `x86_64`)
- **Phase 03: Kotlin ONNX Inference Engine** ✓ (Port Prefill, Decode Step, Acoustic Frame, Codec MOSS sang Kotlin)
- **Phase 04: TTS Audio Pipeline** ✓ (2 luồng Producer - Consumer với AudioTrack streaming 48kHz)
- **Phase 05: Integration** ✓ (Tích hợp VieNeuTTSManager vào ReadingService & StoryDetail UI)
- **Fix Bug Regex Khởi tạo Tokenizer** ✓:
  - Loại bỏ cờ `Pattern.UNICODE_CHARACTER_CLASS` không được Android hỗ trợ (nguyên nhân gây fallback sang Android TTS).
  - Đã chạy kiểm thử thực tế trên máy Android 15 (`testTokenizerEncode` và `testFullInference` PASSED).
  - Cài đặt bản build mới nhất lên điện thoại.

---

### ⏳ CÒN LẠI (Phase 06):
- Đo đạc tối ưu hoá thời gian tạo câu đầu tiên (TTFT).
- Kiểm tra độ ổn định khi đọc truyện dài liên tục nhiều chương.

---

### 🔧 QUYẾT ĐỊNH QUAN TRỌNG:
- **100% Offline trên điện thoại:** Không cần bật máy tính hay server GPU.
- **Pipeline Producer - Consumer:** Sinh âm thanh câu tiếp theo liên tục trong nền trong lúc đang phát câu hiện tại để không bị khựng giữa các câu.

---

### 📁 FILES QUAN TRỌNG:
- [VieNeuTokenizer.kt](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTokenizer.kt)
- [VieNeuOnnxEngine.kt](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuOnnxEngine.kt)
- [TTSAudioPipeline.kt](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt)
- [VieNeuTTSManager.kt](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/tts/vieneu/VieNeuTTSManager.kt)
- [ReadingService.kt](file:///home/skul9x/Desktop/Test_code/DocTruyenApk-main/app/src/main/java/com/skul9x/doctruyen/service/ReadingService.kt)
