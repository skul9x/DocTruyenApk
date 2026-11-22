# fix.md — Audit hiệu năng DocTruyenApk

- **Ngày audit:** 2026-11-21
- **Phạm vi:** toàn bộ app: Kotlin (`app/`), crate Rust (`sea-g2p-android/`), assets model, build, CI
- **Phương pháp:** đọc tĩnh toàn bộ hot path, build, CI, đồng thời kiểm tra trực tiếp assets (đếm op trong file `.onnx`, đọc `voices_v3_turbo.json`, `codec_browser_onnx_meta.json`).
- **Chưa chạy app, chưa profile.** File chỉ liệt kê các vấn đề đang tồn tại trong code hiện tại.
- **Ký hiệu độ tin cậy:** ✅ đã xác minh bằng đọc code hoặc assets. 🔬 là giả thuyết hợp lý, cần đo trước khi sửa.
- **Mức độ:** 🔴 cao, 🟡 trung bình, 🟢 thấp.
- Số liệu "ước tính" trong file này là tính từ kích thước model và code, không phải kết quả đo.

---

## 1. Tóm tắt

| Mức | Số mục | Ghi chú |
|---|---|---|
| 🔴 Cao | 8 | P1–P8. Gồm 3 lỗi logic ảnh hưởng thẳng độ trễ và độ đúng của phát âm thanh |
| 🟡 Trung bình | 16 | M1–M16 |
| 🟢 Thấp | 13 | L1–L13 |

**5 việc đáng làm trước** (rủi ro sửa thấp, lợi ích lớn):

1. **P2.** Prewarm bị huỷ khi bấm Play, kết quả đã tính bị vứt. Đây là nguyên nhân thực tế khiến TTFT cao dù đã có prewarm.
2. **P1.** Pause/Resume vứt toàn bộ buffer đã synth và có thể phát lặp lại một đoạn.
3. **P3.** `engine.close()` chạy trên main thread, chờ hết chunk NonCancellable đang chạy (3–7 giây), nguy cơ ANR.
4. **P4.** Tách câu, parse HTML và dựng TextView cho cả chương đều chạy trên main thread.
5. **P7.** Model đóng gói đã là INT8 nhưng runtime nhận nhầm thành FP32, nên chọn sai nhánh pre-buffer (7,5 giây) và telemetry hiển thị sai.

Điểm cần đo trước khi tối ưu sâu: **P6** (vòng acoustic 16 lần chạy ORT mỗi frame cộng matvec thuần Kotlin), **M3**, **M4**.

---

## 2. 🔴 Mức cao

### P1 🔴 ✅ Pause/Resume vứt buffer đã synth và có thể phát lặp lại một đoạn
**Vị trí:** `tts/vieneu/TTSAudioPipeline.kt:239-261` (pause/resume), `:384-492` (synthesis), `:580-595` (playback), `AudioPlayer.kt:124-166`.

**Vấn đề:**
- `pause()` huỷ `synthesisJob` và `playbackJob`, rồi `drainChannel()` vứt hết chunk đã synth (tối đa 4 chunk, tức vài chục giây CPU).
- `resume()` gọi lại `startPipeline(startIndex = currentChunkIndex)` và synth lại từ chunk đang phát.
- `currentChunkIndex` được gán trước khi ghi (dòng 580) và chỉ `+1` sau khi ghi xong (dòng 595). Khi pause giữa chừng, resume phát lại cả chunk đó từ đầu.
- Lệnh `track.write(WRITE_BLOCKING)` chạy trong `withContext(audioDispatcher)` không huỷ được. Sau `audioTrack.pause()` nó vẫn treo trên thread audio duy nhất. Khi `play()` lại, phần đuôi của chunk cũ được ghi nốt rồi mới tới chunk "mới". Hệ quả là nghe thấy lặp câu. 🔬 Cần xác nhận trên máy thật.

**Hậu quả:**
- Mỗi lần mất audio focus tạm thời (thông báo, cuộc gọi) hoặc bấm Tạm dừng đều tốn thêm 1 đến 4 chunk CPU (RTF ≈ 0,94 nên không còn dư địa).
- Gây nóng máy và có thể underrun ngay sau resume.
- Nguy cơ nghe lặp câu.

**Hướng sửa:**
- Pause chỉ gọi `audioPlayer.pause()` và giữ nguyên channel và synthesis job (backpressure của channel tự giới hạn).
- Resume chỉ gọi `audioPlayer.play()`.
- Nếu vẫn muốn huỷ synthesis lúc pause thì giữ lại các chunk đã synth và ghi nhận offset đã phát.
- Ghi PCM theo từng đoạn nhỏ (ví dụ 100–200 ms) để có thể huỷ và biết vị trí đang phát.

### P2 🔴 ✅ Bấm Play làm huỷ prewarm, kết quả đã tính bị vứt và engine bị khoá
**Vị trí:** `tts/vieneu/VieNeuTTSManager.kt:304-347` (đặc biệt `:321-326`), `:360-361`, `:409-410`; `TTSAudioPipeline.kt:424-432`.

**Vấn đề:**
- `speakContent()` và `speakWithTitlePauseInternal()` gọi `cancelPrewarm()` đầu hàm.
- Prewarm đang trong `withContext(NonCancellable) { eng.infer(...) }` nên vẫn chạy hết 3–7 giây và giữ `lock` của engine.
- Ngay sau đó `if (!isActive) break` chạy trước `PrewarmedChunkCache.put(...)`, nên kết quả tính xong bị bỏ.
- Pipeline phải đợi lock rồi synth lại đúng chunk 0 từ đầu.

