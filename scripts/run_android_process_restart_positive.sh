#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
device_serial="${ANDROID_SERIAL:-${1:-}}"
package_name="io.ethan.pushgo"
expected_title="P2 Split Seed Message"
expected_body="Seeded from fixture.seed_messages for UI validation."
canonical_url="https://pushgo.dev/quality-message"
expected_handoff_url="$canonical_url"
expected_browser_display="pushgo.dev/quality-message"
documentation_url="https://pushgo.dev/guides/getting-started/"
expected_documentation_display="pushgo.dev/guides/getting-started/"
run_dir="$(mktemp -d "${TMPDIR:-/tmp}/pushgo-process-restart.XXXXXX")"
ui_dump="$run_dir/window.xml"
run_id="$(date -u +%Y%m%d-%H%M%S)-$$"
device_ui_dump="/data/local/tmp/pushgo-process-restart-$run_id.xml"
results_root="${QUALITY_RESULTS_ROOT:-${RESULTS_ROOT:-$repo_root/build/quality-results/android-process-restart}}"
failure_evidence_dir="$results_root/failure-$run_id"
adb_timeout_seconds="${QUALITY_ADB_TIMEOUT_SECONDS:-8}"
lock_timeout_seconds="${QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS:-15}"
adb_binary="$(command -v adb || true)"
device_lock_root="${QUALITY_ANDROID_LOCK_ROOT:-${TMPDIR:-/tmp}/pushgo-android-quality-locks}"
device_lock_dir=""
device_lock_acquired=0
failure_evidence_saved=0
last_dump_failure=""
prepared=0
external_surface_open=0

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
  if declare -F dump_ui >/dev/null && dump_ui; then
    python3 - "$ui_dump" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
shown = 0
for node in root.iter("node"):
    values = [node.attrib.get(key, "") for key in ("package", "resource-id", "text", "content-desc")]
    if any(values):
        print("ui=" + "|".join(values))
        shown += 1
        if shown >= 80:
            break
PY
  fi
  exit 1
}

