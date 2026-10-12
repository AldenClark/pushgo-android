#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
allow_no_device=false
if [[ "${1:-}" == "--allow-no-device" ]]; then
  allow_no_device=true
fi
adb_timeout_seconds="${QUALITY_ADB_TIMEOUT_SECONDS:-8}"
adb_binary="$(command -v adb || true)"

fail() {
  printf 'status=BLOCKED\nreason=%s\n' "$1"
  exit 2
}

adb_with_timeout() {
  python3 - "$adb_timeout_seconds" "$adb_binary" "$@" <<'PY'
import os
import signal
import subprocess
import sys

timeout = float(sys.argv[1])
command = sys.argv[2:]
process = subprocess.Popen(
    command,
    stdin=subprocess.DEVNULL,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
    text=True,
    start_new_session=True,
)
try:
    stdout, stderr = process.communicate(timeout=timeout)
except subprocess.TimeoutExpired as error:
    stdout = error.stdout or ""
    stderr = error.stderr or ""
    if isinstance(stdout, bytes):
        stdout = stdout.decode(errors="replace")
    if isinstance(stderr, bytes):
        stderr = stderr.decode(errors="replace")
    if process.poll() is None:
        try:
            os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait()
    if stdout:
        sys.stdout.write(stdout)
    if stderr:
        sys.stderr.write(stderr)
    sys.stderr.write(f"adb command timed out after {timeout:g}s\n")
    raise SystemExit(124)

if stdout:
    sys.stdout.write(stdout)
if stderr:
    sys.stderr.write(stderr)
raise SystemExit(process.returncode)
PY
}

command -v java >/dev/null 2>&1 || fail "java_not_found"
[[ -n "$adb_binary" ]] || fail "adb_not_found"
[[ "$adb_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || fail "QUALITY_ADB_TIMEOUT_SECONDS must be a positive integer"
[[ -x "$repo_root/gradlew" ]] || fail "gradle_wrapper_missing"
[[ -f "$repo_root/app/src/main/java/io/ethan/pushgo/testing/QualityRuntime.kt" ]] \
  || fail "quality_runtime_missing"

device_listing=""
if ! device_listing="$(adb_with_timeout devices -l 2>&1)"; then
  fail "adb_devices_query_failed_or_timed_out"
fi
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  device_line="$(
    printf '%s\n' "$device_listing" | awk -v target="$ANDROID_SERIAL" \
      'NR > 1 && $1 == target && $2 == "device" { print; exit }'
  )"
  [[ -n "$device_line" ]] || fail "requested_android_device_unavailable:$ANDROID_SERIAL"
else
  # Routine automation is emulator-first so connecting a personal phone cannot
  # silently widen the lane, alter its API level, or double its execution cost.
  device_line="$(
    printf '%s\n' "$device_listing" | awk \
      'NR > 1 && $2 == "device" && $1 ~ /^emulator-/ { print; found = 1; exit }
       END { if (!found) exit 1 }'
  )" || device_line="$(printf '%s\n' "$device_listing" | awk 'NR > 1 && $2 == "device" { print; exit }')"
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
if ! api_level="$(adb_with_timeout -s "$device_serial" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r')"; then
  fail "device_api_query_failed:$device_serial"
fi
[[ "$api_level" =~ ^[0-9]+$ ]] || fail "device_api_unreadable:$device_serial"

if ! qemu_target="$(adb_with_timeout -s "$device_serial" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"; then
  fail "device_qemu_query_failed:$device_serial"
fi
power_dump=""
if ! power_dump="$(adb_with_timeout -s "$device_serial" shell dumpsys power 2>/dev/null)"; then
  fail "device_power_query_failed:$device_serial"
fi
wakefulness="$(awk -F= '/^[[:space:]]*mWakefulness=/{gsub(/[[:space:]\r]/, "", $2); print $2; exit}' <<< "$power_dump")"
if [[ "$wakefulness" != "Awake" ]]; then
  if [[ "$qemu_target" != "1" ]]; then
    fail "physical_android_device_must_be_awake_and_unlocked:$device_serial"
  fi
  adb_with_timeout -s "$device_serial" shell input keyevent KEYCODE_WAKEUP >/dev/null \
    || fail "android_emulator_wakeup_failed:$device_serial"
  adb_with_timeout -s "$device_serial" shell wm dismiss-keyguard >/dev/null \
    || fail "android_emulator_keyguard_dismiss_failed:$device_serial"
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    if ! power_dump="$(adb_with_timeout -s "$device_serial" shell dumpsys power 2>/dev/null)"; then
      fail "device_power_query_failed:$device_serial"
    fi
    wakefulness="$(awk -F= '/^[[:space:]]*mWakefulness=/{gsub(/[[:space:]\r]/, "", $2); print $2; exit}' <<< "$power_dump")"
    [[ "$wakefulness" == "Awake" ]] && break
    sleep 0.2
  done
fi
[[ "$wakefulness" == "Awake" ]] || fail "android_device_not_interactive:$device_serial:$wakefulness"

window_policy_dump=""
if ! window_policy_dump="$(adb_with_timeout -s "$device_serial" shell dumpsys window policy 2>/dev/null)"; then
  fail "device_window_policy_query_failed:$device_serial"
fi
keyguard_showing="$(awk -F= '/^[[:space:]]*showing=/{gsub(/[[:space:]\r]/, "", $2); print $2; exit}' <<< "$window_policy_dump")"
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
