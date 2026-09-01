#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
device_serial="${ANDROID_SERIAL:-}"
baseline_version="${PUSHGO_UPDATE_TEST_BASELINE_VERSION:-}"
candidate_version="${PUSHGO_UPDATE_TEST_CANDIDATE_VERSION:-}"
provided_baseline_apk="${PUSHGO_UPDATE_TEST_BASELINE_APK:-}"
provided_candidate_apk="${PUSHGO_UPDATE_TEST_CANDIDATE_APK:-}"
package_name="io.ethan.pushgo.benchmark"
apk_output="$repo_root/app/build/outputs/apk/benchmark/app-benchmark.apk"
metadata_output="$repo_root/app/build/outputs/apk/benchmark/output-metadata.json"
quality_results_root="${QUALITY_RESULTS_ROOT:-$repo_root/build/quality-results}"
results_root="$quality_results_root/android-update-install"

blocked() {
  printf 'status=BLOCKED\nreason=%s\n' "$1" >&2
  exit 2
}

failed() {
  printf 'status=FAILED\nreason=%s\n' "$1" >&2
  exit 1
}

command -v adb >/dev/null 2>&1 || blocked "adb is unavailable"
command -v python3 >/dev/null 2>&1 || blocked "python3 is unavailable"

if [[ -z "$device_serial" ]]; then
  online_emulators="$(adb devices | awk 'NR > 1 && $2 == "device" && $1 ~ /^emulator-/ { print $1 }')"
  online_emulator_count="$(printf '%s\n' "$online_emulators" | sed '/^$/d' | wc -l | tr -d ' ')"
  [[ "$online_emulator_count" == "1" ]] || blocked \
    "ANDROID_SERIAL is required unless exactly one emulator is online"
  device_serial="$online_emulators"
fi

[[ "$(adb -s "$device_serial" get-state 2>/dev/null || true)" == "device" ]] || blocked \
  "selected Android target is not online: $device_serial"
