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

# Test 2: noCompress configured
if grep -q "noCompress" app/build.gradle.kts; then
  echo "✅ PASS: androidResources noCompress configured"
  ((PASS++))
else
  echo "❌ FAIL: androidResources noCompress NOT configured"
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

# Test 7: APK generated
if [ -f "app/build/outputs/apk/debug/app-debug.apk" ]; then
  echo "✅ PASS: Debug APK output exists"
  ((PASS++))
else
  echo "❌ FAIL: Debug APK output not found"
  ((FAIL++))
fi

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
if [ $FAIL -eq 0 ]; then
  echo "🎉 Phase 01 COMPLETE — Ready to proceed to Phase 02"
else
  echo "⚠️ Fix the failing tests before proceeding"
fi
