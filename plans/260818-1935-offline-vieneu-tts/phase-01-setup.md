# Phase 01: Project Setup & Dependencies
Status: ✅ Complete
Dependencies: None

## Objective
Prepare the DocTruyenApk Android project for VieNeu-TTS integration by adding all required libraries, configuring the build system to support native `.so` libraries, and bundling the ONNX model assets.

## Requirements

### Functional
- [x] ONNX Runtime Android library added to Gradle dependencies
- [x] Project configured to load native `.so` libraries from `jniLibs/`
- [x] All VieNeu-TTS ONNX model files bundled (backbone + codec + denoiser)
- [x] Gradle `aaptOptions` / `androidResources` configured with `noCompress` for `.onnx`, `.data`, `.npz`, `.json` files
- [x] APK size stays under 370 MB (model assets ~285 MB + app ~10 MB)

### Non-Functional
- [x] Build succeeds on Android Studio with no errors
- [x] Min SDK remains 24 (Android 7.0)
- [x] Existing app functionality (reading stories, UI) is unaffected

## Implementation Steps

1. [x] **Add ONNX Runtime dependency** to `app/build.gradle.kts`
2. [x] **Configure `androidResources.noCompress`** to prevent asset compression
3. [x] **Create model assets directory structure** (`app/src/main/assets/vieneu/`)
4. [x] **Copy model files** from HuggingFace cache into assets directory (285 MB total)
5. [x] **Add `jniLibs` source directory** config for future Rust `.so` files
6. [x] **Create placeholder package** `com.skul9x.doctruyen.tts.vieneu/`
7. [x] **Verify clean build** succeeds: `./gradlew assembleDebug` passed!

## Files to Create/Modify

- `app/build.gradle.kts` — Add ONNX Runtime dep, aaptOptions, jniLibs config
- `app/src/main/assets/vieneu/backbone/` — Copy 7 backbone model files
- `app/src/main/assets/vieneu/codec/` — Copy 6 codec model files
- `app/src/main/assets/vieneu/denoiser/` — Copy 1 denoiser file
- `app/src/main/java/com/skul9x/doctruyen/tts/vieneu/` — Create package dir

## Verification Test

### Test: `test_phase01_setup.sh`
Run this script on the development machine after completing Phase 01:

```bash
#!/bin/bash
# Phase 01 Verification Test
# Run from DocTruyenApk-main/ root directory

PASS=0
FAIL=0

echo "=== Phase 01: Project Setup Verification ==="

# Test 1: ONNX Runtime dependency in build.gradle
if grep -q "onnxruntime-android" app/build.gradle.kts; then
  echo "✅ PASS: ONNX Runtime dependency found in build.gradle.kts"
  ((PASS++))
else
  echo "❌ FAIL: ONNX Runtime dependency NOT found in build.gradle.kts"
  ((FAIL++))
fi

# Test 2: aaptOptions noCompress configured
if grep -q "noCompress" app/build.gradle.kts; then
  echo "✅ PASS: aaptOptions noCompress configured"
  ((PASS++))
else
  echo "❌ FAIL: aaptOptions noCompress NOT configured"
  ((FAIL++))
fi

# Test 3: Backbone model files exist
BACKBONE_FILES=("vieneu_prefill.onnx" "vieneu_decode_step.onnx" "vieneu_acoustic_cached.onnx" "vieneu_backbone_shared.data" "vieneu_v3_heads.npz" "config.json" "tokenizer.json")
ALL_BACKBONE=true
for f in "${BACKBONE_FILES[@]}"; do
  if [ ! -f "app/src/main/assets/vieneu/backbone/$f" ]; then
    echo "❌ FAIL: Missing backbone file: $f"
    ALL_BACKBONE=false
    ((FAIL++))
  fi
done
if $ALL_BACKBONE; then
  echo "✅ PASS: All 7 backbone model files present"
  ((PASS++))
fi

# Test 4: Codec model files exist
CODEC_FILES=("moss_audio_tokenizer_decode_full.onnx" "moss_audio_tokenizer_decode_shared.data" "moss_audio_tokenizer_decode_step.onnx" "moss_audio_tokenizer_encode.onnx" "moss_audio_tokenizer_encode.data" "codec_browser_onnx_meta.json")
ALL_CODEC=true
for f in "${CODEC_FILES[@]}"; do
  if [ ! -f "app/src/main/assets/vieneu/codec/$f" ]; then
    echo "❌ FAIL: Missing codec file: $f"
    ALL_CODEC=false
    ((FAIL++))
  fi
done
if $ALL_CODEC; then
  echo "✅ PASS: All 6 codec model files present"
  ((PASS++))
fi

# Test 5: Denoiser exists
if [ -f "app/src/main/assets/vieneu/denoiser/denoiser.onnx" ]; then
  echo "✅ PASS: Denoiser model file present"
  ((PASS++))
else
  echo "❌ FAIL: Denoiser model file missing"
  ((FAIL++))
fi

# Test 6: Total assets size is reasonable (250-350 MB)
TOTAL_SIZE=$(du -sm app/src/main/assets/vieneu/ 2>/dev/null | cut -f1)
if [ "$TOTAL_SIZE" -ge 250 ] && [ "$TOTAL_SIZE" -le 350 ]; then
  echo "✅ PASS: Total model assets size = ${TOTAL_SIZE} MB (expected 250-350 MB)"
  ((PASS++))
else
  echo "❌ FAIL: Total model assets size = ${TOTAL_SIZE} MB (expected 250-350 MB)"
  ((FAIL++))
fi

# Test 7: Gradle build succeeds
echo "  Building project (this may take a few minutes)..."
if ./gradlew assembleDebug --quiet 2>/dev/null; then
  echo "✅ PASS: Gradle assembleDebug succeeded"
  ((PASS++))
else
  echo "❌ FAIL: Gradle assembleDebug failed"
  ((FAIL++))
fi

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
if [ $FAIL -eq 0 ]; then
  echo "🎉 Phase 01 COMPLETE — Ready to proceed to Phase 02"
else
  echo "⚠️  Fix the failing tests before proceeding"
fi
```

## Notes
- The `noCompress` setting is **critical**: without it, Android's AAPT tool compresses `.onnx`/`.data` files during APK build, which corrupts them and causes ONNX Runtime to crash at load time.
- Model files are large (~290 MB). In a production scenario, consider downloading them on first launch instead of bundling in the APK. For this personal app, bundling is simpler.
- The `.npz` file (NumPy compressed archive) will need to be read using a custom parser in Kotlin since there is no native Android NumPy library. This is handled in Phase 03.

---
Next Phase: [Phase 02 — sea-g2p Phonemizer](./phase-02-phonemizer.md)
