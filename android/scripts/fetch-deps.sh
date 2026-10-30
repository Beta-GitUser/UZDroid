#!/usr/bin/env bash
#
# Fetches the one external dependency the Android build needs: an SDL2 source
# tree. It is put in android/deps/SDL, which is where gradle.properties points.
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Usage:  ./android/scripts/fetch-deps.sh [tag]
#         SDL_VERSION=release-2.30.9 ./android/scripts/fetch-deps.sh

set -euo pipefail

SDL_VERSION="${1:-${SDL_VERSION:-release-2.30.9}}"

ANDROID_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEPS_DIR="$ANDROID_DIR/deps"
SDL_DIR="$DEPS_DIR/SDL"

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
