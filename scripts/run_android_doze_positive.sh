#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
device_serial="${ANDROID_SERIAL:-}"
package_name="io.ethan.pushgo"
permission="android.permission.POST_NOTIFICATIONS"
control_activity="$package_name/.testing.BenchmarkUnstopActivity"
main_activity="$package_name/.MainActivity"
run_dir="$(mktemp -d "${TMPDIR:-/tmp}/pushgo-doze-positive.XXXXXX")"
ui_dump="$run_dir/window.xml"
run_id="$(date -u +%Y%m%d-%H%M%S)-$$"
device_ui_dump="/data/local/tmp/pushgo-doze-positive-$run_id.xml"
adb_timeout_seconds="${QUALITY_ADB_TIMEOUT_SECONDS:-8}"
lock_timeout_seconds="${QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS:-15}"
adb_binary="$(command -v adb || true)"
results_root="${QUALITY_RESULTS_ROOT:-${RESULTS_ROOT:-$repo_root/build/quality-results/android-doze-positive}}"
failure_evidence_dir="$results_root/failure-$run_id"
device_lock_root="${QUALITY_ANDROID_LOCK_ROOT:-${TMPDIR:-/tmp}/pushgo-android-quality-locks}"
device_lock_dir=""
device_lock_acquired=0
failure_evidence_saved=0
prepared=0
baseline_captured=0
original_granted=false
original_user_set=0
original_user_fixed=0
original_whitelisted=false
last_dump_failure=""

blocked() {
  echo "status=BLOCKED"
  echo "reason=$1"
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
  [[ -n "$device_lock_dir" ]] || return 0
  [[ -f "$device_lock_dir/pid" ]] && owner_pid="$(<"$device_lock_dir/pid")"
  if [[ "$owner_pid" == "$$" ]]; then
    rm -f "$device_lock_dir/pid"
    rmdir "$device_lock_dir" 2>/dev/null || true
  fi
  device_lock_dir=""
  device_lock_acquired=0
}

capture_failure_evidence() {
  local reason="${1:-unknown}"
  [[ "$failure_evidence_saved" -eq 1 ]] && return 0
  failure_evidence_saved=1
  mkdir -p "$failure_evidence_dir" || return 1
  printf 'reason=%s\nserial=%s\nrun_id=%s\n' "$reason" "$device_serial" "$run_id" \
    >"$failure_evidence_dir/metadata.txt"
  adb_with_timeout -s "$device_serial" shell pidof "$package_name" \
    >"$failure_evidence_dir/app-pid.txt" 2>&1 || true
  adb_with_timeout -s "$device_serial" shell dumpsys window windows \
    >"$failure_evidence_dir/window-focus.txt" 2>&1 || true
  adb_with_timeout -s "$device_serial" logcat -b crash -d \
    >"$failure_evidence_dir/crash-buffer.txt" 2>&1 || true
  if dump_ui; then
    cp "$ui_dump" "$failure_evidence_dir/window.xml"
  else
    printf '%s\n' "$last_dump_failure" >"$failure_evidence_dir/ui-dump-failure.txt"
  fi
}

failed() {
  echo "status=FAILED"
  echo "reason=$1"
  capture_failure_evidence "$1" || true
  if [[ -s "$ui_dump" ]]; then
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
  elif [[ -n "$last_dump_failure" ]]; then
    echo "ui_dump_failure=$last_dump_failure"
  fi
  echo "failure_evidence_dir=$failure_evidence_dir"
  exit 1
}

