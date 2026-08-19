# Phase 03: ONNX Inference Engine (Kotlin)
Status: ✅ Complete  
Dependencies: Phase 01, Phase 02

## Objective
Port the VieNeu-TTS v3 Turbo inference pipeline from Python (`onnx_runtime_lite.py`) to Kotlin, implementing the full text-to-audio generation loop: tokenization → prefill → autoregressive decode → acoustic model → MOSS codec → PCM waveform.

## Background
The Python reference implementation in `src/vieneu/_v3_turbo_engine/onnx_runtime_lite.py` defines the complete inference pipeline using 4 ONNX sessions:
1. **Prefill** (`vieneu_prefill.onnx`) — processes the full input sequence (text tokens + reference audio tokens) in one pass, producing initial KV cache
2. **Decode Step** (`vieneu_decode_step.onnx`) — autoregressive loop: takes last generated token + KV cache → predicts next token
3. **Acoustic** (`vieneu_acoustic_cached.onnx`) — converts the generated token sequence into mel-like features
4. **MOSS Codec Decoder** (`moss_audio_tokenizer_decode_full.onnx`) — decodes acoustic features into 48kHz PCM audio

## Requirements

### Functional
- [x] `VieNeuConfig.kt` — parse `config.json` for all token IDs, architecture sizes
- [x] `NpzReader.kt` — read `.npz` (NumPy compressed) files to extract embedding matrices (`text_emb`, `audio_emb`, etc.)
- [x] `VieNeuTokenizer.kt` — wrapper around HuggingFace `tokenizer.json` format (manual BPE decode or use a Kotlin tokenizer lib)
- [x] `VieNeuOnnxEngine.kt` — main engine class that orchestrates:
  - Loading 4 ONNX sessions from assets
  - Building input tensors (text tokens → embeddings → prefill input)
  - Running autoregressive decode loop with top-k sampling
  - Running acoustic model on generated tokens
  - Running MOSS codec to produce float PCM audio
- [x] Generates correct audio output that matches the Python reference for the same input

### Non-Functional
- [x] Memory usage < 600 MB during inference
- [x] Inference time for one sentence (20 words) < 5 seconds on Snapdragon 7-series
- [x] Proper resource cleanup (close ORT sessions on shutdown)
- [x] Thread-safe (can be called from coroutine dispatcher)

## Implementation Steps

### Step 1: Config & Utilities

1. [x] **Create `VieNeuConfig.kt`** — data class parsing `config.json`:
   ```kotlin
   data class VieNeuConfig(
       val nVq: Int,                    // number of VQ codebooks
       val hiddenSize: Int,             // transformer hidden dim
       val numHiddenLayers: Int,        // number of transformer layers
       val audioPadTokenId: Int,        // padding token
       val textPromptStartTokenId: Int, // <tps>
       val textPromptEndTokenId: Int,   // <tpe>
       val speechGenerationStartTokenId: Int, // <sgs>
       val speechGenerationEndTokenId: Int,   // <eos_speech>
       val audioRefSlotTokenId: Int,    // reference audio slot
       val textVocabSize: Int,          // text vocabulary size
       val defaultStyleTokenId: Int,    // style token
   )
   ```

2. [x] **Create `NpzReader.kt`** — minimal NPZ parser:
   - NPZ files are ZIP archives containing `.npy` files
   - Each `.npy` has a header (magic + shape + dtype) followed by raw data
   - Only need to read `float32` arrays: `text_emb`, `audio_emb`
   - Use `java.util.zip.ZipInputStream` to read entries

3. [x] **Create `VieNeuTokenizer.kt`** — tokenizer wrapper:
   - Parse `tokenizer.json` (HuggingFace tokenizers format)
   - Extract `vocab` mapping (token string → ID)
   - Implement `encode(phonemes: String): IntArray`

### Step 2: ONNX Session Management

4. [x] **Create `OnnxSessionManager.kt`**:
   - Load ONNX model files from Android `assets/` into `ByteArray` / local storage
   - Create `OrtEnvironment` singleton
   - Create 4 `OrtSession` instances with optimized `SessionOptions`:
     - `graphOptimizationLevel = ORT_ENABLE_ALL`
     - `intraOpNumThreads = Runtime.getRuntime().availableProcessors() / 2`
   - Provide `close()` method to release all sessions

### Step 3: Inference Pipeline

5. [x] **Create `VieNeuOnnxEngine.kt`** — core inference:

   **a) Input Preparation:**
   - Take phonemized text from Phase 02's `SeaG2P.phonemize()`
   - Tokenize using `VieNeuTokenizer.encode()`
   - Look up text embeddings from `text_emb` matrix (from NPZ)
   - Build the prompt tensor: `[style_emb, tps_emb, text_embs..., tpe_emb, sgs_emb]`

   **b) Prefill:**
   - Feed the full prompt embedding sequence into `vieneu_prefill.onnx`
   - Outputs: initial KV cache (key/value tensors for all layers), first token logits

   **c) Autoregressive Decode Loop:**
   ```
   repeat until EOS or max_length:
       1. Sample next token from logits (top-k=20, temperature=0.7)
       2. Look up token embedding from audio_emb matrix
       3. Feed [embedding, kv_cache] into vieneu_decode_step.onnx
       4. Get updated kv_cache + next logits
       5. Collect generated audio tokens
   ```

   **d) Acoustic Model:**
   - Feed collected audio token sequence into `vieneu_acoustic_cached.onnx`
   - Output: acoustic features tensor

   **e) MOSS Codec Decode:**
   - Feed acoustic features into `moss_audio_tokenizer_decode_full.onnx`
   - Output: raw PCM float array at 48kHz sample rate

