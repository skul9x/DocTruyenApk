# Plan: Offline VieNeu-TTS v3 Turbo on Android (DocTruyenApk)

Created: 2026-08-18T19:35  
Status: 🟡 Awaiting Review

## Overview

Replace the built-in Android `TextToSpeech` engine in DocTruyenApk with **VieNeu-TTS v3 Turbo (ONNX int8)** running 100% offline on the phone. No server, no internet, no PC required after initial APK install.

## Architecture

```
┌─────────────────────────────────────────────────┐
│              DocTruyenApk (Android)              │
│                                                  │
│  ┌──────────┐   text   ┌─────────────────────┐  │
│  │ Reading  │ ───────► │ VieNeuTTSEngine.kt  │  │
│  │ Service  │          │                     │  │
│  │ (exists) │ ◄─────── │  1. sea-g2p (.so)   │  │
│  └──────────┘  PCM/WAV │  2. Tokenizer       │  │
│       │                │  3. ONNX Backbone    │  │
│       ▼                │  4. MOSS Codec       │  │
│  ┌──────────┐          └─────────────────────┘  │
│  │ AudioTrack│                                   │
│  │ /MediaP.  │  ← plays PCM audio chunks         │
│  └──────────┘                                    │
└─────────────────────────────────────────────────┘
```

## Model Assets (~290 MB total)

### VieNeu Backbone (onnx_int8/ — ~160 MB)
| File | Size | Purpose |
|------|------|---------|
| `vieneu_prefill.onnx` | 1.1 MB | Prefill (first-pass) session |
| `vieneu_decode_step.onnx` | 1.1 MB | Autoregressive decode step |
| `vieneu_acoustic_cached.onnx` | 6.9 MB | Acoustic model (cached KV) |
| `vieneu_backbone_shared.data` | 100 MB | Shared weight blob (int8) |
| `vieneu_v3_heads.npz` | 50 MB | Embedding + head matrices |
| `config.json` | 4 KB | Architecture config |
| `tokenizer.json` | 24 KB | Phoneme tokenizer vocab |

### MOSS Audio Codec (~87 MB)
| File | Size | Purpose |
|------|------|---------|
| `moss_audio_tokenizer_decode_full.onnx` | 668 KB | Full decoder graph |
| `moss_audio_tokenizer_decode_shared.data` | 43 MB | Decoder weights |
| `moss_audio_tokenizer_decode_step.onnx` | 344 KB | Streaming step decoder |
| `moss_audio_tokenizer_encode.onnx` | 800 KB | Encoder graph (voice clone) |
| `moss_audio_tokenizer_encode.data` | 43 MB | Encoder weights |
| `codec_browser_onnx_meta.json` | 20 KB | Streaming decode metadata |

### Denoiser (~41 MB)
| File | Size | Purpose |
|------|------|---------|
| `denoiser.onnx` | 41 MB | Post-processing audio denoiser |

## Tech Stack

- **Language**: Kotlin
- **ONNX Runtime**: `com.microsoft.onnxruntime:onnxruntime-android:1.20.0`
- **Phonemizer**: `sea-g2p` Rust library → cross-compiled to `.so` via `cargo-ndk`
- **Audio Playback**: `android.media.AudioTrack` (low-latency PCM streaming)
- **Architecture**: Existing MVVM + Service-based (ReadingService)
- **Model Hosting**: Bundled in APK `assets/` or downloaded on first launch

## Phases

| Phase | Name | Status | Est. Effort |
|-------|------|--------|-------------|
| 01 | Project Setup & Dependencies | ✅ Complete | 1 session |
| 02 | sea-g2p Phonemizer (Rust → Android .so) | ✅ Complete | 2 sessions |
| 03 | ONNX Inference Engine (Kotlin) | ✅ Complete | 3 sessions |
| 04 | Audio Pipeline & Playback | ✅ Complete | 2 sessions |
| 05 | Replace TTSManager & Integration | ✅ Complete | 2 sessions |
| 06 | Optimization & Final Testing | ⬜ Pending | 1 session |

**Total: ~11 sessions**

## Quick Commands
- Start Phase 1: `/code phase-01`
- Check progress: `/next`
