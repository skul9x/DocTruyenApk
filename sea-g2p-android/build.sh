#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-/home/skul9x/android-sdk/ndk/26.1.10909125}"

if [ -d "$SCRIPT_DIR/../app/src/main/jniLibs" ]; then
    JNILIBS="$SCRIPT_DIR/../app/src/main/jniLibs"
elif [ -d "$SCRIPT_DIR/../DocTruyenApk-main/app/src/main/jniLibs" ]; then
    JNILIBS="$SCRIPT_DIR/../DocTruyenApk-main/app/src/main/jniLibs"
else
    JNILIBS="$SCRIPT_DIR/app/src/main/jniLibs"
fi

mkdir -p "$JNILIBS"

cd "$SCRIPT_DIR"
cargo ndk -t aarch64-linux-android -t armv7-linux-androideabi -t x86_64-linux-android \
    -o "$JNILIBS" build --release

echo "✅ Built .so files into $JNILIBS/"
ls -lh "$JNILIBS"/arm64-v8a/libsea_g2p_android.so 2>/dev/null || true
ls -lh "$JNILIBS"/armeabi-v7a/libsea_g2p_android.so 2>/dev/null || true
ls -lh "$JNILIBS"/x86_64/libsea_g2p_android.so 2>/dev/null || true
