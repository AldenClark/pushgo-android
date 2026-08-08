#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
contract_file="$repo_dir/native/quinn-jni/jni-contract.txt"
rust_source="$repo_dir/native/quinn-jni/src/lib.rs"
kotlin_source="$repo_dir/app/src/main/java/io/ethan/pushgo/notifications/WarpLinkNativeBridge.kt"
stale_header="$repo_dir/native/quinn-jni/include/pushgo_quinn_jni.h"
manifest="$repo_dir/native/quinn-jni/Cargo.toml"
toolchain_file="$repo_dir/rust-toolchain.toml"
gradle_properties="$repo_dir/gradle.properties"
native_build="$repo_dir/native/quinn-jni/build-android.sh"
release_workflow="$repo_dir/.github/workflows/android-release.yml"
gradle_wrapper="$repo_dir/gradle/wrapper/gradle-wrapper.properties"
gradle_verification="$repo_dir/gradle/verification-metadata.xml"

if [[ -e "$stale_header" ]]; then
  echo "stale non-JNI header must not exist: $stale_header" >&2
  exit 1
fi

contract_abi="$(awk '$1 == "abi" { print $2 }' "$contract_file")"
rust_abi="$(sed -n 's/^const JNI_ABI_VERSION: jint = \([0-9][0-9]*\);$/\1/p' "$rust_source")"
kotlin_abi="$(sed -n 's/^[[:space:]]*const val ABI_VERSION: Int = \([0-9][0-9]*\)$/\1/p' "$kotlin_source")"
if [[ -z "$contract_abi" || "$contract_abi" != "$rust_abi" || "$contract_abi" != "$kotlin_abi" ]]; then
  echo "JNI ABI version drift: contract=$contract_abi rust=$rust_abi kotlin=$kotlin_abi" >&2
  exit 1
fi

while read -r method descriptor; do
  [[ "$method" == native* ]] || continue
  symbol="Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_${method}"
  if ! rg -q "fn ${symbol}\\(" "$rust_source"; then
    echo "missing Rust JNI export for $method $descriptor" >&2
    exit 1
  fi
  if ! rg -q "external fun ${method}\\(" "$kotlin_source"; then
    echo "missing Kotlin JNI declaration for $method $descriptor" >&2
    exit 1
  fi
done < "$contract_file"

rust_channel="$(sed -n 's/^channel = "\([^"]*\)"$/\1/p' "$toolchain_file")"
rust_version="$(sed -n 's/^rust-version = "\([^"]*\)"$/\1/p' "$manifest")"
if [[ -z "$rust_channel" || "$rust_channel" != "$rust_version" ]]; then
  echo "Rust toolchain drift: rust-toolchain=$rust_channel Cargo.rust-version=$rust_version" >&2
  exit 1
fi

warp_link_revisions="$(sed -n '/git = "https:\/\/github.com\/AldenClark\/warp-link"/s/.*rev = "\([0-9a-f]*\)".*/\1/p' "$manifest")"
warp_link_revision_count="$(printf '%s\n' "$warp_link_revisions" | sed '/^$/d' | wc -l | tr -d ' ')"
warp_link_unique_revision="$(printf '%s\n' "$warp_link_revisions" | sed '/^$/d' | sort -u)"
if [[ "$warp_link_revision_count" != "2" || ! "$warp_link_unique_revision" =~ ^[0-9a-f]{40}$ ]]; then
  echo "warp-link packages must share one exact full Git revision" >&2
  exit 1
fi

for property in pushgo.androidMinSdk pushgo.androidNdkVersion pushgo.cargoNdkVersion; do
  value="$(sed -n "s/^${property}=//p" "$gradle_properties")"
  if [[ -z "$value" ]]; then
    echo "missing native build authority property: $property" >&2
    exit 1
  fi
  for consumer in "$repo_dir/app/build.gradle.kts" "$native_build"; do
    rg -q "$property" "$consumer" || {
      echo "native build property is not consumed by $consumer: $property" >&2
      exit 1
    }
  done
done
rg -q '^distributionSha256Sum=[0-9a-f]{64}$' "$gradle_wrapper" || {
  echo "Gradle distribution checksum is not pinned" >&2
  exit 1
}
rg -q '<verify-metadata>true</verify-metadata>' "$gradle_verification" || {
  echo "Gradle dependency metadata verification must be enabled" >&2
  exit 1
}
rg -q '<sha256 value="[0-9a-f]{64}"' "$gradle_verification" || {
  echo "Gradle dependency checksums are missing" >&2
  exit 1
}
rg -q 'cargo deny --manifest-path native/quinn-jni/Cargo.toml' "$release_workflow" || {
  echo "Android release workflow must enforce cargo-deny advisories, licenses, sources, and duplicate policy" >&2
  exit 1
}

rg -q 'cargo fetch .*--locked' "$native_build" || {
  echo "native build must prefetch with --locked" >&2
  exit 1
}
rg -q 'cargo ndk .*build --release --frozen' "$native_build" || {
  echo "native release build must run --frozen" >&2
  exit 1
}
if rg -q 'ANDROID_API_LEVEL' "$release_workflow"; then
  echo "release workflow must consume the Gradle minSdk authority, not ANDROID_API_LEVEL" >&2
  exit 1
fi
if rg '^\s*uses:' "$release_workflow" | rg -qv '@[0-9a-f]{40}([[:space:]]|$)'; then
  echo "all Android release actions must be pinned to full commit SHAs" >&2
  exit 1
fi
checkjni_line="$(rg -n 'Run real JNI instrumentation with CheckJNI' "$release_workflow" | cut -d: -f1)"
first_secret_line="$(rg -n '\$\{\{ secrets\.' "$release_workflow" | head -1 | cut -d: -f1)"
if [[ -z "$checkjni_line" || -z "$first_secret_line" || "$first_secret_line" -le "$checkjni_line" ]]; then
  echo "release secrets must not be loaded before the real JNI/CheckJNI gate" >&2
  exit 1
fi

echo "JNI/toolchain/workflow contract gate passed (ABI $contract_abi, Rust $rust_channel)."