**Hậu quả:** Luồng thường gặp là mở truyện, nội dung tải xong, prewarm bắt đầu, người dùng bấm Play sau 1–3 giây. Lúc đó TTFT = thời gian còn lại của prewarm + synth lại chunk 0. Prewarm làm TTFT xấu đi thay vì tốt lên. Đây là một lý do hợp lý cho TTFT 5,0 giây trong README. 🔬 Cần đo để chắc chắn.

**Hướng sửa:**
- Bỏ `cancelPrewarm()` trong lúc phát.
- Đặt `PrewarmedChunkCache.put` trước kiểm tra `isActive` để kết quả đã tính xong luôn được lưu.
- Tốt hơn: dùng `CompletableDeferred` theo từng chunk trong cache. Pipeline `await` chunk đang tính thay vì tính lại.
- Prewarm không cần `NonCancellable` nếu engine kiểm tra interrupt đủ dày (xem M6).

### P3 🔴 ✅ Đóng engine và pipeline trên main thread, chờ chunk đang chạy: nguy cơ ANR
**Vị trí:**
- `service/ReadingService.kt:317-323` (`initTTS` gọi `current.shutdown()`)
- `:351-360` (`switchPrecision`)
- `:378-402` (`onStartCommand` chạy trên main)
- `:626-656` (`onTaskRemoved`, `onDestroy`)
- `tts/vieneu/VieNeuTTSManager.kt:629-655`, `:732-745`
- `VieNeuOnnxEngine.kt:422` và `:1027-1049` (`close()` lấy cùng `lock` với `infer`)

**Vấn đề:** `close()` phải chờ `lock`, mà `infer` giữ lock suốt cả chunk (NonCancellable, 3–7 giây). Đổi engine, đổi precision hoặc thoát app trong lúc đang đọc hoặc prewarm sẽ chặn main thread. Ngưỡng ANR là 5 giây. Còn có thêm `System.gc()` ở các điểm đó (`:654`, `:743`).

**Hướng sửa:**
- Chuyển toàn bộ shutdown, reload và switch sang coroutine IO. Nhận lệnh trước rồi báo trạng thái `INITIALIZING`.
- Thêm cờ `cancelRequested` trong engine, kiểm tra ở mỗi frame và giữa các bước prefill/codec (xem M6).
- Bỏ các `System.gc()` thủ công.

### P4 🔴 ✅ Tách câu, parse HTML và dựng TextView cho cả chương chạy trên main thread
**Vị trí:**
- `StoryDetailActivity.kt:318-340`: `sanitizeHtml` (5 regex), `Html.fromHtml`, `toString()` rồi `tvContent.text = spannedContent`. Cả đoạn chạy sau `withContext(IO)` nên nằm trên Main.
- `TTSAudioPipeline.kt:196,232` gọi `SmartTextSegmenter.segment*` đồng bộ. Gọi từ `ReadingService.startReading` (`:454-480`) nên cũng ở main thread.
- `SmartTextSegmenter.kt:279-361`, `:546-595`.

**Vấn đề:**
- Mỗi chương chạy khoảng 10 lượt thao tác trên toàn bộ chuỗi (6 regex và 6 `replace` trong `htmlToStructuredText`, regex `(?i)` với 38 nhánh trong `ABBR_REGEX`, 2 regex số và giờ). Mỗi lượt cấp phát một bản copy.
- Số đo trong `CHANGELOG.md:225` cho thấy parse HTML tốn 100–300 ms với truyện dài.
- Cùng một nội dung bị tách câu 2 lần: một lần ở prewarm (`VieNeuTTSManager.kt:307`, trên Default) và một lần lúc phát (trên Main).
- `tvContent` là một TextView duy nhất chứa cả chương, nằm trong ScrollView (`activity_story_detail.xml:230-239`, `lineSpacingMultiplier=1.6`). Việc measure và layout của StaticLayout trên chương vài trăm KB cũng chạy ở Main, và không có virtualization.
- Bộ nhớ giữ 3 bản: `rawContent`, `Spanned`, `storyContent`.

**Hướng sửa:**
- Làm sanitize, `fromHtml` và `segment` trong `Dispatchers.Default`. Lưu danh sách `TextChunk` đã tách (cache theo `storyId`) và dùng chung cho prewarm lẫn pipeline.
- Hiển thị bằng RecyclerView theo đoạn (một item mỗi đoạn văn) hoặc `PrecomputedText.getTextFuture` thay vì một TextView khổng lồ. Cách này còn cho phép highlight đoạn đang đọc.

### P5 🔴 ✅ Service giữ engine khoảng 500 MB vĩnh viễn, và nạp ngay khi mở bất kỳ truyện nào
**Vị trí:** `StoryDetailActivity.kt:151-152` (`startService`), `ReadingService.kt:152-159` (`onCreate` gọi `initTTS()`), `:602-620` (`onTrimMemory`), toàn file (không có `stopSelf()` khi đọc xong).

**Vấn đề:**
- Chỉ cần mở trang chi tiết là service được tạo, và nó nạp toàn bộ model (nhiều giây CPU, RAM khoảng 500 MB theo README) ngay cả khi người dùng chỉ xem.
- Service không tự dừng sau khi đọc xong. Nó chỉ dừng khi vuốt xoá app khỏi recents.
- `onTrimMemory` chỉ xoá cache, không giải phóng engine.
- Trên máy 3 GB RAM, process nặng như vậy nằm nền dễ bị LMK giết.

**Hướng sửa:**
- Nạp engine lười: lúc bấm Play hoặc khi prewarm thực sự cần.
- Giải phóng engine sau N phút idle hoặc khi `TRIM_MEMORY_BACKGROUND` trở lên.
- Gọi `stopSelf()` khi kết thúc và không còn client bind.

