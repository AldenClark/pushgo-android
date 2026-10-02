#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
device_serial="${ANDROID_SERIAL:-}"
startup_budget_ms=2000
injected_load_delay_ms=3500
results_root="${QUALITY_RESULTS_ROOT:-$repo_root/build/quality-results}"
result_file="$results_root/android-performance-slow-load-negative-control.json"
run_dir="$(mktemp -d "${TMPDIR:-/tmp}/pushgo-performance-negative.XXXXXX")"
gradle_log="$run_dir/gradle.log"
adb_timeout_seconds="${QUALITY_ADB_TIMEOUT_SECONDS:-8}"
adb_binary="$(command -v adb || true)"

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

cleanup() {
  rm -rf "$run_dir"
}
trap cleanup EXIT

blocked() {
  printf 'status=BLOCKED\nreason=%s\n' "$1" >&2
  exit 2
}

failed_test_system() {
  printf 'status=FAILED_TEST_SYSTEM\nreason=%s\n' "$1" >&2
  exit 4
}

[[ -n "$device_serial" ]] || blocked "ANDROID_SERIAL is required; no performance device is selected implicitly"
[[ -n "$adb_binary" ]] || blocked "adb is unavailable"
[[ "$adb_timeout_seconds" =~ ^[1-9][0-9]*$ ]] || blocked "QUALITY_ADB_TIMEOUT_SECONDS must be a positive integer"
[[ "$(adb_with_timeout -s "$device_serial" get-state 2>/dev/null || true)" == "device" ]] || \
  blocked "requested performance device is not connected and online: $device_serial"
[[ "$device_serial" == emulator-* ]] || blocked "slow-load negative control requires a controlled emulator"
[[ "$(adb_with_timeout -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || \
  blocked "slow-load negative control requires a qemu target"

set +e
ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" \
  :macrobenchmark:connectedBenchmarkBenchmarkAndroidTest \
  --console=plain \
  "-Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.macrobenchmark.PushGoMacrobenchmark#coldStartupReachesAccurateLargeStoreContent" \
  "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true" \
  "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR" \
  "-Pandroid.testInstrumentationRunnerArguments.pushgo.maxStartupMs=$startup_budget_ms" \
  "-Pandroid.testInstrumentationRunnerArguments.pushgo.maxDetailMs=10000" \
  "-Pandroid.testInstrumentationRunnerArguments.pushgo.fixtureLoadDelayMs=$injected_load_delay_ms" \
  >"$gradle_log" 2>&1
gradle_status=$?
set -e

if (( gradle_status == 0 )); then
  failed_test_system "Macrobenchmark accepted a deliberately over-budget 1k message load"
fi

if rg -q 'ERRORS \(not suppressed\)|UiAutomation not connected|Observed no .* slices|benchmarkData.json' "$gradle_log"; then
  blocked "Macrobenchmark infrastructure failed before the slow-load product interval could be judged"
fi

failure_line="$(rg -o 'cold startup-to-accurate-content took [0-9]+ms; budget=2000ms' "$gradle_log" | head -n 1 || true)"
[[ -n "$failure_line" ]] || {
  tail -n 80 "$gradle_log" >&2
  failed_test_system "slow-load run failed without the exact startup-to-accurate-content budget oracle"
}

mkdir -p "$(dirname "$result_file")"
python3 - "$result_file" "$device_serial" "$injected_load_delay_ms" "$startup_budget_ms" "$failure_line" <<'PY'
import json
import re
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

output, serial, delay, budget, failure = sys.argv[1:]
match = re.fullmatch(r"cold startup-to-accurate-content took (\d+)ms; budget=(\d+)ms", failure)
if match is None:
    raise SystemExit("unexpected slow-load failure format")
payload = {
    "schema_version": 1,
    "recorded_at": datetime.now(timezone.utc).isoformat(),
    "platform": "android",
    "environment": {"kind": "controlled-emulator", "serial": serial},
    "workload": {"fixture": "messages.large", "canonical_rows": 1000},
    "interval": "cold process start -> exact canonical title visible",
    "injected_message_load_delay_ms": int(delay),
    "budget_ms": int(budget),
    "observed_ms": int(match.group(1)),
    "product_status": "NOT_RUN",
    "test_system_status": "PASSED",
    "status_reason": "the deliberately over-budget load was rejected only after exact content became visible",
}
target = Path(output)
with tempfile.NamedTemporaryFile("w", dir=target.parent, delete=False, encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
    temporary = Path(handle.name)
temporary.replace(target)
PY

printf 'status=PASSED\n'
printf 'claim=slow 1k canonical load trips startup-to-accurate-content budget\n'
printf 'evidence=%s\n' "$result_file"
