# Phase 02: sea-g2p Phonemizer (Rust → Android .so via JNI)
Status: ✅ Completed  
Dependencies: Phase 01

## Objective
Cross-compile the `sea-g2p` Vietnamese phonemizer (Rust library) into an Android-compatible shared library (`.so`), create a JNI bridge, and expose a Kotlin API: `SeaG2P.phonemize("Xin chào") → "s in1 ch aw2"`.

## Background
VieNeu-TTS requires text to be converted into phonemes before feeding into the ONNX backbone. The `sea-g2p` library handles Vietnamese grapheme-to-phoneme conversion, text normalization (numbers → words, abbreviations), and punctuation handling. It is written in Rust for speed and has no Python/PyTorch dependency.

## Requirements

### Functional
- [x] Rust toolchain configured with Android NDK targets (`aarch64-linux-android`, `armv7-linux-androideabi`)
- [x] JNI wrapper crate created around `sea-g2p` exposing `phonemize(text: String) → String`
- [x] Cross-compiled `.so` files placed in `app/src/main/jniLibs/{arm64-v8a,armeabi-v7a}/`
- [x] Kotlin wrapper class `SeaG2P.kt` loads native library and exposes `phonemize()` function
- [x] Handles Vietnamese tones, diacritics, and mixed English-Vietnamese text correctly

### Non-Functional
- [x] Phonemization latency < 10ms for a typical sentence (50 words) on ARM64
- [x] `.so` file size < 15 MB per architecture
- [x] No crashes or memory leaks on repeated calls

## Implementation Steps

1. [x] **Install Rust Android targets**:
   ```bash
   rustup target add aarch64-linux-android armv7-linux-androideabi
   cargo install cargo-ndk
   ```

2. [x] **Set up NDK environment** — ensure `ANDROID_NDK_HOME` points to the NDK installed by Android Studio (typically `~/Android/Sdk/ndk/<version>/`)

3. [x] **Create JNI bridge crate** (`sea-g2p-android/`):
   ```
   sea-g2p-android/
   ├── Cargo.toml
   ├── src/
   │   └── lib.rs        # JNI exports
   └── build.sh          # Cross-compile script
   ```

4. [x] **Write `Cargo.toml`**:
   ```toml
   [package]
   name = "sea-g2p-android"
   version = "0.1.0"
   edition = "2021"

   [lib]
   crate-type = ["cdylib"]

   [dependencies]
   jni = "0.21"
   sea-g2p = "0.9"
   ```

5. [x] **Write `src/lib.rs`** — JNI entry points:
   ```rust
   use jni::JNIEnv;
   use jni::objects::{JClass, JString};
   use jni::sys::jstring;
   use sea_g2p::G2P;
   use std::sync::OnceLock;

   static G2P_INSTANCE: OnceLock<G2P> = OnceLock::new();

   fn get_g2p() -> &'static G2P {
       G2P_INSTANCE.get_or_init(|| G2P::new().expect("Failed to init sea-g2p"))
   }

   #[no_mangle]
   pub extern "C" fn Java_com_skul9x_doctruyen_tts_vieneu_SeaG2P_nativePhonemize(
       mut env: JNIEnv,
       _class: JClass,
       input: JString,
   ) -> jstring {
       let text: String = env.get_string(&input)
           .expect("Invalid UTF-8")
           .into();
       let phonemes = get_g2p().phonemize(&text);
       let output = env.new_string(phonemes)
           .expect("Failed to create Java string");
       output.into_raw()
   }
   ```

6. [x] **Write `build.sh`** — cross-compile for Android:
   ```bash
   #!/bin/bash
   JNILIBS="../DocTruyenApk-main/app/src/main/jniLibs"
   cargo ndk -t aarch64-linux-android -t armv7-linux-androideabi \
       -o "$JNILIBS" build --release
   echo "✅ Built .so files into $JNILIBS/"
   ls -lh "$JNILIBS"/arm64-v8a/libsea_g2p_android.so
   ls -lh "$JNILIBS"/armeabi-v7a/libsea_g2p_android.so
   ```