### P6 🔴 🔬 Vòng acoustic: 17 lần chạy ORT mỗi frame, matvec thuần JVM, cấp phát tensor liên tục
**Vị trí:** `VieNeuOnnxEngine.kt:583-619` (16 lần `acousticSession.run` mỗi frame), `:660-685`, `:1168-1206` (`computeAcousticProjectionLogits`), `:591-596` và `:346-355` (tạo tensor và HashMap mỗi bước).

**Phân tích:**
- Frame codec là 80 ms (`downsample_rate 3840` ở 48 kHz). Với RTF ≈ 0,94, mỗi frame tốn khoảng 75 ms.
- Mỗi frame cần 16 lần `acousticSession.run`, 1 lần `decodeSession.run`, và 16 matvec 1024×768. Tổng matvec là 16×1024×768 ≈ **12,6 triệu MAC/frame**, chạy bằng vòng lặp Kotlin scalar. ART gần như không tự vector hoá được.
- Ước tính 6–12 ms/frame, tức khoảng 8–16% ngân sách mỗi frame. Đây là ước tính, cần đo.
- Mỗi bước còn tạo 2 `OnnxTensor` (`embTensor`, `posTensor`) và `FloatBuffer.wrap(array)`. Buffer heap sẽ bị ORT copy sang bộ nhớ native mỗi lần, khoảng 30 tensor/frame.
- Gợi ý gián tiếp: số đo trong `CHANGELOG.md:296` cho thấy INT8 chỉ nhanh hơn FP32 khoảng 8% (RTF 0,98 xuống 0,90). Nếu matmul của backbone chiếm đa số thì INT8 phải nhanh hơn nhiều. Điều này gợi ý chi phí nằm ở overhead mỗi lần gọi, ở phần FP32 của acoustic, và ở matvec Kotlin.

**Hướng sửa (đo từng bước, giữ bước nào cho kết quả tốt):**
1. Dùng tensor tái sử dụng: tạo `OnnxTensor` một lần từ direct `ByteBuffer`, ghi dữ liệu mới vào buffer rồi `run`.
2. Truyền `requestedOutputs` hoặc dùng `IoBinding` để tránh cấp phát output thừa.
3. Chuyển matvec 1024×768 sang native: crate Rust đã có sẵn trong app, thêm hàm JNI dùng NEON, hoặc dùng một ONNX MatMul nhỏ. Dùng `DirectByteBuffer` để không copy.
4. Chia sẻ ngữ cảnh giữa các frame (position tensors cố định theo `ch`).

**Cần đo trước:** thêm timer cho 4 phần trong `acousticFrame` (ORT run, tạo tensor, `sampleCode`, `topKSample`) rồi in ra qua `TtsMetricsLogger`. Dùng `simpleperf` để xem phân bổ thực.

### P7 🔴 ✅ Model đóng gói đã là INT8 nhưng runtime nhận nhầm thành FP32
**Vị trí:** `OnnxSessionManager.kt:74-98` (`detectQuantizationType`), `:255-258`, `TTSAudioPipeline.kt:525-528` và `:690-726`, `.github/workflows/android.yml:111-126`.

**Bằng chứng:**
- `assets/vieneu/backbone/vieneu_prefill.onnx` và `vieneu_decode_step.onnx` chứa **84 `MatMulInteger` và 48 `DynamicQuantizeLinear`**. `vieneu_acoustic_cached.onnx` chứa 5 và 4. Tức là chúng đã là dynamic INT8. `codec` thì là FP32 (không có op INT, có 68 Conv).
- File gốc không có metadata `quantization` (grep = 0) và đường dẫn không chứa "int8". `detectQuantizationType` trả về **FP32**.
- Chỉ khi CI sinh `backbone_int8` (có metadata do `inject_metadata` chèn) thì mới ra INT8.

**Hậu quả:**
- Bản build local nhận là FP32. `computeAdaptiveTargetDurationMs` chọn pre-buffer 7500 ms (`TTSAudioPipeline.kt:703-718`) thay vì 2000–2500 ms. Đây là nhánh làm TTFT cao.
- Telemetry và `DebugLogActivity` ghi sai "FP32". Nút chuyển FP32/INT8 trong Settings thực chất không đổi trọng số khi chỉ có một bộ model.
- Bước CI "Quantize" chạy `quantize_dynamic` lên model **đã** INT8, và nhân đôi asset trong APK (xem L10).

**Hướng sửa:**
- Chèn metadata `quantization=int8` vào chính các file trong `assets/vieneu/backbone` (hoặc đổi tên thư mục).
- Quyết định rõ chỉ cần một bộ model, hay thật sự giữ FP32 gốc để so sánh. Nếu giữ một bộ thì bỏ bước CI và nút chuyển precision.
- Nếu giữ hai bộ thì FP32 phải là model FP32 thật.

### P8 🔴 ✅ CI có thể phát hành nhầm APK
**Vị trí:** `.github/workflows/android.yml:143-160`, `app/build.gradle.kts:25-32`.

**Vấn đề:** Dự án bật ABI splits (`isUniversalApk = true`). Gradle xuất `app-arm64-v8a-release.apk`, `app-armeabi-v7a-release.apk`, `app-x86_64-release.apk`, `app-universal-release.apk`, và không có `app-release.apk`. Bước CI tìm `app-release.apk`, không thấy nên rơi vào nhánh `find ... | head -n 1`. Thứ tự do hệ thống file quyết định, có thể upload APK x86_64 hoặc APK chỉ một ABI lên Release.

**Hướng sửa:** Chọn tường minh `app-universal-release.apk` (hoặc từng APK theo ABI) và thất bại nếu không tìm thấy. Mỗi APK phải chứa asset model (khoảng 260 MiB), nên cân nhắc Play Asset Delivery hoặc tải model lần đầu thay vì nhân bốn lần.

---

## 3. 🟡 Mức trung bình