[[ "$(adb -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || blocked \
  "update-install mechanism evidence requires a controlled emulator"

if [[ -z "$baseline_version" ]]; then
  baseline_version="$($repo_root/gradlew -q :app:printReleaseVersionInfo | sed -n 's/^versionName=//p' | head -n 1)"
fi
[[ "$baseline_version" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || blocked \
  "baseline version must be a stable semantic version: $baseline_version"
if [[ -z "$candidate_version" ]]; then
  candidate_version="v${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))"
fi
[[ "$candidate_version" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || blocked \
  "candidate version must be a stable semantic version: $candidate_version"
[[ "$candidate_version" != "$baseline_version" ]] || blocked \
  "candidate version must differ from the baseline"

python3 "$repo_root/scripts/quality_disk_preflight.py" \
  --path "$results_root" \
  --minimum-free-bytes "${QUALITY_MIN_FREE_BYTES:-3221225472}" || exit 2

run_id="$(date -u +%Y%m%d-%H%M%S)"
run_dir="$results_root/$run_id"
work_dir="$(mktemp -d -t pushgo-update-install.XXXXXX)"
baseline_apk="$work_dir/baseline.apk"
candidate_apk="$work_dir/candidate.apk"
ui_dump="$run_dir/window.xml"
device_ui_dump="/data/local/tmp/pushgo-update-install-$run_id.xml"
server_log="$run_dir/http.log"
logcat_file="$run_dir/logcat.txt"
server_pid=""
server_port=""
mkdir -p "$run_dir"
device_phase_started_at=""

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" >/dev/null 2>&1 || true
  fi
  if [[ -n "$server_port" ]]; then
    adb -s "$device_serial" reverse --remove "tcp:$server_port" >/dev/null 2>&1 || true
  fi
  adb -s "$device_serial" logcat -d >"$logcat_file" 2>/dev/null || true
  adb -s "$device_serial" shell rm -f "$device_ui_dump" >/dev/null 2>&1 || true
  adb -s "$device_serial" uninstall "$package_name" >/dev/null 2>&1 || true
  rm -rf "$work_dir"
  exit "$status"
}
trap cleanup EXIT INT TERM

build_apk() {
  local version="$1"
  local destination="$2"
  "$repo_root/gradlew" :app:assembleBenchmark \
    "-Ppushgo.versionName=$version" \
    -Ppushgo.enableAbiSplits=false \
    --console=plain
  [[ -f "$apk_output" && -f "$metadata_output" ]] || blocked \
    "benchmark APK or metadata was not produced for $version"
  cp "$apk_output" "$destination"
}

if [[ -n "$provided_baseline_apk" || -n "$provided_candidate_apk" ]]; then
  [[ -n "$provided_baseline_apk" && -n "$provided_candidate_apk" ]] || blocked \
    "baseline and candidate APK inputs must be provided together"
  [[ -f "$provided_baseline_apk" && -f "$provided_candidate_apk" ]] || blocked \
    "provided update-install APK input is missing"
  command -v apkanalyzer >/dev/null 2>&1 || blocked "apkanalyzer is required for provided APK inputs"
  cp "$provided_baseline_apk" "$baseline_apk"
  cp "$provided_candidate_apk" "$candidate_apk"
  candidate_metadata="$(
    printf '%s|%s|%s\n' \
      "$(apkanalyzer manifest application-id "$candidate_apk" 2>/dev/null)" \
      "$(apkanalyzer manifest version-code "$candidate_apk" 2>/dev/null)" \
      "$(apkanalyzer manifest version-name "$candidate_apk" 2>/dev/null)"
  )"
else
  build_apk "$baseline_version" "$baseline_apk"
  build_apk "$candidate_version" "$candidate_apk"
  candidate_metadata="$(python3 - "$metadata_output" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding="utf-8"))
element = payload["elements"][0]
print(f'{payload["applicationId"]}|{element["versionCode"]}|{element["versionName"]}')
PY
)"
fi
IFS='|' read -r candidate_package candidate_version_code candidate_metadata_name <<<"$candidate_metadata"
[[ "$candidate_package" == "$package_name" ]] || blocked \
  "candidate package is not the isolated benchmark application"
[[ "$candidate_metadata_name" == "$candidate_version" ]] || blocked \
  "candidate APK versionName does not match the requested candidate"
candidate_sha="$(shasum -a 256 "$candidate_apk" | awk '{print $1}')"

server_port="$(python3 - <<'PY'
import socket
with socket.socket() as sock:
    sock.bind(("127.0.0.1", 0))
    print(sock.getsockname()[1])
PY
)"
python3 -m http.server "$server_port" --bind 127.0.0.1 --directory "$work_dir" \
  >"$server_log" 2>&1 &
server_pid="$!"
adb -s "$device_serial" reverse "tcp:$server_port" "tcp:$server_port" >/dev/null
sleep 0.2
kill -0 "$server_pid" >/dev/null 2>&1 || blocked "local APK server did not start"

adb -s "$device_serial" uninstall "$package_name" >/dev/null 2>&1 || true
device_phase_started_at="$(date +%s)"
adb -s "$device_serial" install -t "$baseline_apk" >/dev/null || blocked \
  "baseline APK could not be installed"
installed_baseline="$(adb -s "$device_serial" shell dumpsys package "$package_name" \
  | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)"
[[ -n "$installed_baseline" && "$installed_baseline" != "$candidate_version_code" ]] || blocked \
  "baseline install did not establish an older package version"

session_id="update-install-${run_id//-/}"
encoded_session="$(python3 - \
  "$session_id" "$candidate_version_code" "$candidate_version" "$server_port" "$candidate_sha" <<'PY'
import base64
import json
import sys

session_id, version_code, version_name, port, digest = sys.argv[1:]
payload = {
    "schema_version": 1,
    "session_id": session_id,
    "fixture": "messages.standard",
    "update_scenario": "available_stable",
    "update_artifact": {
        "version_code": int(version_code),
        "version_name": version_name,
        "apk_url": f"http://127.0.0.1:{port}/candidate.apk",
        "apk_sha256": digest,
    },
    "faults": {},
}
print(base64.b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode())
PY
)"

prepare_output="$(adb -s "$device_serial" shell content call \
  --uri "content://$package_name.quality-fixture" \
  --method prepare \
  --arg "$encoded_session" 2>&1)" || blocked \
  "App-owned update fixture could not be prepared: $prepare_output"
rg -q 'status=ready' <<<"$prepare_output" || blocked \
  "App-owned update fixture did not report readiness: $prepare_output"