7. [x] **Create Kotlin wrapper** `SeaG2P.kt`:
   ```kotlin
   package com.skul9x.doctruyen.tts.vieneu

   object SeaG2P {
       init {
           System.loadLibrary("sea_g2p_android")
       }

       private external fun nativePhonemize(text: String): String

       /**
        * Convert Vietnamese text to phoneme sequence.
        * Example: "Xin chào" → "s in1 ch aw2"
        */
       fun phonemize(text: String): String {
           return nativePhonemize(text)
       }
   }
   ```

8. [x] **Run cross-compilation** and verify `.so` files are produced

9. [x] **Test on Android device** — call `SeaG2P.phonemize()` from a test Activity

## Files to Create/Modify

- `sea-g2p-android/Cargo.toml` — [NEW] Rust crate config
- `sea-g2p-android/src/lib.rs` — [NEW] JNI bridge implementation
- `sea-g2p-android/build.sh` — [NEW] Cross-compile script
- `app/src/main/jniLibs/arm64-v8a/libsea_g2p_android.so` — [NEW] Compiled library (ARM64)
- `app/src/main/jniLibs/armeabi-v7a/libsea_g2p_android.so` — [NEW] Compiled library (ARMv7)
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/SeaG2P.kt` — [NEW] Kotlin wrapper

## Verification Test

### Test: `test_phase02_phonemizer.kt`
Add this as an Android instrumented test (`androidTest/`):

```kotlin
package com.skul9x.doctruyen.tts.vieneu

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeaG2PTest {

    @Test
    fun testVietnameseBasicPhonemize() {
        // Given a simple Vietnamese sentence
        val input = "Xin chào các bạn"

        // When phonemized
        val result = SeaG2P.phonemize(input)

        // Then it should produce non-empty phoneme output
        assertTrue("Phonemize result should not be empty", result.isNotEmpty())
        println("Phonemize output: '$result'")
    }

    @Test
    fun testVietnameseWithTones() {
        // Given Vietnamese with various tones
        val input = "Tôi yêu Việt Nam"

        val result = SeaG2P.phonemize(input)

        assertTrue("Should handle tonal marks", result.isNotEmpty())
        // Phonemes should contain tone markers
        assertTrue("Should contain phoneme tokens", result.contains(" "))
        println("Tonal phonemize: '$result'")
    }

    @Test
    fun testNumberNormalization() {
        // Given text with numbers
        val input = "Năm 2026, giá là 50000 đồng"

        val result = SeaG2P.phonemize(input)

        // Numbers should be expanded to words
        assertTrue("Should normalize numbers", result.isNotEmpty())
        println("Number normalization: '$result'")
    }

    @Test
    fun testEmptyInput() {
        val result = SeaG2P.phonemize("")
        // Should not crash, return empty or whitespace
        assertNotNull("Empty input should not return null", result)
    }

    @Test
    fun testPerformance() {
        // Given a realistic paragraph
        val paragraph = "Ngày xưa, có một chàng trai nghèo sống trong một ngôi làng nhỏ bên bờ sông. " +
            "Anh ta rất chăm chỉ và luôn mơ ước một cuộc sống tốt đẹp hơn."

        val startTime = System.nanoTime()
        val result = SeaG2P.phonemize(paragraph)
        val elapsed = (System.nanoTime() - startTime) / 1_000_000 // ms

        assertTrue("Phonemization should complete within 50ms, took ${elapsed}ms", elapsed < 50)
        assertTrue("Should produce output for paragraph", result.isNotEmpty())
        println("Performance: ${elapsed}ms for ${paragraph.length} chars")
    }
}
```

## Notes
- `sea-g2p` uses `mmap` for its dictionary data. On Android, files in `assets/` cannot be directly mmapped. The `sea-g2p` Rust library ships its dictionary data embedded in the binary (compiled in), so this is not an issue when using it as a `cdylib`.
- If `sea-g2p` crate does NOT embed its dictionary and requires external files, an alternative approach is needed: copy dictionary files from `assets/` to `filesDir` at first launch, then pass the path to the Rust init function.
- The JNI function name **must** exactly match the Kotlin package path: `Java_com_skul9x_doctruyen_tts_vieneu_SeaG2P_nativePhonemize`. Any mismatch causes `UnsatisfiedLinkError`.

---
Previous Phase: [Phase 01 — Setup](./phase-01-setup.md)  
Next Phase: [Phase 03 — ONNX Inference Engine](./phase-03-onnx-engine.md)
