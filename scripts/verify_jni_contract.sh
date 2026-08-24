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
  if ! grep -Eq "fn ${symbol}\\(" "$rust_source"; then
    echo "missing Rust JNI export for $method $descriptor" >&2
    exit 1
  fi
  if ! grep -Eq "external fun ${method}\\(" "$kotlin_source"; then
    echo "missing Kotlin JNI declaration for $method $descriptor" >&2
    exit 1
  fi
done < "$contract_file"

# The method names above guard symbol presence. The descriptor pass below also
# proves the Java ABI types on both sides; JNI short symbols do not encode them.
python3 - "$contract_file" "$kotlin_source" "$rust_source" <<'PY'
import re
import sys
from pathlib import Path

contract_path, kotlin_path, rust_path = map(Path, sys.argv[1:])
contract = {}
for raw in contract_path.read_text().splitlines():
    parts = raw.split()
    if len(parts) == 2 and parts[0].startswith("native"):
        contract[parts[0]] = parts[1]

java_types = {
    "Int": "I",
    "Long": "J",
    "String": "Ljava/lang/String;",
    "Unit": "V",
}

def kotlin_descriptor(parameters, return_type):
    encoded = []
    if parameters.strip():
        for parameter in parameters.split(","):
            type_name = parameter.split(":", 1)[1].strip().rstrip("?")
            encoded.append(java_types[type_name])
    normalized_return = return_type.rstrip("?") if return_type else "Unit"
    return f"({''.join(encoded)}){java_types[normalized_return]}"

kotlin_contract = {}
for match in re.finditer(
    r"external\s+fun\s+(native\w+)\s*\(([^)]*)\)(?:\s*:\s*([A-Za-z?]+))?",
    kotlin_path.read_text(),
):
    kotlin_contract[match.group(1)] = kotlin_descriptor(match.group(2), match.group(3))

rust_param_types = {
    "jint": "I",
    "jlong": "J",
    "JString": "Ljava/lang/String;",
}
rust_return_types = {
    None: "V",
    "jint": "I",
    "jlong": "J",
    "jstring": "Ljava/lang/String;",
}
rust_contract = {}
pattern = re.compile(
    r'pub\s+extern\s+"system"\s+fn\s+'
    r'Java_io_ethan_pushgo_notifications_WarpLinkNativeBridge_(native\w+)\s*'
    r'\((.*?)\)\s*(?:->\s*([A-Za-z0-9_:<>]+))?\s*\{',
    re.S,
)
for match in pattern.finditer(rust_path.read_text()):
    parameters = [item.strip() for item in match.group(2).split(",") if item.strip()]
    declared_types = [item.split(":", 1)[1].strip().split("<", 1)[0] for item in parameters]
    if declared_types[:2] != ["EnvUnowned", "JClass"]:
        raise SystemExit(f"unexpected JNI receiver types for {match.group(1)}: {declared_types[:2]}")
    encoded = "".join(rust_param_types[type_name] for type_name in declared_types[2:])
    rust_contract[match.group(1)] = f"({encoded}){rust_return_types[match.group(3)]}"

for side, actual in (("Kotlin", kotlin_contract), ("Rust", rust_contract)):
    missing = sorted(set(contract) - set(actual))
    extra = sorted(set(actual) - set(contract))
    mismatched = sorted(
        method for method in set(contract) & set(actual)
        if contract[method] != actual[method]
    )
    if missing or extra or mismatched:
        details = [
            f"{method}: expected={contract.get(method)} actual={actual.get(method)}"
            for method in mismatched
        ]
        raise SystemExit(
            f"{side} JNI descriptor drift; missing={missing} extra={extra} "
            f"mismatched={details}"
        )
PY

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
    grep -Fq "$property" "$consumer" || {
      echo "native build property is not consumed by $consumer: $property" >&2
      exit 1
    }
  done
done
grep -Eq '^distributionSha256Sum=[0-9a-f]{64}$' "$gradle_wrapper" || {
  echo "Gradle distribution checksum is not pinned" >&2
  exit 1
}
grep -Fq '<verify-metadata>true</verify-metadata>' "$gradle_verification" || {
  echo "Gradle dependency metadata verification must be enabled" >&2
  exit 1
}
grep -Eq '<sha256 value="[0-9a-f]{64}"' "$gradle_verification" || {
  echo "Gradle dependency checksums are missing" >&2
  exit 1
}
grep -Eq 'cargo fetch .*--locked' "$native_build" || {
  echo "native build must prefetch with --locked" >&2
  exit 1
}
grep -Eq 'cargo ndk .*build --release --frozen' "$native_build" || {
  echo "native release build must run --frozen" >&2
  exit 1
}
if grep -Fq 'ANDROID_API_LEVEL' "$release_workflow"; then
  echo "release workflow must consume the Gradle minSdk authority, not ANDROID_API_LEVEL" >&2
  exit 1
fi
if grep -E '^[[:space:]]*uses:' "$release_workflow" | grep -Eqv '@[0-9a-f]{40}([[:space:]]|$)'; then
  echo "all Android release actions must be pinned to full commit SHAs" >&2
  exit 1
fi
echo "JNI/toolchain/workflow contract gate passed (ABI $contract_abi, Rust $rust_channel)."
