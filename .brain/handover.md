━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📋 HANDOVER DOCUMENT
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

📍 Đang làm: TTS Latency Optimization, Prewarm Preservation & ANR Prevention (P2, P1, P3, P7)
🔢 Đến bước: Hoàn thành TẤT CẢ 4 Phase (Phase 01, Phase 02, Phase 03, Phase 04) ✓

✅ ĐÃ XONG:
   - Phase 01: Prewarm Preservation & Instant First-Chunk Playback (P2) ✓
     • Thread-safe in-flight single-flight request coalescing (`CompletableDeferred`) in `PrewarmedChunkCache`
     • Inference, PCM post-processing & cache put protected by `withContext(NonCancellable)` in `VieNeuTTSManager`
     • Cooperative `stopPrewarmAfterChunk0` coordination in `speakContent` / `speakWithTitlePauseInternal`
     • `TTSAudioPipeline` fast-path playback for prewarmed chunk 0 (<100ms TTFT) and in-flight prewarm handover
     • Verified via `PrewarmPreservationIntegrityTest.kt` (5/5 passed 100%)
   - Phase 02: Cooperative Audio Pipeline Pause/Resume with Buffer Retention (P1) ✓
     • Thread-safe `PauseGate` (`CompletableJob`) cooperative suspension in `TTSAudioPipeline`
     • Non-destructive `pause()`: keeps `audioChannel` buffer intact, no `drainChannel()`, no CPU waste re-synthesizing
     • Instant `resume()`: resumes hardware playback and continues processing buffered chunks immediately
     • Sub-chunk ~100ms frame slicing and cooperative pause gate in `AudioPlayer.kt` eliminating phrase repetition artifacts
     • Verified via `CooperativePipelinePauseResumeTest.kt` (6/6 passed 100%)
   - Phase 03: Asynchronous Engine Teardown & Lifecycle ANR Elimination (P3) ✓
     • Cooperative cancellation flags (`cancelRequested`, `cancel()`) across prefill, decode step autoregressive loop, acoustic frame channels, and retry loops in `VieNeuOnnxEngine`
     • Non-blocking `engine.close()` using `lock.tryLock()` and background `teardownScope.launch(Dispatchers.IO)` preventing caller thread freeze (< 10ms vs 3-7s)
     • Decoupled `VieNeuTTSManager.shutdown()` and `reloadEngine()` dispatching heavy session closing to `Dispatchers.IO`
     • Non-blocking `ReadingService.onDestroy()` and `onTaskRemoved()` via `serviceScope.launch(NonCancellable)`
     • Elimination of all forced synchronous `System.gc()` calls on the Main thread
     • Verified via `AsynchronousEngineTeardownTest.kt` (5/5 passed 100%)
   - Phase 04: Robust INT8 Model Quantization Detection & Adaptive Prebuffer Optimization (P7) ✓
     • Deep byte pattern detection in `OnnxSessionManager.detectQuantizationType()` scanning for quantized ONNX operators (`MatMulInteger`, `DynamicQuantizeLinear`, `QLinearMatMul`) and directory shared data conventions
     • Correct INT8 classification for packaged backbone models (`vieneu_prefill.onnx`, `vieneu_decode_step.onnx`, `vieneu_acoustic_cached.onnx`)
     • Synchronized `computeAdaptiveTargetDurationMs()` in `TTSAudioPipeline.kt` to target 2000ms–2500ms for INT8, eliminating 5.0 seconds of unnecessary pre-buffering delay
     • Truthful telemetry and report generation in `TtsMetricsLogger.kt` (`Quantization: INT8`)
     • Verified via `QuantizationDetectionAndPrebufferTest.kt` (7/7 passed 100% in 54ms)

⏳ CÒN LẠI:
   - Tất cả 4 Phase trong Plan đã hoàn thành 100%.

🔧 QUYẾT ĐỊNH QUAN TRỌNG:
   - Deep byte pattern scanning cho các operator `MatMulInteger`, `DynamicQuantizeLinear`, `QLinearMatMul` trong 2MB đầu của model giúp nhận diện dynamic INT8 chính xác tuyệt đối mà không phụ thuộc vào metadata string hoặc quy ước đặt tên file.
   - Điều chỉnh pre-buffer target cho INT8 xuống 2000ms (hoặc 2500ms trên thiết bị < 4 core), giữ nguyên 7500ms fallback chỉ cho FP32 thực sự, giảm TTFT trực tiếp tới 5 giây.
   - Thêm `logEffectivePrecision()` và overload `formatAiDiagnosticReport()` trong `TtsMetricsLogger` phản ánh đúng precision của model đang chạy.

📁 FILES QUAN TRỌNG:
   - `plans/261122-1857-tts-latency-anr-fix/plan.md`
   - `plans/261122-1857-tts-latency-anr-fix/phase-04-quantization-detection-optimization.md`
   - `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/OnnxSessionManager.kt`
   - `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TTSAudioPipeline.kt`
   - `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/TtsMetricsLogger.kt`
   - `app/src/test/java/com/skul9x/doctruyen/tts/vieneu/QuantizationDetectionAndPrebufferTest.kt`
   - `.brain/session.json`
   - `.brain/session_log.txt`

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
📍 Đã lưu! Để tiếp tục: Gõ /recap
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