### M1 🟡 ✅ Trích xuất asset: dung lượng gấp đôi, không an toàn khi bị ngắt, tốn thời gian mỗi lần mở
**Vị trí:** `OnnxSessionManager.kt:357-434`, `SeaG2P.kt:105-116`, `app/build.gradle.kts:106-108`.
- Assets đo được khoảng **260 MiB** (backbone 158, codec 43, `sea_g2p.bin` 60). Cả APK lẫn `filesDir/vieneu` đều giữ bản sao, nên cần khoảng **520 MiB** dung lượng.
- `copyAssetFile` coi file đã tồn tại là hợp lệ khi `length() > 0`. Nếu app bị kill giữa lúc copy thì file cụt vẫn được dùng, và model hỏng vĩnh viễn. Không ghi ra file tạm rồi đổi tên, không so version hay kích thước.
- Khi cập nhật app với model mới nhưng cùng tên file, file cũ không bao giờ được thay.
- Mỗi lần khởi động gọi `AssetManager.list` đệ quy nhiều lần dù đã trích xong.
- `noCompress` không có `bin`, nên `sea_g2p.bin` (60 MiB) bị nén trong APK, lần đầu phải giải nén.
- Lần chạy đầu không có tiến độ hay thông báo. Trạng thái `INITIALIZING` nhưng UI vẫn cho bấm "Đọc truyện".

**Hướng sửa:** Ghi file `.tmp` rồi `rename`. Lưu `versionCode` hoặc checksum và chỉ trích khi khác. Thêm `bin` vào `noCompress`, và cho Rust mmap thẳng từ file (xem M2, L9). Hiển thị tiến độ ở lần chạy đầu.

### M2 🟡 ✅ Nạp `vieneu_v3_heads.npz`: đỉnh heap khoảng 100 MiB trở lên, và nạp lại mỗi lần đổi precision
**Vị trí:** `NpzReader.kt:132-199` (`FloatArray(totalElements)` tại `:173`), `:47-58`, `VieNeuOnnxEngine.kt:1260-1272`.
- `audio_emb` có 16×1024×768 float = 50,3 MB. Reader cấp phát `FloatArray` 50 MB, rồi `getChannelFlatArrays` copy tiếp 16 slice (thêm 50 MB). Đỉnh tạm thời là **~100 MB heap Java** cộng `text_emb` và overhead. Manifest không đặt `largeHeap`. Máy RAM thấp dễ OOM ngay lúc nạp.
- `reloadEngine` gọi lại `VieNeuOnnxEngine.create`, parse lại npz dù embedding không phụ thuộc precision.

**Hướng sửa:** Đọc thẳng vào các mảng theo kênh (không đi qua mảng lớn trung gian), hoặc lưu `audio_emb` thành file raw và `mmap`. Tách phần embedding ra khỏi vòng đời session để không nạp lại khi đổi precision. Xoá getter `audioEmbeddings` 3D (`:113-129`) nếu không dùng.

### M3 🟡 🔬 `SessionOptions` dùng chung cho cả 4 session, không cache model đã tối ưu
**Vị trí:** `OnnxSessionManager.kt:181-236`, `:258-264`.
- Một `SessionOptions` duy nhất cho prefill, decode, acoustic và codec.
- `ALL_OPT` chạy lại trên mọi lần mở app. Không có `setOptimizedModelFilePath`, nên thời gian tạo session (4 session) không được cache.
- EP XNNPACK gần như không áp dụng cho graph INT8: `MatMulInteger` và `DynamicQuantizeLinear` không nằm trong tập op XNNPACK EP hỗ trợ. XNNPACK EP thích hợp hơn cho codec FP32 (68 Conv, 448 MatMul). Cấu hình đồng nhất khiến cả hai bị thiệt. Đây là giả thuyết, cần A/B.
- XNNPACK EP có thread pool riêng. Cộng với intra-op pool của CPU EP thì có thể dư luồng. Cần đo.
- `allow_spinning=0` với model acoustic rất nhỏ (1 layer, 1 token/lần chạy, 16 lần mỗi frame) có thể khiến mỗi lần `run` phải đánh thức luồng. Cần đo.

**Hướng sửa:** Tách options theo session. Ví dụ acoustic dùng 1–2 luồng và cho spinning, backbone dùng CPU EP với 3–4 luồng, codec thử XNNPACK. Lưu model đã tối ưu vào `filesDir` ở lần đầu rồi nạp ở `BASIC_OPT` lần sau.

### M4 🟡 🔬 Prefill và decode cùng trỏ vào một file weights, có thể bị nạp hai lần
**Vị trí:** `OnnxSessionManager.kt:261-264`.
- `vieneu_prefill.onnx` và `vieneu_decode_step.onnx` đều tham chiếu `vieneu_backbone_shared.data` (104 MB). ORT tạo bản weight riêng cho mỗi session.
- Nếu đúng thì RAM native cao hơn khoảng 100 MB so với cần thiết. Con số README (~520 MB) khớp với khả năng này.

**Cách đo:** `Debug.getNativeHeapAllocatedSize()` trước và sau mỗi `createSession`.
**Hướng sửa:** `SessionOptions.addExternalInitializers` để dùng chung tensor initializer giữa hai session.

### M5 🟡 ✅ Codec giải mã nguyên chunk một lần, không stream, xuất stereo rồi lấy trung bình
**Vị trí:** `VieNeuOnnxEngine.kt:861-911`; assets có `moss_audio_tokenizer_decode_step.onnx` nhưng không được dùng.
- Decoder xuất 2 kênh (`channels: 2` trong meta) rồi app trung bình thành mono (`:894-904`), tức tính thừa một nửa đầu ra. Còn tạo thêm 3 mảng `FloatArray` cùng cỡ.
- Phải đợi toàn bộ frame của chunk xong rồi mới decode, nên TTFT bao gồm cả thời gian decode. Không huỷ được giữa chừng.

