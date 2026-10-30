#!/usr/bin/env bash
#
# Fetches the external dependencies of the Android build: an SDL2 source tree
# (android/deps/SDL, which is where gradle.properties points) and openal-soft
# (android/deps/openal-soft, optional - it only decides whether there is sound).
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Usage:  ./android/scripts/fetch-deps.sh [tag]
#         SDL_VERSION=release-2.30.9 ./android/scripts/fetch-deps.sh

set -euo pipefail

SDL_VERSION="${1:-${SDL_VERSION:-release-2.30.9}}"
OPENAL_VERSION="${OPENAL_VERSION:-1.23.1}"

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPS_DIR="$ANDROID_DIR/deps"
SDL_DIR="$DEPS_DIR/SDL"
OPENAL_DIR="$DEPS_DIR/openal-soft"

if [ -f "$SDL_DIR/CMakeLists.txt" ]; then
    echo "SDL2 already present at $SDL_DIR"
    exit 0
fi

mkdir -p "$DEPS_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

URL="https://codeload.github.com/libsdl-org/SDL/tar.gz/refs/tags/${SDL_VERSION}"
echo "Downloading SDL2 ($SDL_VERSION) from $URL"

if command -v curl >/dev/null 2>&1; then
    curl -fL "$URL" -o "$TMP_DIR/sdl.tar.gz"
elif command -v wget >/dev/null 2>&1; then
    wget -O "$TMP_DIR/sdl.tar.gz" "$URL"
else
    echo "error: need curl or wget" >&2
    exit 1
fi

tar xzf "$TMP_DIR/sdl.tar.gz" -C "$TMP_DIR"

EXTRACTED="$(find "$TMP_DIR" -maxdepth 1 -type d -name 'SDL-*' | head -n 1)"
if [ -z "$EXTRACTED" ]; then
    echo "error: could not find the extracted SDL directory" >&2
    exit 1
fi

rm -rf "$SDL_DIR"
mv "$EXTRACTED" "$SDL_DIR"

echo "SDL2 is ready at $SDL_DIR"

#
# openal-soft: the only sound backend the engine has, dlopen()ed at runtime.
# Failure here is not fatal - the APK builds and runs silently without it.
#
if [ -f "$OPENAL_DIR/CMakeLists.txt" ]; then
    echo "openal-soft already present at $OPENAL_DIR"
else
    echo "Downloading openal-soft ($OPENAL_VERSION)"
    if ! curl -fL "https://codeload.github.com/kcat/openal-soft/tar.gz/refs/tags/${OPENAL_VERSION}" \
            -o "$TMP_DIR/openal.tar.gz"; then
        echo "warning: could not download openal-soft; the APK will have no sound" >&2
        exit 0
    fi
    tar xzf "$TMP_DIR/openal.tar.gz" -C "$TMP_DIR"
    EXTRACTED_OPENAL="$(find "$TMP_DIR" -maxdepth 1 -type d -name 'openal-soft-*' | head -n 1)"
    if [ -z "$EXTRACTED_OPENAL" ]; then
        echo "warning: could not find the extracted openal-soft directory" >&2
        exit 0
    fi
    rm -rf "$OPENAL_DIR"
    mv "$EXTRACTED_OPENAL" "$OPENAL_DIR"
    echo "openal-soft is ready at $OPENAL_DIR"
fi
