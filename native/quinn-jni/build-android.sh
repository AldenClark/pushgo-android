#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)/app"
OUT_DIR="$APP_DIR/src/main/jniLibs"

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "cargo-ndk not found. install: cargo install cargo-ndk"
  exit 1
fi

API_LEVEL="${ANDROID_API_LEVEL:-31}"
TARGETS=(
  "arm64-v8a:aarch64-linux-android"
  "armeabi-v7a:armv7-linux-androideabi"
  "x86_64:x86_64-linux-android"
)

for item in "${TARGETS[@]}"; do
  abi="${item%%:*}"
  target="${item##*:}"
  cargo ndk -t "$target" -P "$API_LEVEL" build --release
  mkdir -p "$OUT_DIR/$abi"
  cp "$SCRIPT_DIR/target/$target/release/libpushgo_quinn_jni.so" "$OUT_DIR/$abi/libpushgo_quinn_jni.so"
  echo "built $abi -> $OUT_DIR/$abi/libpushgo_quinn_jni.so"
done