**Hướng sửa:** Dùng `decode_step` (streaming) để decode theo từng nhóm frame và cho phát sớm. Xuất mono từ model nếu có thể.

### M6 🟡 ✅ Huỷ không thật sự tức thời: `NonCancellable` bao toàn bộ chunk
**Vị trí:** `TTSAudioPipeline.kt:424-427`, `VieNeuTTSManager.kt:321-323`, `VieNeuOnnxEngine.kt:325` và `:584` (hai điểm kiểm tra interrupt duy nhất).
- README ghi "dừng dưới 50 ms". Âm thanh thì dừng ngay, nhưng CPU vẫn chạy hết chunk (3–7 giây), và start lại phải chờ lock.
- Prefill và codec decode không có điểm kiểm tra huỷ nào.

**Hướng sửa:** Thêm cờ huỷ `@Volatile` trong engine, kiểm tra ở mỗi frame và giữa prefill/codec. ORT có cơ chế terminate qua `RunOptions` để dừng giữa một lần `run`. Cần kiểm tra Java API của bản 1.20.0 có expose hay không.

### M7 🟡 ✅ `AudioPlayer` tạo lại `AudioTrack` ở mọi lần stop. Thread và chế độ audio chưa tối ưu
**Vị trí:** `AudioPlayer.kt:205-220`, `:67-108`, `:20-27`.
- `stop()` luôn đặt `needsReinit = true`. Seek, đổi truyện và stop đều release rồi tạo lại track.
- Chưa dùng `setPerformanceMode(PERFORMANCE_MODE_POWER_SAVING)`. Đọc truyện nền nhiều giờ nên ưu tiên tiết kiệm pin với buffer lớn hơn.
- Thread audio dùng `Thread.MAX_PRIORITY`. Nên dùng `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)`.
- Synthesis và playback cùng chạy trên `Dispatchers.Default` (nhóm luồng dùng chung với coroutine khác). Tác vụ tính toán nặng chạy vài giây nên dùng một dispatcher riêng một luồng với priority phù hợp.

### M8 🟡 🔬 WakeLock hết hạn sau 10 phút, không gia hạn
**Vị trí:** `ReadingService.kt:186-197` (`acquire(10 * 60 * 1000L)`), `:261-278`.
- Chỉ acquire khi trạng thái đổi sang `PLAYING`. `notifyStateChange` loại bỏ trùng trạng thái, nên suốt một chương dài chỉ acquire một lần.
- Một chương đọc hơn 10 phút thì lock hết hạn trong lúc tắt màn hình. CPU có thể ngủ trong các khoảng lặng hoặc khi buffer cạn, rồi synthesis tụt tốc độ. Bản thân AudioTrack giữ thiết bị thức khi có audio, nên rủi ro nằm ở các khoảng trống.
- 🔬 Cần test chương dài (≥ 20 phút) với màn hình tắt.

**Hướng sửa:** Gia hạn định kỳ (khi mỗi chunk bắt đầu), hoặc dùng `acquire()` không timeout và `release()` chắc chắn trong `finally`.

### M9 🟡 ✅ Đọc sysfs và `ActivityManager` ở mỗi lần bắt đầu đọc, một số chạy trên main thread
**Vị trí:** `TTSAudioPipeline.kt:150` (main thread qua `startReadingChunks`), `:296`, `:502`, `DevicePerformanceHelper.kt:138-166`, `OnnxSessionManager.kt:190`.
- `getBigCoreCount()` đọc tối đa 8 file `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq` mỗi lần gọi. `getDeviceProfile(context)` gọi `ActivityManager.getMemoryInfo` chỉ để lấy `cpuCores`.
- `TtsMetricsLogger.startSession(context, ...)` (`:157`) thu thập thêm memory snapshot và thermal ở main thread.

**Hướng sửa:** Tính một lần khi khởi động engine và lưu vào field. Dùng `Runtime.availableProcessors()` cho `cpuCores`.

### M10 🟡 ✅ Tìm kiếm có thể bị bỏ qua, và kết quả cũ có thể ghi đè
**Vị trí:** `MainActivity.kt:145-163` (debounce), `:276-279` (`if (isLoading) return`).
- Nếu người dùng gõ lúc đang tải (ví dụ lượt load đầu), sau 500 ms `currentSearch = query` đã được gán nhưng `loadStories()` thoát ngay vì `isLoading`. Query không bao giờ được gửi. Lần gõ tiếp theo với cùng chuỗi cũng không kích hoạt lại (`query != currentSearch` sai).
- Response của query cũ vẫn gọi `submitList` khi về sau, nên có thể hiện kết quả cũ.
- `loadJob?.cancel()` chỉ được gọi ở nhánh IME search, không gọi ở nhánh debounce hay nút sắp xếp.

**Hướng sửa:** Mỗi lần đổi query thì huỷ `loadJob` và bắt đầu lại (kiểu `collectLatest`). Bỏ cờ `isLoading` dùng làm lớp chặn.

### M11 🟡 🔬 Tầng mạng: không cache, không retry, `getClient` không an toàn luồng
**Vị trí:** `network/ApiService.kt:104-157`, `:159-170`.
- OkHttp không có `Cache`, nên danh sách và nội dung truyện được tải lại mỗi lần. App quảng cáo "offline" nhưng nội dung truyện luôn cần mạng. Không có cache nội dung đã mở.
- Timeout 30 giây × 3, không retry hay backoff. Trên mạng yếu UI chỉ hiện spinner.
- `getClient()` kiểm tra và gán `retrofit` không có đồng bộ, và được gọi từ `Dispatchers.IO` nhiều coroutine. Có thể tạo hai `OkHttpClient` (hai connection pool).
- `Story` có cả `content` và `content_text` (`:18-24`). 🔬 Kiểm tra payload của `action=list` xem có kèm nội dung đầy đủ cho mỗi truyện không. Nếu có thì tốn băng thông, RAM và thời gian parse Gson.

