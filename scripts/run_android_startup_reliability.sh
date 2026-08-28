#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
iterations="${ITERATIONS:-50}"
results_root="${RESULTS_ROOT:-$repo_root/build/quality-results/android-startup-reliability}"
test_class="${TEST_CLASS:-io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest}"
test_method="${TEST_METHOD:-emptyFixtureShowsTheFunctionalEmptyStateInAnAppOwnedDatabase}"
app_id="${APP_ID:-io.ethan.pushgo}"
test_package="${TEST_PACKAGE:-io.ethan.pushgo.test}"
instrumentation="${TEST_INSTRUMENTATION_COMPONENT:-$test_package/.PushGoAndroidJUnitRunner}"

if [[ ! "$iterations" =~ ^[0-9]+$ ]] || (( iterations < 1 || iterations > 100 )); then
  echo "status=BLOCKED"
  echo "reason=iterations_must_be_between_1_and_100:$iterations"
  exit 2
fi

python3 "$repo_root/scripts/quality_test_system_issues.py" --check \
  --require-id android-compose-snapshot-observer-runtime

doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
printf '%s\n' "$doctor_output"
device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
if [[ -z "$device_serial" ]]; then
  echo "status=BLOCKED"
  echo "reason=quality_doctor_missing_device_serial"
  exit 2
fi

campaign_id="$(date +%Y%m%d-%H%M%S)"
campaign_root="$results_root/$campaign_id"
mkdir -p "$campaign_root/logs"
rows="$campaign_root/iterations.tsv"
printf 'iteration\tstatus\tissue_ids\telapsed_ms\tlog\n' > "$rows"

echo "==> build and install the current app/test APKs once"
"$repo_root/gradlew" assembleDebug assembleDebugAndroidTest
app_apk="$repo_root/app/build/outputs/apk/debug/app-universal-debug.apk"
test_apk="$repo_root/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
if [[ ! -f "$app_apk" || ! -f "$test_apk" ]]; then
  echo "status=BLOCKED"
  echo "reason=current_debug_or_test_apk_missing"
  exit 2
fi
adb -s "$device_serial" install -r "$app_apk"
adb -s "$device_serial" install -r "$test_apk"
if ! adb -s "$device_serial" shell pm list instrumentation | grep -Fq "instrumentation:$instrumentation (target=$app_id)"; then
  echo "status=BLOCKED"
  echo "reason=installed_instrumentation_contract_missing:$instrumentation"
  exit 2
fi

for ((iteration = 1; iteration <= iterations; iteration++)); do
  log_file="$campaign_root/logs/iteration-${iteration}.log"
  adb -s "$device_serial" shell am force-stop "$app_id" >/dev/null
  started_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
  set +e
  adb -s "$device_serial" shell am instrument -w -r \
    -e class "$test_class#$test_method" \
    "$instrumentation" >"$log_file" 2>&1
  command_status=$?
  set -e
  finished_ns="$(python3 -c 'import time; print(time.monotonic_ns())')"
  elapsed_ms=$(((finished_ns - started_ns) / 1000000))
  row_status="PASSED"
  issue_ids=""
  if [[ $command_status -ne 0 ]] \
    || ! grep -Eq '^OK \(1 test\)$' "$log_file" \
    || grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$log_file"; then
    classification="$(python3 "$repo_root/scripts/classify_android_instrument_log.py" --log "$log_file")"
    row_status="$(printf '%s\n' "$classification" | sed -n 's/^classification_status=//p')"
    issue_ids="$(printf '%s\n' "$classification" | sed -n 's/^classification_issue_ids=//p')"
  fi
  printf '%s\t%s\t%s\t%s\t%s\n' \
    "$iteration" "$row_status" "$issue_ids" "$elapsed_ms" "$log_file" >> "$rows"
  echo "iteration=$iteration/$iterations status=$row_status elapsed_ms=$elapsed_ms${issue_ids:+ issue_ids=$issue_ids}"
done

report="$campaign_root/summary.json"
python3 - "$rows" "$report" "$iterations" "$device_serial" "$test_class#$test_method" <<'PY'
import csv, json, math, os, statistics, sys, tempfile
from datetime import datetime, timezone
from pathlib import Path

rows_path, report_path, requested, device_serial, test_scope = sys.argv[1:]
with open(rows_path, encoding="utf-8", newline="") as handle:
    rows = list(csv.DictReader(handle, delimiter="\t"))
counts = {}
for row in rows:
    counts[row["status"]] = counts.get(row["status"], 0) + 1
total = int(requested)
passed = counts.get("PASSED", 0)
minimum = math.ceil(total * 0.98)
durations = [int(row["elapsed_ms"]) for row in rows]
def percentile(values, ratio):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * ratio) - 1)] if ordered else None
product_failures = counts.get("PRODUCT_FAILED", 0)
blocked = counts.get("TEST_SYSTEM_BLOCKED", 0)
flaky = counts.get("TEST_SYSTEM_FLAKY", 0)
product_status = "FAILED" if product_failures else ("PASSED" if passed + flaky >= minimum else "NOT_RUN")
if blocked:
    test_system_status = "BLOCKED"
elif flaky:
    test_system_status = "FLAKY" if passed + flaky >= minimum else "FAILED"
else:
    test_system_status = "PASSED"
issue_ids = sorted({item for row in rows for item in row["issue_ids"].split(",") if item})
payload = {
    "schema_version": 1,
    "platform": "android-controlled-emulator",
    "generated_at": datetime.now(timezone.utc).isoformat(),
    "device_serial": device_serial,
    "test_scope": test_scope,
    "requested_iterations": total,
    "completed_iterations": len(rows),
    "minimum_successes_for_98_percent": minimum,
    "counts": counts,
    "success_rate": passed / total,
    "iteration_duration_ms": {
        "sample_count": len(durations),
        "p50": statistics.median(durations) if durations else None,
        "p95": percentile(durations, 0.95),
        "max": max(durations) if durations else None,
        "scope_notice": "Observed controlled-emulator instrumentation duration; no physical-device product SLO is inferred.",
    },
    "product_status": product_status,
    "test_system_status": test_system_status,
    "test_system_issue_ids": issue_ids,
    "startup_reliability_exit_criteria_met": passed == total and not issue_ids,
    "compose_aggregate_flake_exit_criteria_met": False,
    "scope_notice": "This focused startup campaign does not execute the full multi-journey Compose device class and therefore cannot close android-compose-snapshot-observer-runtime by itself.",
    "oracle": "Each iteration launches a fresh App-owned empty.clean session and requires the functional empty state in an isolated Room database; no retry is performed.",
    "iterations_file": str(Path(rows_path).resolve()),
}
path = Path(report_path)
with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8") as handle:
    json.dump(payload, handle, ensure_ascii=False, indent=2)
    handle.write("\n")
    temporary = handle.name
os.replace(temporary, path)
print(json.dumps(payload, ensure_ascii=False, indent=2))
if product_status != "PASSED" or test_system_status != "PASSED":
    raise SystemExit(2 if test_system_status in {"BLOCKED", "FLAKY", "FAILED"} else 1)
PY

echo "summary=$report"
