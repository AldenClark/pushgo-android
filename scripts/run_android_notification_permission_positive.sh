#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
device_serial="${ANDROID_SERIAL:-${1:-}}"
package_name="io.ethan.pushgo"
test_runner="$package_name.test/io.ethan.pushgo.test.PushGoAndroidJUnitRunner"
test_class="$package_name.testing.QualityNotificationPermissionJourneyInstrumentedTest"
test_method="enabledSystemDecisionRefreshesTheRealAppAndRemovesDisabledDeliveryState"
test_selector="$test_class#$test_method"
permission="android.permission.POST_NOTIFICATIONS"
adb_timeout_seconds="${QUALITY_ADB_TIMEOUT_SECONDS:-8}"
adb_binary="$(command -v adb || true)"
results_root="${QUALITY_RESULTS_ROOT:-$repo_root/build/quality-results}"
mkdir -p "$results_root/android-notification-permission"
run_dir="$(mktemp -d "$results_root/android-notification-permission/run.XXXXXX")"
ui_dump="$run_dir/window.xml"
device_ui_dump="/sdcard/pushgo-notification-permission.xml"
lock_timeout_seconds="${QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS:-15}"
device_lock_root="${QUALITY_ANDROID_LOCK_ROOT:-${TMPDIR:-/tmp}/pushgo-android-quality-locks}"
device_lock_dir=""
device_lock_acquired=0
prepared=0
baseline_captured=0
original_granted="false"
original_user_set=0
original_user_fixed=0

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

blocked() {
  echo "status=BLOCKED"
  echo "reason=$1"
  exit 2
}

failed() {
  echo "status=FAILED"
  echo "reason=$1"
  if declare -F dump_ui >/dev/null && dump_ui; then
    python3 - "$ui_dump" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
shown = 0
for node in root.iter("node"):
    values = [node.attrib.get(key, "") for key in ("package", "resource-id", "text")]
    if any(values):
        print("ui=" + "|".join(values))
        shown += 1
        if shown >= 80:
            break
PY
  fi
  adb_with_timeout -s "$device_serial" shell dumpsys window windows \
    | sed -n 's/.*mCurrentFocus=//p' | head -n 1 || true
  exit 1
}

test_system_failed() {
  echo "status=FAILED_TEST_SYSTEM"
  echo "reason=$1"
  exit 3
}

acquire_device_lock() {
  local safe_serial owner_pid deadline
  safe_serial="$(printf '%s' "$device_serial" | tr -c 'A-Za-z0-9_.-' '_')"
  device_lock_dir="$device_lock_root/$safe_serial"
  mkdir -p "$device_lock_root"
  deadline=$((SECONDS + lock_timeout_seconds))
  while ! mkdir "$device_lock_dir" 2>/dev/null; do
    owner_pid=""
    [[ -f "$device_lock_dir/pid" ]] && owner_pid="$(<"$device_lock_dir/pid")"
    if [[ "$owner_pid" =~ ^[0-9]+$ ]] && ! kill -0 "$owner_pid" >/dev/null 2>&1; then
      rmdir "$device_lock_dir" 2>/dev/null || true
      continue
    fi
    if (( SECONDS >= deadline )); then
      device_lock_dir=""
      blocked "selected Android device is busy: $device_serial"
    fi
    sleep 0.25
  done
  printf '%s\n' "$$" >"$device_lock_dir/pid"
  device_lock_acquired=1
}

release_device_lock() {
  local owner_pid=""
  [[ "$device_lock_acquired" -eq 1 && -n "$device_lock_dir" ]] || return 0
  [[ -f "$device_lock_dir/pid" ]] && owner_pid="$(<"$device_lock_dir/pid")"
  if [[ "$owner_pid" == "$$" ]]; then
    rm -f "$device_lock_dir/pid"
    rmdir "$device_lock_dir" 2>/dev/null || true
  fi
  device_lock_dir=""
  device_lock_acquired=0
}