**Hướng sửa:** Thêm `Cache` 10–20 MB cho OkHttp, lưu nội dung truyện đã mở vào file/DB (cũng giúp prewarm khi mở lại), bọc `getClient` bằng `synchronized` hoặc `lazy`.

### M12 🟡 ✅ `HostingVerifier` rò rỉ WebView và có thể gọi callback hai lần
**Vị trí:** `network/HostingVerifier.kt:31-115`.
- WebView được tạo trong `Dialog` rồi chỉ `dismiss()`, không gọi `webView.stopLoading()` và `destroy()`. WebView (kèm renderer process, vài chục MB) tồn tại cho đến khi GC dọn.
- Với lỗi main-frame, cả `onReceivedError(…WebResourceRequest…)` và `onReceivedError(…errorCode…)` (bản deprecated, cũng được gọi trên API ≥ 23) đều gọi `onResult(false)`, nên `onResult` có thể chạy hai lần.
- Mỗi lần xác thực lại khởi tạo WebView mới (chậm và nặng).

**Hướng sửa:** `destroy()` WebView trong mọi nhánh kết thúc, dùng cờ `done` để chỉ gọi callback một lần, và tái sử dụng cookie đã có thay vì dựng WebView khi chưa cần.

### M13 🟡 ✅ Thanh tiến trình `indeterminate` chạy liên tục khi đang đọc
**Vị trí:** `res/layout/activity_story_detail.xml:162-173` (`android:indeterminate="true"`), `StoryDetailActivity.kt:120-127` (`progressBar.progress = progress`).
- Giá trị `progress` được đặt nhưng bị bỏ qua vì chế độ indeterminate. Animation vô tận khiến màn hình redraw liên tục suốt lúc đọc khi sáng màn hình, tốn pin và GPU.
- Người dùng không thấy tiến độ thật.

**Hướng sửa:** `android:indeterminate="false"`, `max=100`, và dùng `setProgressCompat(progress, true)`. Chỉ cho indeterminate trong giai đoạn Buffering.

### M14 🟡 ✅ Engine Google TTS: `QUEUE_FLUSH` cho mỗi segment làm mất khoảng lặng và tạo khoảng trống
**Vị trí:** `tts/GoogleTTSManager.kt:393-413`, `:228-253`.
- Mỗi segment được `speak(..., QUEUE_FLUSH)` rồi `playSilentUtterance(..., QUEUE_ADD)`. Khi `onDone(seg_i)` kích hoạt `playSegment(i+1)` với `QUEUE_FLUSH`, nó **xoá luôn silence đã xếp hàng của segment i**. Cài đặt "khoảng dừng" không có tác dụng đúng như thiết kế.
- Chỉ có một utterance trong hàng đợi tại mỗi thời điểm, nên giữa hai câu có độ trễ của callback binder cộng thời gian engine khởi động câu mới.
- `onDone` chạy trong `synchronized(this)` trên luồng binder.

**Hướng sửa:** Xếp trước 2–3 utterance bằng `QUEUE_ADD`, chỉ `QUEUE_FLUSH` ở utterance đầu khi bắt đầu hoặc khi seek.

### M15 🟡 ✅ Backup mặc định với khoảng 520 MiB trong `filesDir`
**Vị trí:** `AndroidManifest.xml:20-22`, `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` (cả hai là file mẫu, chưa cấu hình).
- `allowBackup="true"` và không loại trừ `files/vieneu/`. Auto Backup có hạn mức 25 MB nên việc sao lưu sẽ thất bại, kéo theo việc mất luôn SharedPreferences khi khôi phục. Chuyển máy (device-transfer) có thể cố chép cả trăm MB model.

**Hướng sửa:** Loại trừ `files/vieneu/` trong cả hai file rule (hoặc đặt model ở `noBackupFilesDir`).

### M16 🟡 ✅ Cache prewarm có thể phát PCM lỗi thời sau khi đổi cài đặt
**Vị trí:** `PrewarmedChunkCache.kt:9-13` (khoá = storyId, voice, chunkIndex), `VieNeuTTSManager.kt:327-345`.
- PCM trong cache đã nhúng sẵn khoảng lặng cuối chunk theo `PauseConfig` lúc prewarm. Khoá cache không có `PauseConfig` hay nhiệt độ. Cache chỉ bị xoá khi đổi giọng hoặc reload engine.
- Người dùng đổi "khoảng dừng" trong Settings rồi đọc lại truyện sẽ vẫn nghe 2 chunk đầu với pause cũ.

**Hướng sửa:** Lưu PCM giọng nói và silence tách riêng (ghép lúc phát), hoặc thêm hash cấu hình vào khoá.

---

## 4. 🟢 Mức thấp

