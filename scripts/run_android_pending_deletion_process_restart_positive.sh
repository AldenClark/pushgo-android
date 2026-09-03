#!/usr/bin/env bash
set -euo pipefail

# Host-driven, App-owned process boundary coverage for pending local deletion.
# Kept separate from the message read/browser process journey so each failure
# has one state machine and one purpose.

script_dir="$(cd "$(dirname "$BASH_SOURCE")" && pwd)"
repo_root="$(cd "$script_dir/.." && pwd)"
device_serial="${ANDROID_SERIAL:-}"
if [[ -z "$device_serial" && "$#" -gt 0 ]]; then device_serial="$1"; fi
package_name="io.ethan.pushgo"
target_row="message.row.quality-cleanup-old"
control_row="message.row.quality-cleanup-recent"
target_title="Quality Old Cleanup Target"
control_title="Quality Recent Cleanup Control"
canonical_body="Deterministic cleanup boundary message."
tmp_root="${TMPDIR:-}"
if [[ -z "$tmp_root" ]]; then tmp_root=/tmp; fi
run_dir="$(mktemp -d "$tmp_root/pushgo-pending-delete.XXXXXX")"
run_id="$(date -u +%Y%m%d-%H%M%S)-$$"
ui_dump="$run_dir/window.xml"
device_ui_dump="/data/local/tmp/pushgo-pending-delete-$run_id.xml"
results_root="${QUALITY_RESULTS_ROOT:-}"
if [[ -z "$results_root" ]]; then results_root="$repo_root/build/quality-results/android-pending-deletion-process-restart"; fi
failure_evidence_dir="$results_root/failure-$run_id"
cases_file="$results_root/cases.jsonl"
summary_file="$results_root/android-pending-deletion-process-summary.json"
adb_timeout_seconds="${QUALITY_ADB_TIMEOUT_SECONDS:-}"
if [[ -z "$adb_timeout_seconds" ]]; then adb_timeout_seconds=8; fi
lock_timeout_seconds="${QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS:-}"
if [[ -z "$lock_timeout_seconds" ]]; then lock_timeout_seconds=15; fi
adb_binary="$(command -v adb || true)"
device_lock_root="${QUALITY_ANDROID_LOCK_ROOT:-}"
if [[ -z "$device_lock_root" ]]; then device_lock_root="$tmp_root/pushgo-android-quality-locks"; fi
device_lock_dir=""
device_lock_acquired=0
failure_evidence_saved=0
last_dump_failure=""
prepared=0
current_case=""
current_session_id=""

mkdir -p "$results_root"

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
  [[ "$device_lock_acquired" -eq 1 && -n "$device_lock_dir" ]] || return 0
  [[ -f "$device_lock_dir/pid" ]] && owner_pid="$(<"$device_lock_dir/pid")"
  if [[ "$owner_pid" == "$$" ]]; then
    rm -f "$device_lock_dir/pid"
    rmdir "$device_lock_dir" 2>/dev/null || true
  fi
  device_lock_dir=""
  device_lock_acquired=0
}

now_ms() {
  python3 -c 'import time; print(int(time.time() * 1000))'
}

app_pid() {
  adb_with_timeout -s "$device_serial" shell pidof "$package_name" |
    tr -d '\r' | awk 'NF { print $1; exit }'
}

capture_failure_evidence() {
  local reason="$1"
  [[ "$failure_evidence_saved" -eq 1 ]] && return 0
  failure_evidence_saved=1
  mkdir -p "$failure_evidence_dir" || return 1
  printf 'reason=%s\ncase=%s\nsession_id=%s\nserial=%s\nrun_id=%s\n' \
    "$reason" "$current_case" "$current_session_id" "$device_serial" "$run_id" \
    >"$failure_evidence_dir/metadata.txt"
  app_pid >"$failure_evidence_dir/app-pid.txt" 2>&1 || true
  adb_with_timeout -s "$device_serial" shell dumpsys window windows \
    >"$failure_evidence_dir/window-focus.txt" 2>&1 || true
  adb_with_timeout -s "$device_serial" logcat -b crash -d \
    >"$failure_evidence_dir/crash-buffer.txt" 2>&1 || true
  if declare -F dump_ui >/dev/null && dump_ui; then
    cp "$ui_dump" "$failure_evidence_dir/window.xml"
  else
    printf '%s\n' "$last_dump_failure" >"$failure_evidence_dir/ui-dump-failure.txt"
  fi
}

failed() {
  echo "status=FAILED"
  echo "case=$current_case"
  echo "reason=$1"
  capture_failure_evidence "$1" || true
  exit 1
}