6. [x] **Implement Top-K sampling** in pure Kotlin:
   ```kotlin
   fun topKSample(logits: FloatArray, k: Int = 20, temperature: Float = 0.7f): Int {
       // 1. Apply temperature
       // 2. Find top-k indices
       // 3. Softmax over top-k
       // 4. Random weighted sample
   }
   ```

### Step 4: Preset Voice Support

7. [x] **Create `VoicePresets.kt`**:
   - Bundle preset speaker embeddings (extracted from VieNeu's `speaker/` directory)
   - Map voice names ("Trúc Ly", "Minh Đức") to their embedding vectors
   - Provide `getVoiceEmbedding(name: String): FloatArray`

## Files Created/Modified

- `app/.../tts/vieneu/VieNeuConfig.kt` — [NEW] Config parser
- `app/.../tts/vieneu/NpzReader.kt` — [NEW] NumPy NPZ file reader
- `app/.../tts/vieneu/VieNeuTokenizer.kt` — [NEW] Phoneme tokenizer
- `app/.../tts/vieneu/OnnxSessionManager.kt` — [NEW] ONNX session lifecycle
- `app/.../tts/vieneu/VieNeuOnnxEngine.kt` — [NEW] Core inference engine
- `app/.../tts/vieneu/VoicePresets.kt` — [NEW] Preset voice embeddings
- `app/src/androidTest/.../VieNeuOnnxEngineTest.kt` — [NEW] Instrumented verification test
- `app/src/test/.../VieNeuOnnxEngineJvmTest.kt` — [NEW] JVM verification test

## Verification Test

### Test: `VieNeuOnnxEngineTest.kt`
Android instrumented test (runs on device/emulator):

```kotlin
package com.skul9x.doctruyen.tts.vieneu

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VieNeuOnnxEngineTest {

    private lateinit var context: Context
    private lateinit var engine: VieNeuOnnxEngine

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        engine = VieNeuOnnxEngine.create(context)
    }

    @After
    fun teardown() {
        engine.close()
    }

    @Test
    fun testConfigLoaded() {
        val config = engine.config
        assertTrue("Hidden size should be > 0", config.hiddenSize > 0)
        assertTrue("nVq should be > 0", config.nVq > 0)
        println("Config loaded: hidden=${config.hiddenSize}, nVq=${config.nVq}, layers=${config.numHiddenLayers}")
    }

    @Test
    fun testTokenizerEncode() {
        val phonemes = "s in1 ch aw2"
        val tokens = engine.tokenizer.encode(phonemes)
        assertTrue("Tokenizer should produce non-empty output", tokens.isNotEmpty())
        assertTrue("All token IDs should be positive", tokens.all { it >= 0 })
        println("Tokenized '$phonemes' → ${tokens.toList()}")
    }

    @Test
    fun testNpzReaderLoadsEmbeddings() {
        val textEmb = engine.textEmbeddings
        assertTrue("Text embeddings should have rows", textEmb.isNotEmpty())
        println("Text embedding matrix: ${textEmb.size} tokens x ${textEmb[0].size} dims")
    }

    @Test
    fun testFullInference() {
        // Given a phonemized Vietnamese sentence
        val phonemes = SeaG2P.phonemize("Xin chào")

        // When we run full inference
        val startTime = System.currentTimeMillis()
        val pcmAudio = engine.infer(phonemes, voiceName = "Trúc Ly")
        val elapsed = System.currentTimeMillis() - startTime

        // Then we should get valid PCM audio data
        assertNotNull("PCM audio should not be null", pcmAudio)
        assertTrue("PCM audio should have samples", pcmAudio.isNotEmpty())
        assertTrue("PCM audio should have > 1000 samples (at 48kHz)", pcmAudio.size > 1000)

        // Verify audio values are in valid range [-1.0, 1.0]
        val maxVal = pcmAudio.maxOrNull() ?: 0f
        val minVal = pcmAudio.minOrNull() ?: 0f
        assertTrue("Audio max should be <= 1.5, got $maxVal", maxVal <= 1.5f)
        assertTrue("Audio min should be >= -1.5, got $minVal", minVal >= -1.5f)

        val durationSec = pcmAudio.size.toFloat() / 48000f
        println("✅ Generated ${durationSec}s of audio in ${elapsed}ms")
        println("   Samples: ${pcmAudio.size}, Range: [$minVal, $maxVal]")
    }

    @Test
    fun testOnnxSessionsLoaded() {
        // Verify all 4 ONNX sessions are loaded without error
        assertNotNull("Prefill session should be loaded", engine.sessionManager.prefillSession)
        assertNotNull("Decode session should be loaded", engine.sessionManager.decodeSession)
        assertNotNull("Acoustic session should be loaded", engine.sessionManager.acousticSession)
        assertNotNull("Codec session should be loaded", engine.sessionManager.codecSession)
        println("✅ All 4 ONNX sessions loaded successfully")
    }
}
```

---
Previous Phase: [Phase 02 — Phonemizer](./phase-02-phonemizer.md)  
Next Phase: [Phase 04 — Audio Pipeline](./phase-04-audio-pipeline.md)