- **L1 ✅ `maxExpectedFrames` compile Regex mỗi lần gọi.** `VieNeuOnnxEngine.kt:1218` tạo `Regex("""\s+""")` trong hàm, và hàm này chạy 2 lần mỗi pass (`:299`, `:428`) cộng thêm mỗi lần retry. Đưa regex ra `companion object`.
- **L2 ✅ `topKSample` sắp xếp thừa.** `:794-798`: chọn và sắp xếp đầy đủ 256 phần tử trong khi chỉ dùng `effK = 25`. Mỗi lần còn copy 1024 logit, scale và fill chỉ mục. Việc này chạy 16 lần mỗi frame. Chỉ cần partition `k`.
- **L3 ✅ `TtsMetricsLogger` tốn O(n) mỗi lần cập nhật.** `TtsMetricsLogger.kt:653-678`: ba `sumOf` và `ArrayList(chunkTimings)` mỗi lần, được gọi vài lần mỗi chunk, cộng thêm `CopyOnWriteArrayList.set` copy cả mảng. Chương dài (cả ngàn chunk) tạo ra O(n²) công việc nhỏ lặp lại. Cập nhật cộng dồn và chỉ dựng snapshot khi cần hiển thị.
- **L4 ✅ Logging bật mặc định, ở cả release, và công tắc không có tác dụng.** `UserConfig.kt:164` (`DEFAULT_DEBUG_LOGGING = true`), `UserConfig.kt:196` và `SettingsActivity.kt:647` là nơi duy nhất dùng giá trị này. `DebugLogger.log` không bao giờ đọc nó. Nhiều chỗ còn dựng chuỗi dài trước khi gọi log (`StoryDetailActivity.kt:449-456`, `:541-545`, `StoryAdapter.kt:115,126` mỗi lần bind ảnh).
- **L5 ✅ `DebugLogActivity` dựng lại toàn bộ log mỗi entry.** `DebugLogActivity.kt:84-99`: mỗi log mới phát một `List` 500 phần tử rồi `joinToString` và `setText` lại cả khối (có thể hàng trăm KB). Trong lúc đọc, log đến vài lần mỗi giây. Nên dùng RecyclerView hoặc giới hạn tần suất cập nhật.
- **L6 ✅ `StoryAdapter` cấp phát nhiều khi bind.** `StoryAdapter.kt:39-48` (`AnimationUtils.loadAnimation` mỗi item mới), `:84-92` (`LazyHeaders` và `AuthenticatedGlideUrl` mỗi lần bind), `:101-102` (`Priority.HIGH` cho mọi item làm mất ý nghĩa ưu tiên, `DiskCacheStrategy.ALL` lưu cả bản gốc lẫn bản biến đổi). Tạo header một lần theo cookie. Dùng `Glide.RecyclerViewPreloader` nếu cần.
- **L7 ✅ Mã chết và tương thích thừa.** `CacheTensor` và `Pool` (`VieNeuOnnxEngine.kt:1055-1158`, chỉ `Pool.clear()` còn được gọi), các constructor và overload "legacy/compat" (`:39-63`, `:688-712`).
- **L8 ✅ `VoicePresets.loadFromContext` parse lại JSON 180 KB nhiều lần.** Gọi từ `VieNeuOnnxEngine.create` (`:1280`) và từ `SettingsActivity.kt:387` trên main thread, mà không kiểm tra đã nạp. Thêm cờ `loaded`.
- **L9 ✅ Crate Rust: cấu hình release và độ an toàn JNI.**
  - `Cargo.toml` không có `[profile.release]` (không `opt-level`, `lto`, `codegen-units=1`, `strip`, `panic="abort"`). `.so` hiện 6,9 / 4,4 / 6,4 MB mỗi ABI.
  - Crate kéo cả module Thái, Indonesia và `rayon` dù app chỉ dùng tiếng Việt.
  - `nativePhonemize` dùng `.read().unwrap()` và `.expect("Failed to create Java string")` (`src/lib.rs`). Panic xuyên ranh giới FFI sẽ abort cả process.
  - Có thể để Rust `mmap` thẳng từ APK khi `sea_g2p.bin` được lưu không nén (`openFd`), tránh copy 60 MiB.
- **L10 ✅ Đóng gói.** `app/build.gradle.kts:64-73`: chưa có `isShrinkResources = true`. Cùng lúc có `ndk.abiFilters` và `splits.abi` (`:20-31`), cần kiểm tra cảnh báo của AGP. Bản universal chứa cả 3 ABI. CI sinh thêm `backbone_int8` kèm bản sao của `config.json`, `tokenizer.json` và `vieneu_v3_heads.npz` (52 MB) nên APK còn nặng hơn. `.gitignore` bỏ qua `*.bin`, `*.data`, `*.npz`, nên checkout sạch trên CI có thể thiếu model nếu không dùng Git LFS (cần xác nhận).
- **L11 ✅ Cache phoneme ít tác dụng.** `SeaG2P.kt:35-42,167-191`: khoá là cả chunk (tối đa 100 ký tự, trong khi chunk thật là câu đến 200 ký tự) nên với truyện thật hầu như không bao giờ trúng. Chỉ có ích cho vài chuỗi ngắn lặp lại như "Vâng.".
- **L12 ✅ Đường dẫn máy dev hard-code.** `SeaG2P.kt:54,58,133`, `sea-g2p-android/src/lib.rs` (`new_default`), `sea-g2p-android/build.sh`. Vô hại khi chạy nhưng là rác và lộ thông tin đường dẫn cá nhân.
- **L13 ✅ `PrewarmedChunkCache` trộn ba cơ chế đồng bộ.** `PrewarmedChunkCache.kt:25-27,45-62`: `ConcurrentHashMap`, `ConcurrentLinkedDeque` và `synchronized(this)` cùng lúc, trong khi sức chứa chỉ 4 phần tử. `getChunksForStory` còn duyệt `cache.forEach` không có khoá. Một `LinkedHashMap` bọc `synchronized` là đủ.

---

## 5. Hạn chế của project (không phải bug)