[[ -n "$adb_binary" ]] || blocked "adb is unavailable"
command -v python3 >/dev/null 2>&1 || blocked "python3 is unavailable"
[[ "$adb_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || blocked \
  "QUALITY_ADB_TIMEOUT_SECONDS must be a positive integer"
[[ "$lock_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || blocked \
  "QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS must be a positive integer"
[[ -n "$device_serial" ]] || blocked "ANDROID_SERIAL is required"
[[ "$(adb_with_timeout -s "$device_serial" get-state 2>/dev/null || true)" == "device" ]] || \
  blocked "selected Android target is not online: $device_serial"
[[ "$(adb_with_timeout -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || \
  blocked "process-restart journey requires a controlled emulator"
case "${QUALITY_ORACLE_NEGATIVE_CONTROL:-}" in
  "") ;;
  wrong-body) expected_body="deliberately-wrong-body" ;;
  wrong-url)
    expected_handoff_url="https://pushgo.dev/deliberately-wrong"
    expected_browser_display="pushgo.dev/deliberately-wrong"
    ;;
  *) blocked "unsupported process-restart negative control" ;;
esac

wait_for_control_completion() {
  local operation="$1"
  local deadline=$((SECONDS + 15))
  while (( SECONDS < deadline )); do
    if adb_with_timeout -s "$device_serial" logcat -d -s PushGoQualityControl:I '*:S' \
      | rg -q "quality control completed: $operation"; then
      return 0
    fi
    if adb_with_timeout -s "$device_serial" logcat -d -s PushGoQualityControl:E '*:S' \
      | rg -q 'quality control failed'; then
      return 1
    fi
    sleep 0.25
  done
  return 1
}

cleanup() {
  local status=$?
  local clear_output=""
  trap - EXIT
  if [[ "$external_surface_open" -eq 1 ]]; then
    adb_with_timeout -s "$device_serial" shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  fi
  adb_with_timeout -s "$device_serial" shell am force-stop "$package_name" >/dev/null 2>&1 || true
  if [[ "$prepared" -eq 1 ]]; then
    clear_output="$(adb_with_timeout -s "$device_serial" shell am start -W \
      -n "$package_name/.testing.BenchmarkUnstopActivity" \
      --ez io.ethan.pushgo.testing.CLEAR_SESSION true 2>&1)" || true
    if [[ "$clear_output" != *"Status: ok"* ]] || ! wait_for_control_completion clear; then
      echo "cleanup_status=FAILED"
      echo "cleanup_reason=App-owned process-restart session was not cleared"
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
else
  adb_with_timeout -s "$device_serial" shell pm path "$package_name" | rg -q '^package:' || \
    blocked "QUALITY_ANDROID_SKIP_INSTALL=1 requires an installed debug App"
fi

session_id="android-process-restart-$(date +%s)"
payload="$(python3 - "$session_id" <<'PY'
import base64
import json
import sys

payload = {
    "schema_version": 1,
    "session_id": sys.argv[1],
    "fixture": "messages.standard",
    "faults": {},
}
print(base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode())
PY
)"

adb_with_timeout -s "$device_serial" logcat -c
prepare_output="$(adb_with_timeout -s "$device_serial" shell am start -W \
  -n "$package_name/.testing.BenchmarkUnstopActivity" \
  --es io.ethan.pushgo.testing.SESSION_BASE64 "$payload" 2>&1)" || \
  blocked "App-owned process-restart fixture could not be prepared: $prepare_output"
[[ "$prepare_output" == *"Status: ok"* ]] || \
  blocked "App-owned process-restart control did not launch: $prepare_output"
prepared=1
wait_for_control_completion prepare || \
  blocked "App-owned process-restart fixture did not complete inside the App"

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
  local mode="$1"
  local query="$2"
  python3 - "$ui_dump" "$mode" "$query" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, mode, query = sys.argv[1:]
root = ET.parse(path).getroot()
for node in root.iter("node"):
    resource_id = node.attrib.get("resource-id", "")
    text = node.attrib.get("text", "")
    description = node.attrib.get("content-desc", "")
    if mode == "resource" and not (resource_id == query or resource_id.endswith(":id/" + query)):
        continue
    if mode == "text" and query not in {text, description}:
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if match:
        x1, y1, x2, y2 = map(int, match.groups())
        print(f"{(x1 + x2) // 2},{(y1 + y2) // 2}")
        raise SystemExit(0)
raise SystemExit(1)
PY
}

ui_root_package() {
  python3 - "$ui_dump" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    package_name = node.attrib.get("package", "")
    if package_name:
        print(package_name)
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
        print(node.attrib.get("text", "") or node.attrib.get("content-desc", ""))
        raise SystemExit(0)
raise SystemExit(1)
PY
}

wait_for_node() {
  local mode="$1"
  local query="$2"
  local timeout_seconds="${3:-15}"
  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    if dump_ui && node_center "$mode" "$query" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.25
  done
  return 1
}

wait_for_node_absent() {
  local mode="$1"
  local query="$2"
  local timeout_seconds="${3:-10}"
  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    if dump_ui && ! node_center "$mode" "$query" >/dev/null 2>&1; then
      return 0
    fi
    sleep 0.25
  done
  return 1
}

tap_node() {
  local mode="$1"
  local query="$2"
  wait_for_node "$mode" "$query" 15 || failed "UI node was not reachable: $query"
  # wait_for_node leaves the last successful dump in ui_dump. Re-dumping here
  # races with Compose/system-surface transitions and can hide a reachable node.
  local center
  center="$(node_center "$mode" "$query")" || failed "UI node disappeared before tapping: $query"
  adb_with_timeout -s "$device_serial" shell input tap "${center%,*}" "${center#*,}"
}

adb_with_timeout -s "$device_serial" shell am force-stop "$package_name"
adb_with_timeout -s "$device_serial" shell am start -W -n "$package_name/.MainActivity" >/dev/null
wait_for_node resource "quality-runtime.ready" 20 || failed "first App process did not become ready"
wait_for_node resource "message.row.quality-standard-message" 15 || \
  failed "first App process did not show the canonical message"
wait_for_node resource "action.messages.mark_all_read" 5 || \
  failed "canonical unread state was missing before the user action"
first_pid="$(adb_with_timeout -s "$device_serial" shell pidof "$package_name" | tr -d '\r')"
[[ "$first_pid" =~ ^[0-9]+$ ]] || failed "first App process PID was unavailable"

tap_node resource "message.row.quality-standard-message"
wait_for_node text "$expected_title" 10 || failed "first process did not show the exact canonical title"
wait_for_node text "$expected_body" 10 || failed "first process did not show the exact canonical body"

adb_with_timeout -s "$device_serial" shell am force-stop "$package_name"
deadline=$((SECONDS + 10))
while (( SECONDS < deadline )); do
  [[ -z "$(adb_with_timeout -s "$device_serial" shell pidof "$package_name" | tr -d '\r')" ]] && break
  sleep 0.25
done
[[ -z "$(adb_with_timeout -s "$device_serial" shell pidof "$package_name" | tr -d '\r')" ]] || \
  failed "force-stop did not terminate the first App process"

adb_with_timeout -s "$device_serial" shell am start -W -n "$package_name/.MainActivity" >/dev/null
wait_for_node resource "quality-runtime.ready" 20 || failed "restarted App process did not become ready"
second_pid="$(adb_with_timeout -s "$device_serial" shell pidof "$package_name" | tr -d '\r')"
[[ "$second_pid" =~ ^[0-9]+$ ]] || failed "restarted App process PID was unavailable"
[[ "$second_pid" != "$first_pid" ]] || failed "App restart reused the original process PID"
wait_for_node resource "message.row.quality-standard-message" 15 || \
  failed "restarted App process lost the canonical message"
if wait_for_node resource "action.messages.mark_all_read" 2; then
  failed "restarted App process resurrected the already-read state"
fi
tap_node resource "message.row.quality-standard-message"
wait_for_node text "$expected_title" 10 || failed "restarted process lost the exact canonical title"
wait_for_node text "$expected_body" 10 || failed "restarted process lost the exact canonical body"

resolved_activity="$(adb_with_timeout -s "$device_serial" shell cmd package resolve-activity --brief \
  -a android.intent.action.VIEW \
  -c android.intent.category.BROWSABLE \
  -d "$canonical_url" 2>/dev/null | tr -d '\r' | tail -n 1)"
if [[ "$resolved_activity" != */* ]]; then
  handler_components="$(adb_with_timeout -s "$device_serial" shell cmd package query-activities \
    --brief --components --user current \
    -a android.intent.action.VIEW \
    -c android.intent.category.BROWSABLE \
    -d "$canonical_url" 2>/dev/null | tr -d '\r' | sed '/^$/d')"
  handler_count="$(printf '%s\n' "$handler_components" | wc -l | tr -d ' ')"
  [[ "$handler_count" == "1" && "$handler_components" == */* ]] || \
    blocked "canonical HTTPS handoff has no unique browser handler"
  resolved_activity="$handler_components"
fi
[[ "$resolved_activity" == */* ]] || \
  blocked "no default browser can complete the canonical HTTPS handoff"
browser_package="${resolved_activity%%/*}"
[[ -n "$browser_package" && "$browser_package" != "$package_name" && "$browser_package" != "android" ]] || \
  blocked "canonical HTTPS handoff resolves only to a chooser or PushGo: $resolved_activity"

return_from_browser() {
  local target_tag="$1"
  for _ in 1 2 3; do
    adb_with_timeout -s "$device_serial" shell input keyevent KEYCODE_BACK
    local deadline=$((SECONDS + 3))
    while (( SECONDS < deadline )); do
      if dump_ui && [[ "$(ui_root_package 2>/dev/null || true)" == "$package_name" ]]; then
        wait_for_node resource "$target_tag" 7
        return $?
      fi
      sleep 0.25
    done
  done
  return 1
}

tap_node resource "action.message.open_url"
external_surface_open=1
deadline=$((SECONDS + 10))
focused_package=""
while (( SECONDS < deadline )); do
  if dump_ui; then
    focused_package="$(ui_root_package 2>/dev/null || true)"
  fi
  [[ "$focused_package" == "$browser_package" ]] && break
  sleep 0.25
done
[[ "$focused_package" == "$browser_package" ]] || \
  failed "Open URL did not foreground the resolved browser; expected=$browser_package actual=${focused_package:-missing}"

# A fresh controlled AVD can expose Chrome's one-time sign-in screen before it
# consumes the already-delivered VIEW intent. Dismiss only this exact system
# preparation surface; do not select an account, change the default browser, or
# guess across browser/OEM dialogs.
if [[ "$browser_package" == "com.android.chrome" ]]; then
  deadline=$((SECONDS + 10))
  while (( SECONDS < deadline )); do
    if dump_ui; then
      if node_text url_bar >/dev/null 2>&1; then
        break
      fi
      if node_center resource "signin_fre_dismiss_button" >/dev/null 2>&1; then
        fre_center="$(node_center resource "signin_fre_dismiss_button")"
        adb_with_timeout -s "$device_serial" shell input tap "${fre_center%,*}" "${fre_center#*,}"
      elif node_center resource "notification_permission_rationale_title" >/dev/null 2>&1 && \
        node_center resource "negative_button" >/dev/null 2>&1; then
        rationale_center="$(node_center resource "negative_button")"
        adb_with_timeout -s "$device_serial" shell input tap "${rationale_center%,*}" "${rationale_center#*,}"
      fi
    fi
    sleep 0.25
  done
  if dump_ui && node_center resource "signin_fre_dismiss_button" >/dev/null 2>&1; then
    blocked "Chrome first-run preparation did not complete"
  fi
  if dump_ui && node_center resource "notification_permission_rationale_title" >/dev/null 2>&1; then
    blocked "Chrome notification preparation did not complete"
  fi
fi
if [[ "$browser_package" == "com.android.chrome" ]]; then
  deadline=$((SECONDS + 10))
  actual_browser_display=""
  while (( SECONDS < deadline )); do
    if dump_ui; then
      actual_browser_display="$(node_text url_bar 2>/dev/null || true)"
    fi
    [[ "$actual_browser_display" == "$expected_browser_display" ]] && break
    sleep 0.25
  done
  [[ "$actual_browser_display" == "$expected_browser_display" ]] || \
    failed "Chrome did not expose the exact canonical message URL; actual=${actual_browser_display:-missing}"
else
  top_activity="$(adb_with_timeout -s "$device_serial" shell dumpsys activity top)"
  rg -Fq "dat=$expected_handoff_url" <<<"$top_activity" || \
    failed "system browser did not receive the exact canonical message URL"
fi

return_from_browser "sheet.message.detail" || \
  failed "PushGo did not return to the existing message detail after browser handoff"
wait_for_node text "$expected_body" 5 || \
  failed "browser return lost the exact canonical message detail"
external_surface_open=0

adb_with_timeout -s "$device_serial" shell input keyevent KEYCODE_BACK
wait_for_node_absent resource "sheet.message.detail" 8 || \
  failed "message detail did not close before the documentation journey"
tap_node resource "nav.item.channels"
wait_for_node resource "screen.channels.list" 10 || \
  failed "the restarted App could not reach Channels before opening Settings"
tap_node resource "action.channels.settings"
wait_for_node resource "screen.settings" 10 || \
  failed "the restarted App could not reach Settings before opening documentation"
for _ in 1 2 3 4 5; do
  if wait_for_node resource "row.settings.docs.getting_started" 1; then
    break
  fi
  adb_with_timeout -s "$device_serial" shell input swipe 500 1650 500 550 300
done
wait_for_node resource "row.settings.docs.getting_started" 3 || \
  failed "the visible Getting Started documentation row was not reachable"
tap_node resource "row.settings.docs.getting_started"
external_surface_open=1
deadline=$((SECONDS + 10))
focused_package=""
while (( SECONDS < deadline )); do
  if dump_ui; then
    focused_package="$(ui_root_package 2>/dev/null || true)"
  fi
  [[ "$focused_package" == "$browser_package" ]] && break
  sleep 0.25
done
[[ "$focused_package" == "$browser_package" ]] || \
  failed "documentation action did not foreground the resolved browser; expected=$browser_package actual=${focused_package:-missing}"
if [[ "$browser_package" == "com.android.chrome" ]]; then
  deadline=$((SECONDS + 10))
  actual_documentation_display=""
  while (( SECONDS < deadline )); do
    if dump_ui; then
      actual_documentation_display="$(node_text url_bar 2>/dev/null || true)"
    fi
    [[ "$actual_documentation_display" == "$expected_documentation_display" ]] && break
    sleep 0.25
  done
  [[ "$actual_documentation_display" == "$expected_documentation_display" ]] || \
    failed "Chrome did not expose the exact Getting Started URL; actual=${actual_documentation_display:-missing}"
else
  top_activity="$(adb_with_timeout -s "$device_serial" shell dumpsys activity top)"
  rg -Fq "dat=$documentation_url" <<<"$top_activity" || \
    failed "system browser did not receive the exact Getting Started URL"
fi
return_from_browser "screen.settings" || \
  failed "PushGo did not return to the same Settings journey after documentation handoff"
external_surface_open=0

echo "status=PASSED"
echo "claim=unread canonical message -> real detail/read mutation -> no PID -> new process -> exact persisted detail/read state -> exact message HTTPS browser handoff/return -> visible Settings documentation row -> exact localized-safe HTTPS browser handoff -> same Settings return"
echo "first_pid=$first_pid"
echo "second_pid=$second_pid"
