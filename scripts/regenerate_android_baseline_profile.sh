#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
device_serial="${ANDROID_BASELINE_PROFILE_DEVICE_SERIAL:-}"

blocked() {
  printf 'status=BLOCKED\nreason=%s\n' "$1" >&2
  exit 2
}

[[ -n "$device_serial" ]] || blocked "ANDROID_BASELINE_PROFILE_DEVICE_SERIAL is required"
command -v adb >/dev/null 2>&1 || blocked "adb is unavailable"
[[ "$(adb -s "$device_serial" get-state 2>/dev/null || true)" == "device" ]] || blocked "requested profile device is unavailable"
[[ "$device_serial" == emulator-* ]] || blocked "profile generation must use a controlled emulator because it installs the production applicationId"
[[ "$(adb -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || blocked "profile generation target is not a controlled emulator"

python3 - "$repo_root" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
profile_root = root / "app/src/release/generated/baselineProfiles"
for name in ("baseline-prof.txt", "startup-prof.txt"):
    path = profile_root / name
    if path.is_file():
        path.unlink()
PY

ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" \
  clean \
  :app:generateReleaseBaselineProfile \
  --console=plain \
  "-Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.macrobenchmark.BaselineProfileGenerator"
"$repo_root/gradlew" :app:assembleRelease --console=plain
python3 "$repo_root/scripts/verify_android_performance_contract.py"