wait_for_control_completion() {
  local operation="$1"
  local deadline=$((SECONDS + 15))
  while (( SECONDS < deadline )); do
    if adb_with_timeout -s "$device_serial" logcat -d -s PushGoQualityControl:I '*:S' |
      rg -q "quality control completed: $operation"; then
      return 0
    fi
    if adb_with_timeout -s "$device_serial" logcat -d -s PushGoQualityControl:E '*:S' |
      rg -q 'quality control failed'; then
      return 1
    fi
    sleep 0.25
  done
  return 1
}

dump_ui() {
  local dump_output=""
  rm -f "$ui_dump"
  if ! dump_output="$(adb_with_timeout -s "$device_serial" shell uiautomator dump "$device_ui_dump" 2>&1)"; then
    last_dump_failure="uiautomator dump failed: $(printf '%s' "$dump_output" | tr '\n' ' ')"
    return 1
  fi
  if ! adb_with_timeout -s "$device_serial" exec-out cat "$device_ui_dump" \
    >"$ui_dump" 2>"$run_dir/ui-copy.stderr"; then
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

wait_for_node() {
  local mode="$1"
  local query="$2"
  local timeout_seconds="$3"
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
  local timeout_seconds="$3"
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
  local center center_x center_y
  center="$(node_center "$mode" "$query")" ||
    failed "UI node disappeared before tapping: $query"
  center_x="$(printf '%s' "$center" | cut -d, -f1)"
  center_y="$(printf '%s' "$center" | cut -d, -f2)"
  adb_with_timeout -s "$device_serial" shell input tap "$center_x" "$center_y"
}

wait_for_process_gone() {
  local deadline=$((SECONDS + 10))
  while (( SECONDS < deadline )); do
    [[ -z "$(app_pid)" ]] && return 0
    sleep 0.25
  done
  return 1
}

force_stop_app() {
  adb_with_timeout -s "$device_serial" shell am force-stop "$package_name" ||
    failed "force-stop failed for the App process"
  wait_for_process_gone || failed "force-stop did not terminate the App process"
}

start_app_and_ready() {
  adb_with_timeout -s "$device_serial" shell am start -W -n "$package_name/.MainActivity" \
    >/dev/null || failed "MainActivity could not start"
  wait_for_node resource "quality-runtime.ready" 20 ||
    failed "App-owned quality runtime did not become ready"
  wait_for_node resource "screen.messages.list" 15 ||
    failed "Messages screen did not become ready"
}

prepare_case() {
  current_case="$1"
  local window_ms="$2"
  current_session_id="android-pending-delete-$current_case-$(date +%s)-$$"
  local payload
  payload="$(python3 - "$current_session_id" "$window_ms" <<'PY'
import base64
import json
import sys

payload = {
    "schema_version": 1,
    "session_id": sys.argv[1],
    "fixture": "messages.cleanup",
    "pending_deletion_undo_window_ms": int(sys.argv[2]),
    "faults": {},
}
print(base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode())
PY
)"
  adb_with_timeout -s "$device_serial" logcat -c
  force_stop_app
  local prepare_output
  prepare_output="$(adb_with_timeout -s "$device_serial" shell am start -W \
    -n "$package_name/.testing.BenchmarkUnstopActivity" \
    --es io.ethan.pushgo.testing.SESSION_BASE64 "$payload" 2>&1)" ||
    blocked "App-owned pending-deletion fixture could not be prepared: $prepare_output"
  [[ "$prepare_output" == *"Status: ok"* ]] ||
    blocked "App-owned pending-deletion control did not launch: $prepare_output"
  prepared=1
  wait_for_control_completion prepare ||
    blocked "App-owned pending-deletion fixture did not complete inside the App"
}

clear_current_session() {
  [[ "$prepared" -eq 1 ]] || return 0
  local clear_output
  clear_output="$(adb_with_timeout -s "$device_serial" shell am start -W \
    -n "$package_name/.testing.BenchmarkUnstopActivity" \
    --ez io.ethan.pushgo.testing.CLEAR_SESSION true 2>&1)" || true
  if [[ "$clear_output" != *"Status: ok"* ]] || ! wait_for_control_completion clear; then
    blocked "App-owned pending-deletion session was not cleared"
  fi
  prepared=0
  current_session_id=""
}

