# Kế Hoạch Bổ Sung Hệ Thống Log Định Hướng Tối Ưu Tự Động Cho AI (per.md)

## 1. Mục tiêu Cốt Lõi
Xây dựng định dạng log có tính **tự chẩn đoán (Self-Diagnostic)**. Khi người dùng copy toàn bộ log đưa cho bất kỳ AI nào (Gemini, Claude, GPT), AI có thể **ngay lập tức chỉ ra chính xác nút thắt cổ chai (Bottleneck)** và đưa ra phương án tối ưu mã nguồn cụ thể mà không cần đoán mò.

---

## 2. Các Chỉ Số & Thẻ Định Danh Giúp AI Nhận Diện Vấn Đề

### 2.1. Phân Rã Tỷ Trọng Thời Gian (Time Breakdown & Latency Breakdown)
*AI sẽ nhìn vào % thời gian của từng công đoạn trong 1 chunk để biết nên tối ưu thuật toán nào:*

```text
⏱️ [Chunk Time Breakdown]
├─ G2P Phonemize: 8ms (1.5%)       ──> Nếu > 15%: Cần tối ưu SeaG2P C++ / Regex
├─ ONNX Backbone: 3400ms (70.2%)   ──> Nếu > 60%: Cần giảm context, quantize INT8 hoặc tối ưu thread
├─ ONNX Codec: 1200ms (25.1%)      ──> Nếu > 25%: Cần tối ưu decoder ONNX / SIMD
├─ Audio microFade + Silence: 2ms  ──> Nếu > 5%: Cần tối ưu mảng PCM ShortArray
└─ Buffer Wait / Queue: 230ms      ──> Nếu > 10%: AudioTrack blocking hoặc thread lock
```

---

### 2.2. Các Thẻ Cảnh Báo Chuẩn Hóa Cho AI (AI Diagnostic Flags)
Khi app phát hiện chỉ số vượt ngưỡng an toàn, app tự động đính kèm thẻ `⚠️ [AI_FLAG: ...]` để AI nhận diện ngay:

| Thẻ Cảnh Báo (AI Flag) | Điều kiện kích hoạt | AI sẽ đề xuất giải pháp gì? |
| :--- | :--- | :--- |
| `[AI_FLAG: SLOW_TTFT]` | TTFT > 4,000 ms | Kích hoạt **Pre-warming** sớm hơn hoặc cắt câu đầu ngắn hơn để phát ngay trong < 2s. |
| `[AI_FLAG: BUFFER_UNDERRUN]` | Buffer cạn khi đang phát | Tăng `minPreBufferChunks` từ 2 lên 3, hoặc tăng `minPreBufferDurationMs` lên 8000ms. |
| `[AI_FLAG: CPU_BOUND_RTF_HIGH]` | RTF trung bình > 1.0x | Chip quá yếu $\rightarrow$ Giảm số threads ONNX, bật INT8 quantization, hoặc fallback Google TTS. |
| `[AI_FLAG: THERMAL_THROTTLED]` | Thermal Status $\ge$ SEVERE | SoC bị quá nhiệt bóp xung $\rightarrow$ Chèn idle sleep giữa các chunk hoặc giảm xung inference. |
| `[AI_FLAG: MEMORY_LEAK_SUSPECTED]` | Native Heap tăng đều không hạ | Cần kiểm tra giải phóng tensor ONNX, đóng `OrtSession` cũ hoặc rò rỉ buffer PCM. |
| `[AI_FLAG: CHUNK_TOO_LONG]` | Chunk > 120 ký tự (RTF tăng vọt) | Tinh chỉnh `SmartTextSegmenter` để ngắt câu nhỏ hơn tại dấu phẩy/chấm phẩy. |
| `[AI_FLAG: G2P_OVERHEAD]` | G2P time > 50 ms | Tối ưu bảng từ vựng (dictionary lookup) hoặc bộ nhớ cache SeaG2P. |

---

### 2.3. Bảng Khuyến Nghị Tối Ưu Tự Động (AI Recommendation Matrix)
Cuối mỗi phiên đọc, app sẽ tự động tổng hợp thành mục **`🎯 AI ACTIONABLE RECOMMENDATION`**.

---

## 3. Mẫu Log Hoàn Chỉnh Giúp AI "Nhìn Là Biết Sửa Ở Đâu"

```text
[11:55:28] ⏱️ TtsMetrics:
========================== 📊 AI PERFORMANCE DIAGNOSTIC ==========================
[DEVICE PROFILE]
• Device: Xiaomi 21081111RG | SoC: Dimensity 920 (8 Cores) | RAM: 3.2GB Free
• Runtime Engine: VieNeu ONNX Turbo v3 | Threads: 2 | Quantization: FP32

[SESSION METRICS]
• Story: 1249 chars | 19 chunks | Total Speech Duration: 74.2s
• Total Inference Time: 68.1s | Overall RTF: 0.92x (Throughput: 18.3 chars/s)
• TTFT (Time to First Audio): 5,240 ms
• Buffer Starvations: 1 lần (Chunk #2 trễ 234ms)
• Memory Footprint: Native Heap: 112MB ➔ 152MB (Delta: +40MB, No Leak)
• Thermal Profile: 36.4°C ➔ 37.8°C (+1.4°C) | Throttling: NONE

[LATENCY PROFILING PER STAGE]
• G2P Processing:      1.2% tổng thời gian (Rất tốt, không cần tối ưu)
• ONNX Backbone:      68.4% tổng thời gian (Tải chính của hệ thống)
• ONNX Decoder Codec: 29.8% tổng thời gian (Bình thường)
• Audio Enqueue/PCM:   0.6% tổng thời gian (Rất nhẹ)

[ACTIVE AI FLAGS & BOTTLENECK ANALYSIS]
⚠️ [AI_FLAG: SLOW_TTFT] TTFT = 5240ms > 3500ms target. (Nguyên nhân: Pre-warming chưa xong khi bấm Play).
⚠️ [AI_FLAG: BUFFER_UNDERRUN] Chunk #2 bị khựng 234ms do Chunk #1 dài 117 chars cần 6.4s inference.

[🎯 AI ACTIONABLE RECOMMENDATIONS]
1. [OPTIMIZE_PREWARM]: Gọi prepareStory() ngay khi mở StoryDetailActivity để đạt TTFT = 0ms.
2. [OPTIMIZE_SEGMENTATION]: Tinh chỉnh SmartTextSegmenter để giới hạn độ dài chunk đầu tiên <= 50 chars.
3. [OPTIMIZE_PREBUFFER]: Tăng requiredDurationMs của pre-buffering từ 6000ms lên 7500ms đối với chip tầm trung.
==================================================================================
```

---

## 4. Kế Hoạch Triển Khai Trong Mã Nguồn

### 1. `DevicePerformanceHelper.kt` (Tạo mới):
- Đọc thông tin phần cứng: Chipset, số nhân, RAM trống, nhiệt độ máy, trạng thái Throttling.

### 2. `TtsMetricsLogger.kt` (Nâng cấp):
- Bổ sung logic tính toán % thời gian từng khâu (G2P vs Backbone vs Codec).
- Kiểm tra các ngưỡng giới hạn (Threshold Rules) để tự động sinh ra các **`[AI_FLAG: ...]`**.
- Xuất khối **`AI PERFORMANCE DIAGNOSTIC`** chuẩn định dạng khi kết thúc phiên đọc (`endSession`).