[[ -n "$device_serial" ]] || blocked "ANDROID_SERIAL is required"
[[ -n "$adb_binary" ]] || blocked "adb is unavailable"
[[ "$adb_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || blocked "QUALITY_ADB_TIMEOUT_SECONDS must be a positive integer"
[[ "$lock_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || blocked "QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS must be a positive integer"
[[ "$(adb_with_timeout -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || \
  blocked "Doze journey changes battery-optimization state and requires a controlled emulator"

is_whitelisted() {
  adb_with_timeout -s "$device_serial" shell dumpsys deviceidle whitelist | rg -q "$package_name"
}

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
  return "$restore_failed"
}

restore_battery_optimization() {
  if [[ "$original_whitelisted" == "true" ]]; then
    adb_with_timeout -s "$device_serial" shell cmd deviceidle whitelist +"$package_name" >/dev/null 2>&1
  else
    adb_with_timeout -s "$device_serial" shell cmd deviceidle whitelist -"$package_name" >/dev/null 2>&1
  fi
}

clear_session() {
  local clear_output=""
  [[ "$prepared" -eq 1 ]] || return 0
  adb_with_timeout -s "$device_serial" shell am force-stop "$package_name" >/dev/null 2>&1 || true
  adb_with_timeout -s "$device_serial" logcat -c >/dev/null 2>&1 || true
  clear_output="$(adb_with_timeout -s "$device_serial" shell am start -W \
    -n "$control_activity" \
    --ez io.ethan.pushgo.testing.CLEAR_SESSION true 2>&1)" || true
  [[ "$clear_output" == *"Status: ok"* ]] || return 1
  if adb_with_timeout -s "$device_serial" logcat -d -s PushGoQualityControl:E '*:S' | rg -q 'quality control failed'; then
    return 1
  fi
  prepared=0
}

cleanup() {
  local status=$?
  if [[ "$device_lock_acquired" -ne 1 ]]; then
    rm -rf "$run_dir"
    exit "$status"
  fi
  if [[ "$status" -ne 0 ]]; then
    capture_failure_evidence "runner-exit-$status" || true
  fi
  adb_with_timeout -s "$device_serial" shell am force-stop "$package_name" >/dev/null 2>&1 || true
  if ! clear_session; then
    echo "cleanup_status=FAILED"
    echo "cleanup_reason=App-owned Doze session was not cleared"
    [[ "$status" -ne 0 ]] || status=1
  fi
  if [[ "$baseline_captured" -eq 1 ]]; then
    if ! restore_permission; then
      echo "cleanup_status=FAILED"
      echo "cleanup_reason=original notification permission state was not restored"
      [[ "$status" -ne 0 ]] || status=1
    fi
    if ! restore_battery_optimization; then
      echo "cleanup_status=FAILED"
      echo "cleanup_reason=original battery optimization state was not restored"
      [[ "$status" -ne 0 ]] || status=1
    fi
  fi
  adb_with_timeout -s "$device_serial" shell am force-stop "$package_name" >/dev/null 2>&1 || true
  adb_with_timeout -s "$device_serial" shell rm -f "$device_ui_dump" >/dev/null 2>&1 || true
  release_device_lock
  rm -rf "$run_dir"
  exit "$status"
}
trap cleanup EXIT

acquire_device_lock

if [[ "${QUALITY_ANDROID_SKIP_INSTALL:-0}" != "1" ]]; then
  "$repo_root/gradlew" :app:installDebug
fi

permission_line="$(adb_with_timeout -s "$device_serial" shell dumpsys package "$package_name" \
  | awk '/android.permission.POST_NOTIFICATIONS: granted=/{print; exit}')"
[[ "$permission_line" == *"granted=true"* ]] && original_granted=true
[[ "$permission_line" == *"USER_SET"* ]] && original_user_set=1
[[ "$permission_line" == *"USER_FIXED"* ]] && original_user_fixed=1
is_whitelisted && original_whitelisted=true
baseline_captured=1

dump_ui() {
  local dump_output=""
  rm -f "$ui_dump"
  if ! dump_output="$(adb_with_timeout -s "$device_serial" shell uiautomator dump "$device_ui_dump" 2>&1)"; then
    last_dump_failure="uiautomator dump failed: $(printf '%s' "$dump_output" | tr '\n' ' ')"
    return 1
  fi
  if ! adb_with_timeout -s "$device_serial" exec-out cat "$device_ui_dump" >"$ui_dump" 2>"$run_dir/ui-copy.stderr"; then
    last_dump_failure="could not read UI dump from device: $(tr '\n' ' ' <"$run_dir/ui-copy.stderr")"
    return 1
  fi
  if ! rg -q '<hierarchy' "$ui_dump"; then
    last_dump_failure="UI dump did not contain a hierarchy: $(printf '%s' "$dump_output" | tr '\n' ' ')"
    return 1
  fi
  last_dump_failure=""
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

node_text() {
  local resource_id="$1"
  python3 - "$ui_dump" "$resource_id" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
query = sys.argv[2]
for node in root.iter("node"):
    resource_id = node.attrib.get("resource-id", "")
    if resource_id == query or resource_id.endswith(":id/" + query):
        print(node.attrib.get("text", ""))
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

wait_for_absent() {
  local resource_id="$1"
  local timeout_seconds="${2:-10}"
  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    if dump_ui && ! node_center "$resource_id" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.25
  done
  return 1
}

tap_node() {
  local resource_id="$1"
  wait_for_node "$resource_id" 15 || failed "UI node was not reachable: $resource_id"
  # wait_for_node leaves the last successful dump in ui_dump. Re-dumping here
  # races with Compose/system-surface transitions and can hide a reachable node.
  local center
  center="$(node_center "$resource_id")" || failed "UI node disappeared before tapping: $resource_id"
  adb_with_timeout -s "$device_serial" shell input tap "${center%,*}" "${center#*,}"
}

prepare_session() {
  local session_id="$1"
  local payload
  local prepare_output=""
  payload="$(python3 - "$session_id" <<'PY'
import base64
import json
import sys

payload = {
    "schema_version": 1,
    "session_id": sys.argv[1],
    "fixture": "empty.clean",
    "system_capabilities": ["doze_reminder_journey"],
    "faults": {},
}
print(base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode())
PY
)"
  adb_with_timeout -s "$device_serial" logcat -c
  prepare_output="$(adb_with_timeout -s "$device_serial" shell am start -W \
    -n "$control_activity" \
    --es io.ethan.pushgo.testing.SESSION_BASE64 "$payload" 2>&1)" || \
    blocked "App-owned Doze fixture could not be prepared: $prepare_output"
  [[ "$prepare_output" == *"Status: ok"* ]] || \
    blocked "App-owned Doze control did not launch: $prepare_output"
  prepared=1
  if adb_with_timeout -s "$device_serial" logcat -d -s PushGoQualityControl:E '*:S' | rg -q 'quality control failed'; then
    blocked "App-owned Doze fixture failed inside the app"
  fi
}

launch_app() {
  adb_with_timeout -s "$device_serial" shell am force-stop "$package_name"
  adb_with_timeout -s "$device_serial" shell am start -W -n "$main_activity" >/dev/null
}

open_settings_from_root() {
  wait_for_node "nav.item.channels" 10 || failed "Channels navigation was not reachable"
  tap_node "nav.item.channels"
  wait_for_node "action.channels.settings" 10 || failed "Channels did not expose Settings"
  tap_node "action.channels.settings"
  wait_for_node "screen.settings.content" 10 || failed "PushGo did not open Settings"
}

adb_with_timeout -s "$device_serial" shell pm grant "$package_name" "$permission"
adb_with_timeout -s "$device_serial" shell pm clear-permission-flags \
  "$package_name" "$permission" user-set user-fixed
adb_with_timeout -s "$device_serial" shell cmd deviceidle whitelist -"$package_name" >/dev/null

prepare_session "android-doze-positive-$(date +%s)"
launch_app
wait_for_node "field.delivery_guard.title" 10 || \
  failed "PushGo did not explain the active battery optimization risk"
dump_ui || failed "Doze explanation UI could not be captured"
[[ "$(node_text "field.delivery_guard.title")" == "Battery optimization is enabled" ]] || \
  failed "PushGo showed the wrong delivery-risk explanation"
wait_for_node "field.delivery_guard.message" 3 || failed "PushGo did not explain delayed delivery"
wait_for_node "field.delivery_guard.urgency" 3 || failed "PushGo did not explain Doze urgency"

adb_with_timeout -s "$device_serial" shell input keyevent KEYCODE_BACK
wait_for_absent "field.delivery_guard.title" 5 || failed "PushGo did not dismiss the startup Doze explanation"
open_settings_from_root
wait_for_node "banner.settings.doze_enabled" 10 || failed "Settings did not show the Doze risk banner"
tap_node "action.settings.open_battery_optimization_settings"
wait_for_node "android:id/button1" 10 || failed "PushGo did not open the real unrestricted-mode system dialog"
dump_ui || failed "System battery dialog could not be captured"
[[ "$(node_text "android:id/button1")" == "Allow" ]] || failed "System battery dialog did not expose the Allow action"
tap_node "android:id/button1"

wait_for_node "screen.settings.content" 10 || failed "PushGo did not resume Settings after battery settings"
is_whitelisted || failed "Android did not exempt PushGo after the real system Allow action"
wait_for_absent "banner.settings.doze_enabled" 10 || \
  failed "Settings kept the Doze banner after unrestricted mode was enabled"

adb_with_timeout -s "$device_serial" shell cmd deviceidle whitelist -"$package_name" >/dev/null
launch_app
wait_for_node "field.delivery_guard.title" 10 || failed "Doze reminder did not return after restoring restricted mode"
adb_with_timeout -s "$device_serial" shell input keyevent KEYCODE_BACK
wait_for_absent "field.delivery_guard.title" 5 || failed "PushGo did not return from the restored Doze explanation"
open_settings_from_root
wait_for_node "banner.settings.doze_enabled" 10 || failed "Settings lost the restored Doze risk banner"
tap_node "action.settings.snooze_doze_reminder"
wait_for_absent "banner.settings.doze_enabled" 5 || failed "One-month snooze did not hide the Doze banner"
is_whitelisted && failed "Snooze incorrectly changed Android battery optimization state"

launch_app
wait_for_node "nav.item.channels" 10 || failed "PushGo did not relaunch after snoozing Doze"
wait_for_absent "field.delivery_guard.title" 3 || failed "Snoozed Doze reminder reappeared in the same session"
open_settings_from_root
wait_for_absent "banner.settings.doze_enabled" 3 || failed "Snoozed Doze banner reappeared in the same session"

clear_session || failed "First App-owned Doze session could not be cleared"
prepare_session "android-doze-isolation-$(date +%s)"
launch_app
wait_for_node "field.delivery_guard.title" 10 || \
  failed "A new App-owned session inherited the previous session's Doze snooze"

echo "status=PASSED"
echo "claim=restricted explanation -> Settings banner -> real system unrestricted -> return refresh -> session-owned snooze -> clean-session reminder"