record_case() {
  local case_name="$1"
  local session_id="$2"
  local window_ms="$3"
  local first_pid="$4"
  local second_pid="$5"
  python3 - "$cases_file" "$case_name" "$session_id" "$window_ms" "$first_pid" "$second_pid" <<'PY'
import json
import pathlib
import sys

path = pathlib.Path(sys.argv[1])
path.parent.mkdir(parents=True, exist_ok=True)
record = {
    "case": sys.argv[2],
    "session_id": sys.argv[3],
    "undo_window_ms": int(sys.argv[4]),
    "first_pid": int(sys.argv[5]),
    "second_pid": int(sys.argv[6]),
    "status": "PASSED",
}
with path.open("a", encoding="utf-8") as handle:
    handle.write(json.dumps(record, sort_keys=True) + "\n")
PY
  echo "case=$case_name status=PASSED session_id=$session_id first_pid=$first_pid second_pid=$second_pid"
}

run_undo_case() {
  local window_ms=60000
  prepare_case undo "$window_ms"
  start_app_and_ready
  wait_for_node resource "$target_row" 15 || failed "undo case target message was not shown"
  wait_for_node resource "$control_row" 15 || failed "undo case control message was not shown"
  local first_pid
  first_pid="$(app_pid)"
  [[ "$first_pid" =~ ^[0-9]+$ ]] || failed "undo case first process PID was unavailable"

  tap_node resource "$target_row"
  wait_for_node text "$target_title" 10 || failed "undo case target title was not shown in detail"
  wait_for_node text "$canonical_body" 10 || failed "undo case target body was not shown in detail"
  tap_node resource "action.message.delete"
  wait_for_node resource "state.pending_deletion" 10 || failed "undo case pending bar was not shown"
  wait_for_node_absent resource "$target_row" 5 || failed "undo case target remained visible after delete"
  wait_for_node resource "$control_row" 5 || failed "undo case control disappeared after delete"

  force_stop_app
  adb_with_timeout -s "$device_serial" shell am start -W -n "$package_name/.MainActivity" \
    >/dev/null || failed "undo case restarted MainActivity could not start"
  wait_for_node resource "quality-runtime.ready" 20 || failed "undo case restarted process was not ready"
  local second_pid
  second_pid="$(app_pid)"
  [[ "$second_pid" =~ ^[0-9]+$ ]] || failed "undo case restarted process PID was unavailable"
  [[ "$second_pid" != "$first_pid" ]] || failed "undo case reused the original process PID"
  wait_for_node resource "$control_row" 15 || failed "undo case restarted process lost control message"
  wait_for_node resource "state.pending_deletion" 15 || failed "undo case pending deletion was not visible in the new process"
  wait_for_node_absent resource "$target_row" 5 || failed "undo case target reappeared before Undo"
  tap_node resource "action.pending_deletion.undo"
  wait_for_node_absent resource "state.pending_deletion" 10 || failed "undo case pending bar remained after Undo"
  wait_for_node resource "$target_row" 15 || failed "undo case target was not restored after Undo"

  tap_node resource "$target_row"
  wait_for_node text "$target_title" 10 || failed "undo case restored target title was inaccurate"
  wait_for_node text "$canonical_body" 10 || failed "undo case restored target body was inaccurate"
  adb_with_timeout -s "$device_serial" shell input keyevent KEYCODE_BACK
  wait_for_node_absent resource "sheet.message.detail" 8 || failed "undo case target detail did not close"
  tap_node resource "$control_row"
  wait_for_node text "$control_title" 10 || failed "undo case control title was inaccurate"
  wait_for_node text "$canonical_body" 10 || failed "undo case control body was inaccurate"
  record_case undo "$current_session_id" "$window_ms" "$first_pid" "$second_pid"
  clear_current_session
}