1. **Không có dư địa hiệu năng.** RTF trung bình 0,94 (có câu 1,01). CPU chạy khoảng 94% thời gian, máy nóng. Chưa có cơ chế thích ứng: nếu RTF > 1 kéo dài hoặc `thermal status ≥ MODERATE` thì tự tăng buffer, giảm `maxNewFrames`, giảm luồng, hoặc đề nghị chuyển Google TTS. Hiện chỉ ghi log.
2. **Benchmark chưa đủ.** README chỉ đo trên một máy Android 10, một truyện, 19 chunk. Chưa ghi rõ màn hình bật hay tắt. Khi tắt màn hình, tiến trình foreground service thường bị đẩy khỏi nhóm CPU `top-app`, có thể mất các nhân lớn, nên RTF thực tế có thể tệ hơn. Tính năng "chạy nền, tắt màn hình" chưa có số liệu 🔬. Chưa đo: cold start, đỉnh RAM, mức tiêu thụ pin, và kết quả trên máy yếu (2 nhân lớn).
3. **Chi phí phụ thuộc giọng đọc.** Mỗi chunk đều nạp lại phần "voice prompt" (`codes`, từ 12 đến 98 frame tuỳ giọng, trung vị khoảng 57) vào prefill. Mã tham chiếu nằm sau đoạn text trong prompt, nên không dùng lại được prefix. Giọng "Thiền Tâm Đức" (98) và "Adam bựa" (91) nặng hơn "Xuân Vĩnh" (12) rõ rệt. Có thể cắt ngắn mã tham chiếu nếu chất lượng chấp nhận được 🔬.
4. **Chunk dài.** `DEFAULT_MAX_CHARS = 200` và gom mệnh đề tới 75 ký tự. Chunk đầu sau tiêu đề chưa được rút ngắn. Nên dùng kích thước thích ứng: chunk đầu sau tiêu đề ngắn (khoảng 60–80 ký tự) để TTFT thấp, các chunk sau dài hơn để tận dụng thông lượng.
5. **Phụ thuộc mạng và hosting.** Nội dung truyện luôn lấy từ máy chủ `free.nf` qua cookie `__test` của anti-bot, lấy bằng WebView ẩn. Cơ chế này dễ hỏng và không hoạt động khi offline. Chưa có thư viện truyện offline, cache nội dung hay lưu vị trí đang đọc.
6. **Tái lập build khó.** Model lớn bị `.gitignore` và README không có đường dẫn tải. CI không chạy test (không có bước `./gradlew test`) dù repo có 83 file test, đa số là JVM unit test dùng mock nên không đo hiệu năng thật.
7. **Model nằm hết trong APK.** Không tách model ra khỏi APK (App Bundle, Play Asset Delivery hay tải lần đầu), nên mỗi APK (kể cả từng bản split theo ABI) đều chứa đủ khoảng 260 MiB model.

### README lệch với code
- README nhắc `denoiser.onnx` và thư mục `assets/vieneu/denoiser/`, nhưng thư mục này không có và không có đoạn code nào dùng denoiser.
- README ghi "dừng tức thời < 50 ms" (xem M6), "8 giọng" ở bảng so sánh nhưng 25 giọng ở phần khác.
- README ghi "70+ unit test" bao phủ cả chu kỳ service nhưng CI không chạy chúng.

---

## 6. Ngoài phạm vi hiệu năng nhưng nên biết

- `HostingVerifier.kt:72` ghi cookie xác thực vào `DebugLogger`. Màn hình log có nút Sao chép và Chia sẻ, nên có thể rò cookie qua log.
- `AndroidManifest.xml:21` `usesCleartextTraffic="true"`, trong khi `base_url` do người dùng cấu hình và cookie được gửi kèm. HTTP thuần sẽ lộ cookie.
- WebView bật JavaScript và `domStorage` để nạp URL do người dùng đặt.
- `allowBackup="true"` (xem M15).

---

## 7. Kế hoạch đo (làm trước khi tối ưu sâu P6, M3, M4, M5)

| Cần biết | Cách đo |
|---|---|
| Phân bổ thời gian mỗi frame | Thêm timer quanh 4 phần của `acousticFrame` và `synthesizePass.decodeSession.run`, ghi vào `TtsMetricsLogger` |
| Hot spot CPU thật | `simpleperf record --call-graph dwarf -p <pid>`, hoặc Perfetto (CPU + sched) |
| Thời gian tạo session | `System.nanoTime()` quanh từng `createSession`; thử `enableProfiling` của ORT |
| Bộ nhớ native theo thành phần | `dumpsys meminfo com.skul9x.doctruyen` và `Debug.getNativeHeapAllocatedSize()` trước và sau mỗi bước nạp |
| Khởi động lạnh | Macrobenchmark `StartupTimingMetric`, cộng log thời gian engine sẵn sàng |
| Ảnh hưởng màn hình tắt | Chạy cùng một truyện ở hai chế độ, ghi RTF, underrun và `thermal status`, kèm `dumpsys power` |
| Jank và ANR | Bật `StrictMode` (thread + VM policy) ở bản debug, mở truyện dài, đổi engine/precision khi đang đọc |
| Pause/resume | Test thủ công: pause giữa chunk, resume, nghe có lặp câu không; đo CPU lúc resume |

---

## 8. Thứ tự sửa đề xuất

1. **Đợt 1, logic, nhỏ và an toàn:** P2, P1, P3, L1.
2. **Đợt 2, trải nghiệm và bộ nhớ:** P4, P5, M13, M10, M8, M12.
3. **Đợt 3, build và đóng gói:** P7, P8, M1, M15, L10, M16.
4. **Đợt 4, đo rồi mới tối ưu engine:** thực hiện mục 7, sau đó P6, M3, M4, M5, M6, M2.
5. **Đợt 5, dọn dẹp:** L2–L9, L11–L13, M7, M9, M11, M14.

Sau mỗi đợt chạy `./gradlew testDebugUnitTest` và đo lại RTF, TTFT, underrun và đỉnh RAM bằng `TtsMetricsLogger`. Cần thêm bước chạy test vào CI trước khi sửa lớn (xem mục 5.6).

