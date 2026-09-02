#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
quality_results_root="${QUALITY_RESULTS_ROOT:-$repo_root/build/quality-results}"
results_root="${RESULTS_ROOT:-$quality_results_root/android-preparation-contract}"
package_name="${APP_ID:-io.ethan.pushgo}"
mkdir -p "$results_root"
run_id="$(date +%Y%m%d-%H%M%S)"
run_dir="$results_root/$run_id"
mkdir -p "$run_dir"
summary="$run_dir/summary.json"
prepared=0
device_serial=""
adb_timeout_seconds="${QUALITY_ADB_TIMEOUT_SECONDS:-15}"
lock_timeout_seconds="${QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS:-15}"
adb_binary="$(command -v adb || true)"
device_lock_root="${QUALITY_ANDROID_LOCK_ROOT:-${TMPDIR:-/tmp}/pushgo-android-quality-locks}"
device_lock_dir=""
device_lock_acquired=0

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
  mkdir -p "$device_lock_root" || {
    device_lock_dir=""
    echo "status=FAILED_TEST_SYSTEM"
    echo "reason=android_device_lock_root_unavailable:$device_lock_root"
    return 3
  }
  [[ "$lock_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || {
    device_lock_dir=""
    echo "status=BLOCKED"
    echo "reason=QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS must be a positive integer"
    return 2
  }
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
      echo "status=BLOCKED"
      echo "reason=selected Android device is busy: $device_serial"
      return 2
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

cleanup() {
  if [[ -n "$device_serial" && $prepared -eq 1 ]]; then
    adb_with_timeout -s "$device_serial" shell content call \
      --uri "content://$package_name.quality-fixture" \
      --method clear >/dev/null 2>&1 || true
    adb_with_timeout -s "$device_serial" shell am force-stop "$package_name" >/dev/null 2>&1 || true
  fi
  release_device_lock
}
trap cleanup EXIT

[[ -n "$adb_binary" ]] || blocked "adb is unavailable"
[[ "$adb_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || blocked "QUALITY_ADB_TIMEOUT_SECONDS must be a positive integer"

doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
printf '%s\n' "$doctor_output"
device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
if [[ -z "$device_serial" ]]; then
  echo "status=BLOCKED"
  echo "reason=quality_doctor_missing_device_serial"
  exit 2
fi

acquire_device_lock || exit "$?"

if [[ "${QUALITY_ANDROID_SKIP_INSTALL:-0}" != "1" ]]; then
  "$repo_root/gradlew" assembleDebug
  apk="$repo_root/app/build/outputs/apk/debug/app-universal-debug.apk"
  [[ -f "$apk" ]] || {
    echo "status=BLOCKED"
    echo "reason=current_debug_apk_missing"
    exit 2
  }
  adb_with_timeout -s "$device_serial" install -r "$apk" >/dev/null
fi

invalid_payload="$(printf '%s' '{"schema_version":999,"session_id":"invalid-preparation","fixture":"empty.clean"}' | base64 | tr -d '\n')"
invalid_log="$run_dir/invalid-session.log"
started_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
set +e
adb_with_timeout -s "$device_serial" shell content call \
  --uri "content://$package_name.quality-fixture" \
  --method prepare \
  --arg "$invalid_payload" >"$invalid_log" 2>&1
set -e
finished_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
invalid_elapsed_ms=$(((finished_ns - started_ns) / 1000000))

if ! rg -q 'QUALITY_PRECONDITION phase=session.decode' "$invalid_log" \
  || rg -q 'status=ready' "$invalid_log"; then
  echo "status=FAILED_TEST_SYSTEM"
  echo "reason=invalid_session_was_not_exclusively_classified"
  echo "log=$invalid_log"
  exit 3
fi
if (( invalid_elapsed_ms >= 10000 )); then
  echo "status=FAILED_TEST_SYSTEM"
  echo "reason=invalid_session_classification_exceeded_10_seconds:$invalid_elapsed_ms"
  echo "log=$invalid_log"
  exit 3
fi

storage_failure_payload="$(python3 - "$run_id" <<'PY'
import base64
import json
import sys

payload = {
    "schema_version": 1,
    "session_id": f"android-storage-failure-{sys.argv[1]}",
    "fixture": "empty.clean",
    "faults": {"fail_local_store_initialization": True},
}
print(base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode())
PY
)"
storage_failure_log="$run_dir/storage-open-failure.log"
started_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
set +e
adb_with_timeout -s "$device_serial" shell content call \
  --uri "content://$package_name.quality-fixture" \
  --method prepare \
  --arg "$storage_failure_payload" >"$storage_failure_log" 2>&1
set -e
finished_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
storage_failure_elapsed_ms=$(((finished_ns - started_ns) / 1000000))
if ! rg -q 'QUALITY_PRECONDITION phase=storage.open' "$storage_failure_log" \
  || rg -q 'status=ready' "$storage_failure_log"; then
  echo "status=FAILED_TEST_SYSTEM"
  echo "reason=storage_open_failure_was_not_exclusively_classified"
  echo "log=$storage_failure_log"
  exit 3
fi
if (( storage_failure_elapsed_ms >= 10000 )); then
  echo "status=FAILED_TEST_SYSTEM"
  echo "reason=storage_open_classification_exceeded_10_seconds:$storage_failure_elapsed_ms"
  echo "log=$storage_failure_log"
  exit 3
fi

session_id="android-preparation-$run_id"
valid_payload="$(python3 - "$session_id" <<'PY'
import base64
import json
import sys

payload = {
    "schema_version": 1,
    "session_id": sys.argv[1],
    "fixture": "empty.clean",
    "faults": {},
}
print(base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode())
PY
)"
positive_log="$run_dir/positive-session.log"
started_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
adb_with_timeout -s "$device_serial" shell content call \
  --uri "content://$package_name.quality-fixture" \
  --method prepare \
  --arg "$valid_payload" >"$positive_log" 2>&1
finished_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
positive_elapsed_ms=$(((finished_ns - started_ns) / 1000000))
rg -q 'status=ready' "$positive_log" || {
  echo "status=BLOCKED"
  echo "reason=valid_app_owned_session_did_not_report_ready"
  echo "log=$positive_log"
  exit 2
}
prepared=1

adb_with_timeout -s "$device_serial" shell am force-stop "$package_name" >/dev/null
adb_with_timeout -s "$device_serial" shell am start -W -n "$package_name/.MainActivity" >/dev/null
ui_dump="$run_dir/window.xml"
deadline=$((SECONDS + 15))
functional_ready=0
while (( SECONDS < deadline )); do
  if adb_with_timeout -s "$device_serial" shell uiautomator dump /data/local/tmp/pushgo-preparation.xml >/dev/null 2>&1 \
    && adb_with_timeout -s "$device_serial" exec-out cat /data/local/tmp/pushgo-preparation.xml >"$ui_dump" 2>/dev/null \
    && rg -q 'resource-id="quality-runtime.ready"' "$ui_dump" \
    && rg -q 'resource-id="state.messages.empty"' "$ui_dump"; then
    functional_ready=1
    break
  fi
  sleep 0.25
done
if [[ $functional_ready -ne 1 ]]; then
  echo "status=FAILED"
  echo "reason=valid_session_did_not_reach_the_accurate_functional_empty_state"
  echo "ui_dump=$ui_dump"
  exit 1
fi

python3 - "$summary" "$device_serial" "$invalid_elapsed_ms" "$storage_failure_elapsed_ms" "$positive_elapsed_ms" <<'PY'
import json
import os
import sys
import tempfile
from datetime import datetime, timezone

path, serial, invalid_ms, storage_failure_ms, positive_ms = sys.argv[1:]
payload = {
    "schema_version": 1,
    "platform": "android",
    "lane": "preparation-contract",
    "created_at": datetime.now(timezone.utc).isoformat(),
    "device_serial": serial,
    "product_status": "PASSED",
    "test_system_status": "PASSED",
    "invalid_session": {
        "status": "EXPECTED_BLOCKED",
        "phase": "session.decode",
        "elapsed_ms": int(invalid_ms),
        "retry_count": 0,
        "business_ui_started": False,
    },
    "storage_open_failure": {
        "status": "EXPECTED_BLOCKED",
        "phase": "storage.open",
        "elapsed_ms": int(storage_failure_ms),
        "retry_count": 0,
        "rollback_followed_by_positive_control": True,
    },
    "positive_control": {
        "status": "PASSED",
        "fixture": "empty.clean",
        "preparation_elapsed_ms": int(positive_ms),
        "oracle": "quality-runtime.ready plus accurate functional empty state",
    },
    "limitations": ["emulator/device evidence does not prove physical-device startup latency"],
}
directory = os.path.dirname(path)
fd, temporary = tempfile.mkstemp(prefix=".preparation-", suffix=".json", dir=directory)
try:
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    os.replace(temporary, path)
finally:
    if os.path.exists(temporary):
        os.unlink(temporary)
PY

echo "status=PASSED"
echo "summary=$summary"