run_deadline_case() {
  local window_ms=8000
  prepare_case deadline "$window_ms"
  start_app_and_ready
  wait_for_node resource "$target_row" 15 || failed "deadline case target message was not shown"
  wait_for_node resource "$control_row" 15 || failed "deadline case control message was not shown"
  local first_pid
  first_pid="$(app_pid)"
  [[ "$first_pid" =~ ^[0-9]+$ ]] || failed "deadline case first process PID was unavailable"

  tap_node resource "$target_row"
  wait_for_node text "$target_title" 10 || failed "deadline case target title was not shown in detail"
  wait_for_node text "$canonical_body" 10 || failed "deadline case target body was not shown in detail"
  tap_node resource "action.message.delete"
  wait_for_node resource "state.pending_deletion" 10 || failed "deadline case pending bar was not shown"
  local pending_seen_at force_stop_at
  pending_seen_at="$(now_ms)"
  wait_for_node_absent resource "$target_row" 5 || failed "deadline case target remained visible after delete"
  wait_for_node resource "$control_row" 5 || failed "deadline case control disappeared after delete"
  force_stop_app
  force_stop_at="$(now_ms)"
  local time_before_deadline=$((force_stop_at - pending_seen_at))
  (( time_before_deadline < window_ms )) ||
    blocked "deadline case could not terminate before its quality undo window"
  local terminal_at=$((pending_seen_at + window_ms + 1500))
  while (( $(now_ms) < terminal_at )); do
    sleep 0.25
  done
  [[ -z "$(app_pid)" ]] || failed "deadline case App process returned before restart"

  adb_with_timeout -s "$device_serial" shell am start -W -n "$package_name/.MainActivity" \
    >/dev/null || failed "deadline case restarted MainActivity could not start"
  wait_for_node resource "quality-runtime.ready" 20 || failed "deadline case restarted process was not ready"
  local second_pid
  second_pid="$(app_pid)"
  [[ "$second_pid" =~ ^[0-9]+$ ]] || failed "deadline case restarted process PID was unavailable"
  [[ "$second_pid" != "$first_pid" ]] || failed "deadline case reused the original process PID"
  wait_for_node resource "$control_row" 15 || failed "deadline case restarted process lost control message"
  wait_for_node_absent resource "state.pending_deletion" 15 || failed "deadline case pending bar remained after deadline"
  wait_for_node_absent resource "$target_row" 10 || failed "deadline case target returned after deadline commit"
  tap_node resource "$control_row"
  wait_for_node text "$control_title" 10 || failed "deadline case control title was inaccurate"
  wait_for_node text "$canonical_body" 10 || failed "deadline case control body was inaccurate"
  record_case deadline "$current_session_id" "$window_ms" "$first_pid" "$second_pid"
  clear_current_session
}

cleanup() {
  local status=$?
  trap - EXIT
  if [[ -n "$device_serial" && -n "$adb_binary" ]]; then
    adb_with_timeout -s "$device_serial" shell am force-stop "$package_name" >/dev/null 2>&1 || true
    if [[ "$prepared" -eq 1 ]]; then
      clear_current_session || true
    fi
    adb_with_timeout -s "$device_serial" shell rm -f "$device_ui_dump" >/dev/null 2>&1 || true
  fi
  release_device_lock
  rm -rf "$run_dir"
  exit "$status"
}

[[ -n "$adb_binary" ]] || blocked "adb is unavailable"
command -v python3 >/dev/null 2>&1 || blocked "python3 is unavailable"
[[ "$adb_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || blocked \
  "QUALITY_ADB_TIMEOUT_SECONDS must be a positive integer"
[[ "$lock_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || blocked \
  "QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS must be a positive integer"
[[ -n "$device_serial" ]] || blocked "ANDROID_SERIAL is required"
[[ "$(adb_with_timeout -s "$device_serial" get-state 2>/dev/null || true)" == "device" ]] ||
  blocked "selected Android target is not online: $device_serial"
[[ "$(adb_with_timeout -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] ||
  blocked "pending-deletion process-restart journey requires a controlled emulator"

trap cleanup EXIT
acquire_device_lock
if [[ "${QUALITY_ANDROID_SKIP_INSTALL:-0}" != "1" ]]; then
  "$repo_root/gradlew" :app:installDebug --console=plain
else
  adb_with_timeout -s "$device_serial" shell pm path "$package_name" | rg -q '^package:' ||
    blocked "QUALITY_ANDROID_SKIP_INSTALL=1 requires an installed debug App"
fi

run_undo_case
run_deadline_case

python3 - "$summary_file" "$cases_file" "$device_serial" "$run_id" "$repo_root" <<'PY'
import json
import pathlib
import subprocess
import sys

summary_path = pathlib.Path(sys.argv[1])
cases_path = pathlib.Path(sys.argv[2])
repo_root = pathlib.Path(sys.argv[5])
try:
    revision = subprocess.check_output(
        ["git", "-C", str(repo_root), "rev-parse", "HEAD"],
        text=True,
    ).strip()
    dirty = bool(
        subprocess.check_output(
            ["git", "-C", str(repo_root), "status", "--porcelain"],
            text=True,
        ).strip()
    )
except Exception:
    revision = "unknown"
    dirty = True
cases = [
    json.loads(line)
    for line in cases_path.read_text(encoding="utf-8").splitlines()
    if line.strip()
]
summary = {
    "schema_version": 1,
    "status": "PASSED",
    "run_id": sys.argv[4],
    "device_serial": sys.argv[3],
    "source_revision": revision,
    "source_dirty": dirty,
    "cases": cases,
    "oracle": {
        "undo": "new PID observes pending bar and hidden target; Undo restores exact target while control remains readable",
        "deadline": "new PID observes no pending bar and permanently absent target while control remains readable",
    },
}
summary_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

echo "status=PASSED"
echo "claim=two independent App-owned pending-deletion process boundaries: new-PID Undo restoration and new-PID post-deadline commit with exact control-message preservation"
echo "summary=$summary_file"
