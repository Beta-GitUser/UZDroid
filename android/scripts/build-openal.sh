#!/usr/bin/env bash
#
# Builds openal-soft for arm64-v8a and drops libopenal.so where gradle packages
# it (app/src/main/jniLibs/arm64-v8a). The engine dlopen()s "libopenal.so" at
# startup, so this file is the difference between sound and silence; nothing
# links against it at build time.
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Usage:  ANDROID_NDK_HOME=/path/to/ndk ./android/scripts/build-openal.sh
#         (NDK_ROOT is used when ANDROID_NDK_HOME is not set)

set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="$ANDROID_DIR/deps/openal-soft"
BUILD_DIR="$ANDROID_DIR/../build-openal"
OUT_DIR="$ANDROID_DIR/app/src/main/jniLibs/arm64-v8a"
OUT_LIB="$OUT_DIR/libopenal.so"

if [ ! -f "$SRC_DIR/CMakeLists.txt" ]; then
    echo "openal-soft source not found at $SRC_DIR - skipping (no sound)"
    exit 0
fi

NDK="${ANDROID_NDK_HOME:-${NDK_ROOT:-}}"
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "error: set ANDROID_NDK_HOME (or NDK_ROOT) to an NDK" >&2
    exit 1
fi

TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
if [ ! -f "$TOOLCHAIN" ]; then
    echo "error: no toolchain file at $TOOLCHAIN" >&2
    exit 1
fi

cmake -S "$SRC_DIR" -B "$BUILD_DIR" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-24 \
    -DCMAKE_BUILD_TYPE=Release \
    -DLIBTYPE=SHARED \
    -DALSOFT_EXAMPLES=OFF \
    -DALSOFT_UTILS=OFF \
    -DALSOFT_TESTS=OFF \
    -DALSOFT_INSTALL=OFF \
    -DALSOFT_BACKEND_OPENSL=ON \
    -DALSOFT_BACKEND_WAVE=OFF

cmake --build "$BUILD_DIR" --parallel

# Where the library ends up has moved around between releases; find it instead
# of assuming.
BUILT="$(find "$BUILD_DIR" -type f -name 'libopenal.so' | head -n 1)"
if [ -z "$BUILT" ]; then
    echo "error: libopenal.so was not produced" >&2
    exit 1
fi

mkdir -p "$OUT_DIR"
cp -f "$BUILT" "$OUT_LIB"

STRIP="$(find "$NDK/toolchains/llvm/prebuilt" -maxdepth 3 -type f -name 'llvm-strip' | head -n 1)"
if [ -n "$STRIP" ]; then
    "$STRIP" --strip-unneeded "$OUT_LIB" 2>/dev/null || true
fi

echo "openal-soft built: $OUT_LIB ($(stat -c%s "$OUT_LIB") bytes)"
