#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
APP_DIR="$REPO_DIR/app"
OUT_DIR="${PUSHGO_ANDROID_JNI_OUT_DIR:-$APP_DIR/src/main/jniLibs}"
cd "$SCRIPT_DIR"

read_gradle_property() {
  local name="$1"
  sed -n "s/^${name}=//p" "$REPO_DIR/gradle.properties" | tail -1
}

MIN_SDK="$(read_gradle_property pushgo.androidMinSdk)"
NDK_VERSION="$(read_gradle_property pushgo.androidNdkVersion)"
CARGO_NDK_VERSION="$(read_gradle_property pushgo.cargoNdkVersion)"
RUST_VERSION="$(sed -n 's/^channel = "\([^"]*\)"$/\1/p' "$REPO_DIR/rust-toolchain.toml")"

if [[ -z "$MIN_SDK" || -z "$NDK_VERSION" || -z "$CARGO_NDK_VERSION" || -z "$RUST_VERSION" ]]; then
  echo "missing Android native toolchain properties in gradle.properties" >&2
  exit 1
fi
installed_rust_version="$(rustc --version | awk '{print $2}')"
if [[ "$installed_rust_version" != "$RUST_VERSION" ]]; then
  echo "Rust version mismatch: expected=$RUST_VERSION actual=$installed_rust_version" >&2
  exit 1
fi
if [[ -n "${PUSHGO_ANDROID_MIN_SDK:-}" && "$PUSHGO_ANDROID_MIN_SDK" != "$MIN_SDK" ]]; then
  echo "Android minSdk drift: Gradle=$MIN_SDK environment=$PUSHGO_ANDROID_MIN_SDK" >&2
  exit 1
fi
if [[ -n "${PUSHGO_ANDROID_NDK_VERSION:-}" && "$PUSHGO_ANDROID_NDK_VERSION" != "$NDK_VERSION" ]]; then
  echo "Android NDK drift: Gradle=$NDK_VERSION environment=$PUSHGO_ANDROID_NDK_VERSION" >&2
  exit 1
fi
if [[ -n "${PUSHGO_CARGO_NDK_VERSION:-}" && "$PUSHGO_CARGO_NDK_VERSION" != "$CARGO_NDK_VERSION" ]]; then
  echo "cargo-ndk drift: Gradle=$CARGO_NDK_VERSION environment=$PUSHGO_CARGO_NDK_VERSION" >&2
  exit 1
fi

if ! command -v cargo-ndk >/dev/null 2>&1; then
  echo "cargo-ndk not found. install: cargo install cargo-ndk --version $CARGO_NDK_VERSION --locked"
  exit 1
fi
installed_cargo_ndk_version="$(cargo ndk --version | awk '{print $NF}')"
if [[ "$installed_cargo_ndk_version" != "$CARGO_NDK_VERSION" ]]; then
  echo "cargo-ndk version mismatch: expected=$CARGO_NDK_VERSION actual=$installed_cargo_ndk_version" >&2
  exit 1
fi

if [[ -z "${ANDROID_NDK_HOME:-}" && -n "${ANDROID_SDK_ROOT:-}" ]]; then
  export ANDROID_NDK_HOME="$ANDROID_SDK_ROOT/ndk/$NDK_VERSION"
fi
if [[ -z "${ANDROID_NDK_HOME:-}" || ! -f "$ANDROID_NDK_HOME/source.properties" ]]; then
  echo "Android NDK $NDK_VERSION not found; set ANDROID_NDK_HOME to that exact revision" >&2
  exit 1
fi
installed_ndk_version="$(sed -n 's/^Pkg.Revision[[:space:]]*=[[:space:]]*//p' "$ANDROID_NDK_HOME/source.properties")"
if [[ "$installed_ndk_version" != "$NDK_VERSION" ]]; then
  echo "Android NDK version mismatch: expected=$NDK_VERSION actual=$installed_ndk_version" >&2
  exit 1
fi
llvm_prebuilt_dir="$(find "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -1)"
LLVM_NM="$llvm_prebuilt_dir/bin/llvm-nm"
LLVM_READELF="$llvm_prebuilt_dir/bin/llvm-readelf"
if [[ ! -x "$LLVM_NM" || ! -x "$LLVM_READELF" ]]; then
  echo "LLVM ABI inspection tools not found under pinned NDK: $llvm_prebuilt_dir/bin" >&2
  exit 1
fi

verify_jni_symbols() {
  local library="$1"
  local expected actual
  expected="$(awk '$1 ~ /^native/ {
    print "Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_" $1
  }' "$SCRIPT_DIR/jni-contract.txt" | sort)"
  actual="$("$LLVM_NM" -D --defined-only "$library" \
    | awk '{print $NF}' \
    | sed -n '/^Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_/p' \
    | sort)"
  if [[ "$actual" != "$expected" ]]; then
    echo "JNI export drift in $library" >&2
    diff <(printf '%s\n' "$expected") <(printf '%s\n' "$actual") >&2 || true
    exit 1
  fi
  if [[ "$library" == */arm64-v8a/* ]] &&
    "$LLVM_READELF" -lW "$library" | awk '
      BEGIN { found = 0 }
      $1 == "LOAD" { found = 1; if ($NF != "0x4000") exit 1 }
      END { if (!found) exit 1 }
    '
  then
    :
  elif [[ "$library" == */arm64-v8a/* ]]; then
    echo "arm64 JNI library is not 16 KiB page aligned: $library" >&2
    exit 1
  fi
}

TARGETS=(
  "arm64-v8a:aarch64-linux-android"
  "armeabi-v7a:armv7-linux-androideabi"
  "x86_64:x86_64-linux-android"
)

case "$OUT_DIR" in
  ""|"/"|"$HOME")
    echo "refusing unsafe JNI output directory: $OUT_DIR" >&2
    exit 1
    ;;
esac
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

cargo fetch --manifest-path "$SCRIPT_DIR/Cargo.toml" --locked

for item in "${TARGETS[@]}"; do
  abi="${item%%:*}"
  target="${item##*:}"
  cargo ndk -t "$target" -P "$MIN_SDK" build --release --frozen
  mkdir -p "$OUT_DIR/$abi"
  install -m 0755 "$SCRIPT_DIR/target/$target/release/libpushgo_quinn_jni.so" "$OUT_DIR/$abi/libpushgo_quinn_jni.so"
  verify_jni_symbols "$OUT_DIR/$abi/libpushgo_quinn_jni.so"
  echo "built $abi -> $OUT_DIR/$abi/libpushgo_quinn_jni.so"
done
