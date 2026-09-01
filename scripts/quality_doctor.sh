#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
allow_no_device=false
if [[ "${1:-}" == "--allow-no-device" ]]; then
  allow_no_device=true
fi

fail() {
  printf 'status=BLOCKED\nreason=%s\n' "$1"
  exit 2
}

command -v java >/dev/null 2>&1 || fail "java_not_found"
command -v adb >/dev/null 2>&1 || fail "adb_not_found"
[[ -x "$repo_root/gradlew" ]] || fail "gradle_wrapper_missing"
[[ -f "$repo_root/app/src/main/java/io/ethan/pushgo/testing/QualityRuntime.kt" ]] \
  || fail "quality_runtime_missing"

if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  device_line="$(
    adb devices -l | awk -v target="$ANDROID_SERIAL" \
      'NR > 1 && $1 == target && $2 == "device" { print; exit }'
  )"
  [[ -n "$device_line" ]] || fail "requested_android_device_unavailable:$ANDROID_SERIAL"
else
  # Routine automation is emulator-first so connecting a personal phone cannot
  # silently widen the lane, alter its API level, or double its execution cost.
  device_line="$(
    adb devices -l | awk \
      'NR > 1 && $2 == "device" && $1 ~ /^emulator-/ { print; found = 1; exit }
       END { if (!found) exit 1 }'
  )" || device_line="$(adb devices -l | awk 'NR > 1 && $2 == "device" { print; exit }')"
fi
if [[ -z "$device_line" ]]; then
  if [[ "$allow_no_device" == true ]]; then
    printf 'status=READY\n'
    printf 'platform=android\n'
    printf 'device=NOT_RUN\n'
    printf 'storage_contract=app-owned\n'
    printf 'release_runtime=disabled\n'
    exit 0
  fi
  fail "no_authorized_android_device"
fi

device_serial="$(printf '%s\n' "$device_line" | awk '{print $1}')"
api_level="$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$api_level" =~ ^[0-9]+$ ]] || fail "device_api_unreadable:$device_serial"

qemu_target="$(adb -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')"
wakefulness="$(
  adb -s "$device_serial" shell dumpsys power 2>/dev/null \
    | awk -F= '/^[[:space:]]*mWakefulness=/{gsub(/[[:space:]\r]/, "", $2); print $2; exit}'
)"
if [[ "$wakefulness" != "Awake" ]]; then
  if [[ "$qemu_target" != "1" ]]; then
    fail "physical_android_device_must_be_awake_and_unlocked:$device_serial"
  fi
  adb -s "$device_serial" shell input keyevent KEYCODE_WAKEUP >/dev/null \
    || fail "android_emulator_wakeup_failed:$device_serial"
  adb -s "$device_serial" shell wm dismiss-keyguard >/dev/null \
    || fail "android_emulator_keyguard_dismiss_failed:$device_serial"
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    wakefulness="$(
      adb -s "$device_serial" shell dumpsys power 2>/dev/null \
        | awk -F= '/^[[:space:]]*mWakefulness=/{gsub(/[[:space:]\r]/, "", $2); print $2; exit}'
    )"
    [[ "$wakefulness" == "Awake" ]] && break
    sleep 0.2
  done
fi
[[ "$wakefulness" == "Awake" ]] || fail "android_device_not_interactive:$device_serial:$wakefulness"

keyguard_showing="$(
  adb -s "$device_serial" shell dumpsys window policy 2>/dev/null \
    | awk -F= '/^[[:space:]]*showing=/{gsub(/[[:space:]\r]/, "", $2); print $2; exit}'
)"
[[ "$keyguard_showing" == "false" ]] \
  || fail "android_device_keyguard_state_not_unlocked:$device_serial:${keyguard_showing:-unreadable}"

printf 'status=READY\n'
printf 'platform=android\n'
printf 'device_serial=%s\n' "$device_serial"
printf 'device_api=%s\n' "$api_level"
printf 'device_interactive=awake_unlocked\n'
printf 'fixture_empty=empty.clean\n'
printf 'storage_contract=app-owned\n'
printf 'release_runtime=disabled\n'
