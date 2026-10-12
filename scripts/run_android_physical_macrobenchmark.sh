#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
device_serial="${ANDROID_PERFORMANCE_DEVICE_SERIAL:-}"
startup_budget="${PUSHGO_ANDROID_PHYSICAL_MAX_STARTUP_MS:-}"
detail_budget="${PUSHGO_ANDROID_PHYSICAL_MAX_DETAIL_MS:-}"
frame_budget="${PUSHGO_ANDROID_PHYSICAL_MAX_FRAME_MS:-}"

blocked() {
  printf 'status=BLOCKED\nreason=%s\n' "$1" >&2
  exit 2
}

[[ -n "$device_serial" ]] || blocked "ANDROID_PERFORMANCE_DEVICE_SERIAL is required; no device is selected implicitly"
for budget_pair in \
  "startup:$startup_budget" \
  "detail:$detail_budget" \
  "frame:$frame_budget"; do
  budget_name="${budget_pair%%:*}"
  budget_value="${budget_pair#*:}"
  [[ "$budget_value" =~ ^[0-9]+([.][0-9]+)?$ ]] || blocked "$budget_name budget must be an explicit positive number"
  awk -v value="$budget_value" 'BEGIN { exit !(value > 0) }' || blocked "$budget_name budget must be greater than zero"
done

command -v adb >/dev/null 2>&1 || blocked "adb is unavailable"
device_state="$(adb -s "$device_serial" get-state 2>/dev/null || true)"
[[ "$device_state" == "device" ]] || blocked "requested device is not connected and online: $device_serial"
[[ "$device_serial" != emulator-* ]] || blocked "physical performance evidence rejects emulator serials"
qemu_state="$(adb -s "$device_serial" shell getprop ro.kernel.qemu 2>/dev/null | tr -d '\r')"
[[ "$qemu_state" != "1" ]] || blocked "physical performance evidence rejects qemu targets"

device_api="$(adb -s "$device_serial" shell getprop ro.build.version.sdk | tr -d '\r')"
[[ "$device_api" =~ ^[0-9]+$ ]] && (( device_api >= 28 )) || blocked "physical performance requires API 28 or newer"
battery_level="$(adb -s "$device_serial" shell dumpsys battery | sed -n 's/^[[:space:]]*level: //p' | head -n 1 | tr -d '\r')"
[[ "$battery_level" =~ ^[0-9]+$ ]] || blocked "unable to resolve physical-device battery level"
(( battery_level >= 30 )) || blocked "physical-device battery is below 30 percent"
thermal_status="$(adb -s "$device_serial" shell dumpsys thermalservice 2>/dev/null | sed -n 's/.*mStatus=//p' | head -n 1 | tr -d '\r')"
if [[ "$thermal_status" =~ ^[0-9]+$ ]] && (( thermal_status >= 2 )); then
  blocked "physical device thermal status is moderate or worse: $thermal_status"
fi

run_id="$(date -u +%Y%m%d-%H%M%S)"
run_dir="$repo_root/build/quality-results/android-physical-macrobenchmark/$run_id"
mkdir -p "$run_dir"
additional_output="$repo_root/macrobenchmark/build/outputs/connected_android_test_additional_output/benchmarkBenchmark"
run_marker="$run_dir/.started"
touch "$run_marker"

set +e
ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" \
  :macrobenchmark:connectedBenchmarkBenchmarkAndroidTest \
  --rerun-tasks \
  --console=plain \
  "-Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.macrobenchmark.PushGoMacrobenchmark" \
  "-Pandroid.testInstrumentationRunnerArguments.pushgo.maxStartupMs=$startup_budget" \
  "-Pandroid.testInstrumentationRunnerArguments.pushgo.maxDetailMs=$detail_budget" \
  2>&1 | tee "$run_dir/gradle.log"
gradle_status=${PIPESTATUS[0]}
set -e
(( gradle_status == 0 )) || {
  if grep -Eq 'ERRORS \(not suppressed\)|Observed no .* slices|UiAutomation not connected|benchmarkData.json' "$run_dir/gradle.log"; then
    printf 'status=BLOCKED\nreason=Macrobenchmark infrastructure could not produce trustworthy measurements\n' >&2
    exit 2
  fi
  printf 'status=FAILED\nreason=macrobenchmark product oracle or explicit task budget failed\n' >&2
  exit 1
}

benchmark_json_candidates="$(
  find "$additional_output" -type f -name '*benchmarkData.json' -newer "$run_marker" -print 2>/dev/null
)"
benchmark_json_count="$(printf '%s\n' "$benchmark_json_candidates" | sed '/^$/d' | wc -l | tr -d ' ')"
[[ "$benchmark_json_count" == "1" ]] || blocked "expected one fresh benchmarkData.json, found $benchmark_json_count"
benchmark_json="$benchmark_json_candidates"
cp "$benchmark_json" "$run_dir/benchmarkData.json"
find "$additional_output" -type f -name '*.perfetto-trace' -newer "$run_marker" -exec cp {} "$run_dir/" \;
python3 "$repo_root/scripts/validate_android_macrobenchmark.py" \
  --input "$run_dir/benchmarkData.json" \
  --max-frame-ms "$frame_budget" \
  --output "$run_dir/frame-budget.json"

printf 'status=PASSED\nartifacts=%s\n' "$run_dir"
