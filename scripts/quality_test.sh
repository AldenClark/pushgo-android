#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lane="${1:-pr}"
results_root="$repo_root/build/quality-results"
result_file="$results_root/android-$lane-summary.json"
mkdir -p "$results_root"

claims=()
selected_claims=()
not_run=(
  "real FCM/notification permission/Doze/reboot/install/physical accessibility evidence"
  "Macrobenchmark/physical-device performance evidence"
)

write_result() {
  local product_status="$1"
  local test_system_status="$2"
  local reason="${3:-}"
  local args=(
    --output "$result_file"
    --platform android
    --lane "$lane"
    --product-status "$product_status"
    --test-system-status "$test_system_status"
  )
  local item
  for item in "${selected_claims[@]}"; do args+=(--selected-claim "$item"); done
  for item in "${claims[@]}"; do args+=(--claim "$item"); done
  for item in "${not_run[@]}"; do args+=(--not-run "$item"); done
  [[ -z "$reason" ]] || args+=(--reason "$reason")
  python3 "$repo_root/scripts/quality_result.py" "${args[@]}"
}

on_exit() {
  local status=$?
  if [[ $status -eq 0 ]]; then
    write_result PASSED PASSED
  elif [[ $status -eq 2 ]]; then
    write_result NOT_RUN BLOCKED "lane preparation was blocked before product evidence completed"
  else
    write_result FAILED PASSED "an executed product oracle failed; inspect Gradle/device reports for the first failure"
  fi
  printf 'quality_result=%s\n' "$result_file"
}
trap on_exit EXIT

run_impact_contracts() {
  local plan_path="${QUALITY_IMPACT_PLAN:-}"
  local check
  local release_tag
  local checks_output
  if ! checks_output="$(
    python3 - "$plan_path" "$lane" <<'PY'
import json
import pathlib
import sys

plan_path, lane = sys.argv[1:]
checks = set()
if plan_path:
    path = pathlib.Path(plan_path)
    if not path.is_file():
        raise SystemExit(f"impact plan is not a regular file: {path}")
    checks.update(json.loads(path.read_text()).get("required_checks", []))
if lane == "release":
    checks.update({"android-release-static-contract", "android-update-distribution-contract"})
print("\n".join(sorted(checks)))
PY
  )"; then
    echo "status=BLOCKED"
    echo "reason=invalid_android_impact_plan"
    exit 2
  fi
  while IFS= read -r check; do
    [[ -n "$check" ]] || continue
    case "$check" in
      android-update-distribution-contract)
        selected_claims+=("Android signed update feed contract")
        "$repo_root/scripts/verify_update_feed.sh" "$repo_root/release/update-feed-v1.json"
        "$repo_root/gradlew" testDebugUnitTest \
          --tests io.ethan.pushgo.update.UpdateFeedSignatureRegressionTest
        claims+=("Android signed update feed contract")
        ;;
      android-release-static-contract)
        selected_claims+=("Android JNI/toolchain/schema/release static contracts")
        "$repo_root/scripts/verify_jni_contract.sh"
        release_tag="$($repo_root/gradlew -q :app:printReleaseVersionInfo | sed -n 's/^versionName=//p' | tail -n 1)"
        [[ -n "$release_tag" ]] || {
          echo "status=BLOCKED"
          echo "reason=unable_to_resolve_android_release_tag"
          exit 2
        }
        "$repo_root/scripts/verify_android_release_contract.sh" "$release_tag"
        claims+=("Android JNI/toolchain/schema/release static contracts")
        ;;
      *)
        echo "status=BLOCKED"
        echo "reason=unsupported_android_impact_check:$check"
        exit 2
        ;;
    esac
  done <<< "$checks_output"
}

run_impact_contracts

run_jvm_and_compile_device_tests() {
  selected_claims+=("Android JVM behavior suite and androidTest compilation")
  "$repo_root/scripts/quality_doctor.sh" --allow-no-device
  "$repo_root/gradlew" \
    testDebugUnitTest \
    compileDebugAndroidTestKotlin \
    assembleDebug
  claims+=("Android JVM behavior suite and androidTest compilation")
}

# These classes prove the highest-value product outcomes and storage boundaries.
# Keeping the routine device lane curated prevents diagnostics and rare platform
# permutations from consuming the feedback budget on every run.
quality_device_classes="io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest,io.ethan.pushgo.testing.QualityEntityJourneyInstrumentedTest,io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest"
core_data_classes="io.ethan.pushgo.data.db.PushGoDatabaseMigrationDeviceTest,io.ethan.pushgo.data.PendingLocalDeletionRoomDeviceTest,io.ethan.pushgo.data.ProviderAckScopeDeviceTest"
nightly_data_classes="$core_data_classes,io.ethan.pushgo.testing.RuntimeDataLayerInstrumentedTest,io.ethan.pushgo.testing.RuntimeChannelSwitchInstrumentedTest,io.ethan.pushgo.testing.RuntimePrivateChannelStateFlowInstrumentedTest,io.ethan.pushgo.ui.PendingLocalDeletionWorkBoundaryDeviceTest"

run_device_classes() {
  local classes="$1"
  selected_claims+=("Android migration/deletion/ACK/transport data boundaries: $classes")
  "$repo_root/scripts/quality_doctor.sh"
  "$repo_root/gradlew" connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$classes"
  claims+=("Android migration/deletion/ACK/transport data boundaries: $classes")
}

run_quality_device_classes() {
  local session_id="android-lane-$(date +%s)"
  local payload
  payload="$(printf '{"schema_version":1,"session_id":"%s","fixture":"empty.clean","faults":{}}' "$session_id" | base64 | tr -d '\n')"
  selected_claims+=("Android core App UI empty/content/pagination/read/search/delete/slow-load/slow-refresh/error-retry/navigation/Event/Thing journeys")
  "$repo_root/scripts/quality_doctor.sh"
  "$repo_root/gradlew" connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$quality_device_classes" \
    "-Pandroid.testInstrumentationRunnerArguments.pushgoQualitySessionBase64=$payload"
  claims+=("Android core App UI empty/content/pagination/read/search/delete/slow-load/slow-refresh/error-retry/navigation/Event/Thing journeys")
}

case "$lane" in
  focused)
    [[ -n "${TEST_FILTER:-}" ]] || {
      echo "status=BLOCKED"
      echo "reason=focused_lane_requires_TEST_FILTER"
      exit 2
    }
    selected_claims+=("Android focused JVM behavior: $TEST_FILTER")
    "$repo_root/scripts/quality_doctor.sh" --allow-no-device
    "$repo_root/gradlew" testDebugUnitTest --tests "$TEST_FILTER"
    claims+=("Android focused JVM behavior: $TEST_FILTER")
    ;;
  pr)
    run_jvm_and_compile_device_tests
    ;;
  pr-ui)
    run_quality_device_classes
    ;;
  device)
    run_jvm_and_compile_device_tests
    run_quality_device_classes
    run_device_classes "$core_data_classes"
    ;;
  nightly)
    run_jvm_and_compile_device_tests
    run_quality_device_classes
    run_device_classes "$nightly_data_classes"
    ;;
  release)
    run_jvm_and_compile_device_tests
    run_quality_device_classes
    run_device_classes "$nightly_data_classes"
    "$repo_root/gradlew" assembleRelease
    ;;
  *)
    echo "status=BLOCKED"
    echo "reason=unsupported_lane:$lane"
    exit 2
    ;;
esac

echo "status=PASSED"
echo "lane=$lane"