[[ -n "$device_serial" ]] || blocked "ANDROID_SERIAL is required"
[[ -n "$adb_binary" ]] || blocked "adb is unavailable"
command -v python3 >/dev/null 2>&1 || blocked "python3 is unavailable"
command -v rg >/dev/null 2>&1 || blocked "rg is unavailable before notification permission preparation"
[[ "$adb_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || \
  blocked "QUALITY_ADB_TIMEOUT_SECONDS must be a positive integer"
[[ "$lock_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || \
  blocked "QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS must be a positive integer"
[[ "$(adb_with_timeout -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || \
  blocked "notification permission journey is destructive to permission state and requires a controlled emulator"
api_level="$(adb_with_timeout -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$api_level" =~ ^[0-9]+$ && "$api_level" -ge 33 ]] || \
  blocked "notification permission journey requires Android 13 or newer"

restore_permission() {
  local restore_failed=0
  local restored_line=""
  adb_with_timeout -s "$device_serial" shell pm clear-permission-flags \
    "$package_name" "$permission" user-set user-fixed >/dev/null 2>&1 || restore_failed=1
  if [[ "$original_granted" == "true" ]]; then
    adb_with_timeout -s "$device_serial" shell pm grant "$package_name" "$permission" >/dev/null 2>&1 || restore_failed=1
  else
    adb_with_timeout -s "$device_serial" shell pm revoke "$package_name" "$permission" >/dev/null 2>&1 || restore_failed=1
  fi
  if [[ "$original_user_set" -eq 1 ]]; then
    adb_with_timeout -s "$device_serial" shell pm set-permission-flags \
      "$package_name" "$permission" user-set >/dev/null 2>&1 || restore_failed=1
  fi
  if [[ "$original_user_fixed" -eq 1 ]]; then
    adb_with_timeout -s "$device_serial" shell pm set-permission-flags \
      "$package_name" "$permission" user-fixed >/dev/null 2>&1 || restore_failed=1
  fi
  restored_line="$(adb_with_timeout -s "$device_serial" shell dumpsys package "$package_name" \
    | awk '/android.permission.POST_NOTIFICATIONS: granted=/{print; exit}')"
  [[ "$original_granted" == "true" && "$restored_line" == *"granted=true"* ]] || \
    [[ "$original_granted" == "false" && "$restored_line" == *"granted=false"* ]] || restore_failed=1
  [[ "$original_user_set" -eq 1 && "$restored_line" == *"USER_SET"* ]] || \
    [[ "$original_user_set" -eq 0 && "$restored_line" != *"USER_SET"* ]] || restore_failed=1
  [[ "$original_user_fixed" -eq 1 && "$restored_line" == *"USER_FIXED"* ]] || \
    [[ "$original_user_fixed" -eq 0 && "$restored_line" != *"USER_FIXED"* ]] || restore_failed=1
  return "$restore_failed"
}

cleanup() {
  local status=$?
  local clear_output=""
  if [[ "$device_lock_acquired" -eq 1 ]]; then
    adb_with_timeout -s "$device_serial" shell am force-stop "$package_name" >/dev/null 2>&1 || true
    if [[ "$prepared" -eq 1 ]]; then
      adb_with_timeout -s "$device_serial" logcat -c >/dev/null 2>&1 || true
      clear_output="$(adb_with_timeout -s "$device_serial" shell am start -W \
        -n "$package_name/.testing.BenchmarkUnstopActivity" \
        --ez io.ethan.pushgo.testing.CLEAR_SESSION true 2>&1)" || true
      if [[ "$clear_output" != *"Status: ok"* ]] || \
        adb_with_timeout -s "$device_serial" logcat -d -s PushGoQualityControl:E '*:S' | rg -q 'quality control failed'; then
        echo "cleanup_status=FAILED"
        echo "cleanup_reason=App-owned notification permission session was not cleared"
        [[ "$status" -ne 0 ]] || status=1
      fi
    fi
    if [[ "$baseline_captured" -eq 1 ]] && ! restore_permission; then
      echo "cleanup_status=FAILED"
      echo "cleanup_reason=original notification permission state was not restored"
      [[ "$status" -ne 0 ]] || status=1
    fi
    adb_with_timeout -s "$device_serial" shell am force-stop "$package_name" >/dev/null 2>&1 || true
    adb_with_timeout -s "$device_serial" shell rm -f "$device_ui_dump" >/dev/null 2>&1 || true
    release_device_lock
  fi
  printf '%s\n' "$status" > "$run_dir/exit-status.txt"
  echo "evidence_dir=$run_dir"
  exit "$status"
}
trap cleanup EXIT

acquire_device_lock

"$repo_root/gradlew" :app:installDebug :app:installDebugAndroidTest
permission_line="$(adb_with_timeout -s "$device_serial" shell dumpsys package "$package_name" \
  | awk '/android.permission.POST_NOTIFICATIONS: granted=/{print; exit}')"
[[ "$permission_line" == *"granted=true"* ]] && original_granted="true"
[[ "$permission_line" == *"USER_SET"* ]] && original_user_set=1
[[ "$permission_line" == *"USER_FIXED"* ]] && original_user_fixed=1
baseline_captured=1

session_id="android-notification-permission-$(date +%s)"
payload="$(python3 - "$session_id" <<'PY'
import base64
import json
import sys

payload = {
    "schema_version": 1,
    "session_id": sys.argv[1],
    "fixture": "empty.clean",
    "system_capabilities": ["notification_permission_journey"],
    "faults": {},
}
print(base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode())
PY
)"
adb_with_timeout -s "$device_serial" logcat -c
prepare_output="$(adb_with_timeout -s "$device_serial" shell am start -W \
  -n "$package_name/.testing.BenchmarkUnstopActivity" \
  --es io.ethan.pushgo.testing.SESSION_BASE64 "$payload" 2>&1)" || \
  blocked "App-owned notification permission fixture could not be prepared: $prepare_output"
[[ "$prepare_output" == *"Status: ok"* ]] || \
  blocked "App-owned notification permission control did not launch: $prepare_output"
prepared=1
if adb_with_timeout -s "$device_serial" logcat -d -s PushGoQualityControl:E '*:S' | rg -q 'quality control failed'; then
  blocked "App-owned notification permission fixture failed inside the app"
fi

dump_ui() {
  adb_with_timeout -s "$device_serial" shell uiautomator dump "$device_ui_dump" >/dev/null 2>&1 || return 1
  adb_with_timeout -s "$device_serial" exec-out cat "$device_ui_dump" >"$ui_dump" 2>/dev/null || return 1
  rg -q '<hierarchy' "$ui_dump"
}

node_center() {
  local resource_id="$1"
  python3 - "$ui_dump" "$resource_id" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
query = sys.argv[2]
for node in root.iter("node"):
    resource_id = node.attrib.get("resource-id", "")
    if resource_id != query and not resource_id.endswith(":id/" + query):
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if match:
        x1, y1, x2, y2 = map(int, match.groups())
        print(f"{(x1 + x2) // 2},{(y1 + y2) // 2}")
        raise SystemExit(0)
raise SystemExit(1)
PY
}

wait_for_node() {
  local resource_id="$1"
  local timeout_seconds="${2:-15}"
  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    if dump_ui && node_center "$resource_id" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.25
  done
  return 1
}

tap_node() {
  local resource_id="$1"
  wait_for_node "$resource_id" 15 || failed "UI node was not reachable: $resource_id"
  dump_ui || failed "UI tree could not be captured before tapping $resource_id"
  local center
  center="$(node_center "$resource_id")" || failed "UI node disappeared before tapping: $resource_id"
  adb_with_timeout -s "$device_serial" shell input tap "${center%,*}" "${center#*,}"
}

adb_with_timeout -s "$device_serial" shell pm revoke "$package_name" "$permission"
adb_with_timeout -s "$device_serial" shell pm clear-permission-flags \
  "$package_name" "$permission" user-set user-fixed
adb_with_timeout -s "$device_serial" shell am force-stop "$package_name"
adb_with_timeout -s "$device_serial" shell am start -W -n "$package_name/.MainActivity" >/dev/null

tap_node "permission_deny_button"
wait_for_node "field.delivery_guard.title" 10 || \
  failed "PushGo did not explain the disabled notification outcome"
wait_for_node "field.delivery_guard.message" 3 || \
  failed "PushGo did not show the notification impact"
wait_for_node "field.delivery_guard.urgency" 3 || \
  failed "PushGo did not show the notification urgency"
tap_node "action.delivery_guard.confirm"

wait_for_node "main_switch_bar" 10 || \
  failed "PushGo did not open its real Android notification settings"
tap_node "main_switch_bar"
deadline=$((SECONDS + 10))
while (( SECONDS < deadline )); do
  permission_line="$(adb_with_timeout -s "$device_serial" shell dumpsys package "$package_name" \
    | awk '/android.permission.POST_NOTIFICATIONS: granted=/{print; exit}')"
  [[ "$permission_line" == *"granted=true"* ]] && break
  sleep 0.25
done
[[ "$permission_line" == *"granted=true"* ]] || \
  failed "Android notification permission did not become granted after the real switch action"

adb_with_timeout -s "$device_serial" shell input keyevent KEYCODE_BACK
wait_for_node "nav.item.channels" 10 || failed "PushGo did not resume after Android notification settings"
if wait_for_node "action.delivery_guard.confirm" 2; then
  failed "PushGo kept the disabled-notification prompt after permission was enabled"
fi

adb_with_timeout -s "$device_serial" shell am force-stop "$package_name"
instrumentation_output="$(adb_with_timeout -s "$device_serial" shell am instrument -w -r \
  -e class "$test_selector" "$test_runner" 2>&1)" || true
printf '%s\n' "$instrumentation_output"
printf '%s\n' "$instrumentation_output" | python3 \
  "$repo_root/scripts/verify_android_instrumentation_identity.py" \
  --expected-class "$test_class" \
  --expected-method "$test_method" || \
  test_system_failed "notification permission instrumentation identity mismatch"
[[ "$instrumentation_output" == *"OK (1 test)"* ]] || \
  failed "semantic notification permission verifier did not pass"

echo "status=PASSED"
echo "claim=denied decision -> App explanation -> real Settings grant -> return refresh -> semantic enabled state"