dump_ui() {
  adb -s "$device_serial" shell uiautomator dump "$device_ui_dump" >/dev/null 2>&1 || return 1
  adb -s "$device_serial" exec-out cat "$device_ui_dump" >"$ui_dump" 2>/dev/null || return 1
  rg -q '<hierarchy' "$ui_dump"
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
    if mode == "text-bottom" and query not in text and query not in description:
        continue
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
    if match:
        x1, y1, x2, y2 = map(int, match.groups())
        if mode == "text-bottom":
            print(f"{(x1 + x2) // 2},{max(y1, y2 - 26)}")
        else:
            print(f"{(x1 + x2) // 2},{(y1 + y2) // 2}")
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

tap_node() {
  local mode="$1"
  local query="$2"
  wait_for_node "$mode" "$query" 15 || failed "UI node was not reachable: $query"
  dump_ui || failed "UI tree could not be captured before tapping $query"
  local center
  center="$(node_center "$mode" "$query")" || failed "UI node lost before tapping: $query"
  adb -s "$device_serial" shell input tap "${center%,*}" "${center#*,}"
}

adb -s "$device_serial" logcat -c
adb -s "$device_serial" shell am force-stop "$package_name"
adb -s "$device_serial" shell am start -n "$package_name/io.ethan.pushgo.MainActivity" >/dev/null
wait_for_node text "Version $candidate_version is available" 20 || failed \
  "baseline app did not show the exact App-owned update candidate"
wait_for_node text "Install now" 5 || failed \
  "the real update prompt did not expose its positive install action"

adb -s "$device_serial" shell appops set "$package_name" REQUEST_INSTALL_PACKAGES allow
tap_node text "Install now"

installer_observed=0
deadline=$((SECONDS + 75))
while (( SECONDS < deadline )); do
  installed_version="$(adb -s "$device_serial" shell dumpsys package "$package_name" \
    | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)"
  if [[ "$installed_version" == "$candidate_version_code" ]]; then
    break
  fi
  if dump_ui; then
    current_package="$(python3 - "$ui_dump" <<'PY'
import sys
import xml.etree.ElementTree as ET
root = ET.parse(sys.argv[1]).getroot()
node = next(root.iter("node"), None)
print(node.attrib.get("package", "") if node is not None else "")
PY
)"
    if [[ -n "$current_package" && "$current_package" != "$package_name" ]]; then
      installer_observed=1
    fi
    clicked=0
    for label in \
      "Update" "Install" "Continue" \
      "More details" "Install anyway" \
      "更新" "安装" "更多详情" "仍要安装" "不扫描直接安装" \
      "繼續安裝" "安裝" "更多詳細資料" "仍要安裝"; do
      if center="$(node_center text "$label" 2>/dev/null)"; then
        adb -s "$device_serial" shell input tap "${center%,*}" "${center#*,}"
        clicked=1
        break
      fi
    done
    if (( clicked == 0 )); then
      if center="$(node_center text-bottom "Install without scanning" 2>/dev/null)"; then
        adb -s "$device_serial" shell input tap "${center%,*}" "${center#*,}"
        clicked=1
      fi
    fi
    (( clicked == 0 )) || sleep 0.5
  fi
  sleep 0.25
done

installed_version="$(adb -s "$device_serial" shell dumpsys package "$package_name" \
  | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1)"
[[ "$installed_version" == "$candidate_version_code" ]] || failed \
  "PackageInstaller did not install the candidate; installer_observed=$installer_observed baseline=$installed_baseline actual=${installed_version:-missing} expected=$candidate_version_code"

adb -s "$device_serial" shell am force-stop "$package_name"
adb -s "$device_serial" shell am start -n "$package_name/io.ethan.pushgo.MainActivity" >/dev/null
wait_for_node resource "quality-runtime.ready" 20 || failed \
  "updated app did not become functionally ready"
wait_for_node resource "message.row.quality-standard-message" 15 || failed \
  "updated app did not preserve the canonical message row"
tap_node resource "message.row.quality-standard-message"
wait_for_node text "P2 Split Seed Message" 10 || failed \
  "updated app did not preserve the exact canonical title"
wait_for_node text "Seeded from fixture.seed_messages for UI validation." 10 || failed \
  "updated app did not preserve the exact canonical body"

device_phase_seconds="$(( $(date +%s) - device_phase_started_at ))"

python3 - "$run_dir/evidence.json" \
  "$device_serial" "$baseline_version" "$installed_baseline" \
  "$candidate_version" "$candidate_version_code" "$candidate_sha" \
  "$device_phase_seconds" <<'PY'
import json
import sys

path, serial, baseline_name, baseline_code, candidate_name, candidate_code, digest, duration = sys.argv[1:]
payload = {
    "status": "PASSED",
    "scope": "release-shaped benchmark variant update mechanism on a controlled emulator",
    "device_serial": serial,
    "baseline": {"version_name": baseline_name, "version_code": int(baseline_code)},
    "candidate": {
        "version_name": candidate_name,
        "version_code": int(candidate_code),
        "sha256": digest,
    },
    "cost": {
        "product_install_actions": 1,
        "business_action_retries": 0,
        "device_phase_seconds": int(duration),
    },
    "oracles": [
        "candidate downloaded through the real UpdateInstaller",
        "SHA-256, archive package/version, and signer checks completed",
        "PackageInstaller replaced the running package",
        "updated app reached App-owned readiness",
        "exact canonical title and body survived package replacement",
    ],
    "not_run": [
        "production release signing identity and public update feed",
        "physical-device/OEM installer policy",
    ],
}
with open(path, "w", encoding="utf-8") as handle:
    json.dump(payload, handle, indent=2, ensure_ascii=False)
    handle.write("\n")
PY

printf 'status=PASSED\nevidence=%s\n' "$run_dir/evidence.json"
