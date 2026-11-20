# 📋 HANDOVER DOCUMENT

**Dự án:** DocTruyen & VieNeu-TTS Offline Mobile App  
**Repository:** `https://github.com/skul9x/DocTruyenApk.git`  
**Thời gian cập nhật:** 20/11/2026 17:05 (GMT+7)

---

### ✅ ĐÃ HOÀN THÀNH: Plan A - Punctuation-First Natural Text Segmentation

1. **Phase 01: Core Segmenter Refactor (`SmartTextSegmenter.kt`)**:
   - Cập nhật trần độ dài mặc định lên 200 ký tự: `DEFAULT_MAX_CHARS = 200`, `SAFE_MAX_CHARS = 200`.
   - Tái cấu trúc hàm `splitSentenceIntoSubClauses()` trong `SmartTextSegmenter.kt`:
     - Tách vế câu ưu tiên tại các ranh giới dấu câu tự nhiên (`[,;:—–-]`).
     - Bảo vệ nguyên vẹn các từ ghép tiếng Việt (ví dụ: *"sẵn sàng"*, *"khiêm tốn"*, *"bình tĩnh"*) không bị chém ngang xương cơ học ở mốc 75 ký tự cũ.
     - Duy trì chuẩn hóa dấu hai chấm `puncNorm`: đoạn kết thúc bằng `:` được chuyển thành `:.` phục vụ ngữ điệu mô hình.
     - Cơ chế phòng hộ khẩn cấp: đối với câu dài bất thường > 200 ký tự không có dấu câu, tự động ngắt tại khoảng trắng (word boundaries), tuyệt đối không cắt giữa chừng một từ vựng.
   - File test: `PunctuationFirstSegmenterCoreTest.kt` (4/4 tests passed).

2. **Phase 02: Pipeline Integration & Pause Duration Verification**:
   - Tối ưu gom vế tự nhiên (prosody clause accumulation <= 75 ký tự trong ngưỡng an toàn 200 ký tự) giúp các cụm mở đầu ngắn (*"Ngày xưa,"*, *"Bất ngờ,"*) đi liền mạch với ngữ cảnh tiếp theo mà không bị băm vụn.
   - Xác thực toàn bộ câu chuyện mẫu 11 đoạn (*"Một trí khôn hơn trăm trí khôn"*) phân đoạn thành đúng **23 chunks tự nhiên** (giảm triệt để từ 26 chunks phân mảnh trước đây).
   - Kiểm tra và khớp chính xác các khoảng dừng: `PauseType.CLAUSE_BREAK` (180ms), `SENTENCE_BREAK` (400ms), `PARAGRAPH_BREAK` (650ms), và `TITLE_BREAK` (1000ms).
   - Cập nhật các assertions hồi quy trong `OptimizedTextChunkingAndStateTest.kt` thừa kế ngưỡng trần 200 ký tự.
   - File test: `PunctuationFirstPipelineIntegrationTest.kt` (3/3 tests passed).

---

### 📁 FILES QUAN TRỌNG ĐÃ TẠO / SỬA ĐỔI:
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SmartTextSegmenter.kt`
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/OptimizedTextChunkingAndStateTest.kt`
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PunctuationFirstSegmenterCoreTest.kt`
- `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/PunctuationFirstPipelineIntegrationTest.kt`
- `plans/261120-1628-punctuation-first-segmentation/plan.md`
- `plans/261120-1628-punctuation-first-segmentation/phase-01-punctuation-first-segmenter-core.md`
- `plans/261120-1628-punctuation-first-segmentation/phase-02-pipeline-integration-verification.md`
- `CHANGELOG.md`
- `.brain/session_log.txt`
- `.brain/session.json`
- `.brain/brain.json`
- `.brain/handover.md`

---

### ⏳ BƯỚC TIẾP THEO (Gợi ý):
1. **Kiểm tra nghe thực tế trên thiết bị vật lý**:
   - Trải nghiệm độ mượt mà ngữ điệu và ngắt nghỉ tự nhiên với các chunk 200 ký tự.
2. **Đóng gói phát hành bản cập nhật v1.13**:
   - Gắn tag git v1.13 và trigger pipeline CI/CD GitHub Actions.

---

### 🚀 TIẾP TỤC TRONG SESSION MỚI:
Khi quay lại làm việc, chỉ cần gõ `/recap` để khôi phục toàn bộ ngữ cảnh dự án.
