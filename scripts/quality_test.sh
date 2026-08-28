#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lane="${1:-pr}"
results_root="$repo_root/build/quality-results"
result_file="$results_root/android-$lane-summary.json"
lane_started_at_epoch="$(python3 -c 'import time; print(time.time())')"
mkdir -p "$results_root"

claims=()
selected_claims=()
test_system_issue_ids=()
not_run=(
  "real FCM, user permission-decision UI, Doze/reboot/install, and physical accessibility evidence"
)
if [[ "$lane" != "performance" && "$lane" != "release" ]]; then
  not_run+=("opt-in 100k production Room performance evidence")
  not_run+=("Release-like Macrobenchmark mechanics and physical-device performance evidence")
fi

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
  for item in "${test_system_issue_ids[@]}"; do args+=(--test-system-issue-id "$item"); done
  [[ -z "$reason" ]] || args+=(--reason "$reason")
  python3 "$repo_root/scripts/quality_result.py" "${args[@]}"
}

classify_current_test_system_failure() {
  local report_root="$repo_root/app/build/outputs/androidTest-results/connected"
  python3 "$repo_root/scripts/classify_android_test_failure.py" \
    --report-root "$report_root" \
    --started-at-epoch "$lane_started_at_epoch"
}

on_exit() {
  local status=$?
  local classification=""
  local issue_ids=""
  local issue_id=""
  local -a classified_ids=()
  if [[ $status -eq 0 ]]; then
    write_result PASSED PASSED
  elif [[ $status -eq 2 ]]; then
    write_result NOT_RUN BLOCKED "lane preparation was blocked before product evidence completed"
  elif classification="$(classify_current_test_system_failure)"; then
    printf '%s\n' "$classification"
    issue_ids="$(printf '%s\n' "$classification" | sed -n 's/^classification_issue_ids=//p')"
    IFS=',' read -r -a classified_ids <<< "$issue_ids"
    for issue_id in "${classified_ids[@]}"; do
      [[ -z "$issue_id" ]] || test_system_issue_ids+=("$issue_id")
    done
    write_result NOT_RUN FAILED "a recognized test-runtime/precondition failure prevented product evidence; inspect current device XML"
  else
    write_result FAILED PASSED "an executed product oracle failed; inspect Gradle/device reports for the first failure"
  fi
  printf 'quality_result=%s\n' "$result_file"
}
trap on_exit EXIT

if ! python3 "$repo_root/scripts/quality_test_system_issues.py" --check; then
  echo "status=BLOCKED"
  echo "reason=invalid_or_expired_android_test_system_issue_registry"
  exit 2
fi

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
  selected_claims+=("Android JVM behavior suite, localization completeness, and androidTest compilation")
  "$repo_root/scripts/quality_doctor.sh" --allow-no-device
  python3 "$repo_root/scripts/verify_android_localizations.py"
  "$repo_root/gradlew" \
    testDebugUnitTest \
    compileDebugAndroidTestKotlin \
    assembleDebug
  claims+=("Android JVM behavior suite, localization completeness, and androidTest compilation")
}

# These classes prove the highest-value product outcomes and storage boundaries.
# Keeping the routine device lane curated prevents diagnostics and rare platform
# permutations from consuming the feedback budget on every run.
quality_device_classes="io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest,io.ethan.pushgo.testing.QualityEntityJourneyInstrumentedTest,io.ethan.pushgo.testing.QualityChannelJourneyInstrumentedTest,io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest"
core_data_classes="io.ethan.pushgo.data.db.PushGoDatabaseMigrationDeviceTest,io.ethan.pushgo.data.PendingLocalDeletionRoomDeviceTest,io.ethan.pushgo.data.ProviderAckScopeDeviceTest"
nightly_data_classes="$core_data_classes,io.ethan.pushgo.testing.RuntimeDataLayerInstrumentedTest,io.ethan.pushgo.testing.RuntimeChannelSwitchInstrumentedTest,io.ethan.pushgo.testing.RuntimePrivateChannelStateFlowInstrumentedTest,io.ethan.pushgo.ui.PendingLocalDeletionWorkBoundaryDeviceTest"
system_notification_class="io.ethan.pushgo.testing.QualitySystemNotificationJourneyInstrumentedTest"

run_device_classes() {
  local classes="$1"
  local doctor_output
  local device_serial
  selected_claims+=("Android migration/deletion/ACK/transport data boundaries: $classes")
  doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
  printf '%s\n' "$doctor_output"
  device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
  [[ -n "$device_serial" ]] || {
    echo "status=BLOCKED"
    echo "reason=quality_doctor_missing_device_serial"
    exit 2
  }
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$classes"
  claims+=("Android migration/deletion/ACK/transport data boundaries: $classes")
}

run_quality_device_classes() {
  local session_id="android-lane-$(date +%s)"
  local payload
  local doctor_output
  local device_serial
  payload="$(printf '{"schema_version":1,"session_id":"%s","fixture":"empty.clean","faults":{}}' "$session_id" | base64 | tr -d '\n')"
  selected_claims+=("Android core App UI empty/content/pagination/read/search/delete/slow-load/slow-refresh/error-retry/navigation/Event/Thing/Channel/Settings journeys")
  doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
  printf '%s\n' "$doctor_output"
  device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
  [[ -n "$device_serial" ]] || {
    echo "status=BLOCKED"
    echo "reason=quality_doctor_missing_device_serial"
    exit 2
  }
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$quality_device_classes" \
    "-Pandroid.testInstrumentationRunnerArguments.pushgoQualitySessionBase64=$payload"
  claims+=("Android core App UI empty/content/pagination/read/search/delete/slow-load/slow-refresh/error-retry/navigation/Event/Thing/Channel/Settings journeys")
}

run_system_notification_journey() {
  local doctor_output
  local device_serial
  selected_claims+=("Android durable inbound to real system notification/PendingIntent and accurate detail/read/dedupe/relaunch journey")
  doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
  printf '%s\n' "$doctor_output"
  device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
  [[ -n "$device_serial" ]] || {
    echo "status=BLOCKED"
    echo "reason=quality_doctor_missing_device_serial"
    exit 2
  }
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" connectedDebugAndroidTest \
    --rerun-tasks \
    "-Pandroid.testInstrumentationRunnerArguments.class=$system_notification_class"
  claims+=("Android durable inbound to real system notification/PendingIntent and accurate detail/read/dedupe/relaunch journey")
}

run_performance() {
  local doctor_output
  local device_serial
  local device_log="$results_root/android-performance-device.log"

  selected_claims+=("Android 100k real Room correctness and provisional selected-emulator search ceiling")
  doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
  printf '%s\n' "$doctor_output"
  device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
  [[ -n "$device_serial" ]] || {
    echo "status=BLOCKED"
    echo "reason=quality_doctor_missing_device_serial"
    exit 2
  }
  [[ "$device_serial" == emulator-* ]] && [[ "$(adb -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
    echo "status=BLOCKED"
    echo "reason=hosted_performance_lane_requires_controlled_emulator:$device_serial"
    exit 2
  }
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" \
    connectedDebugAndroidTest \
    --rerun-tasks \
    "-Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.testing.RuntimeDataLayerInstrumentedTest#realRoomDaoSearchAndPaging_optIn100000" \
    "-Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.include100k=true" \
    2>&1 | tee "$device_log"
  claims+=("Android 100k real Room correctness and provisional selected-emulator search ceiling")

  selected_claims+=("Release-like Macrobenchmark mechanics with exact 1k startup/detail product Oracle on controlled emulator")
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" \
    :macrobenchmark:connectedBenchmarkBenchmarkAndroidTest \
    --rerun-tasks \
    --console=plain \
    "-Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.macrobenchmark.PushGoMacrobenchmark" \
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true" \
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR" \
    "-Pandroid.testInstrumentationRunnerArguments.pushgo.maxStartupMs=20000" \
    "-Pandroid.testInstrumentationRunnerArguments.pushgo.maxDetailMs=10000"
  claims+=("Release-like Macrobenchmark mechanics with exact 1k startup/detail product Oracle on controlled emulator")

  selected_claims+=("Filtered Baseline/Startup Profile and Release APK quality-control isolation")
  "$repo_root/gradlew" :app:assembleRelease --console=plain
  python3 "$repo_root/scripts/verify_android_performance_contract.py"
  claims+=("Filtered Baseline/Startup Profile and Release APK quality-control isolation")

  local physical_serial="${ANDROID_PERFORMANCE_DEVICE_SERIAL:-}"
  local physical_startup="${PUSHGO_ANDROID_PHYSICAL_MAX_STARTUP_MS:-}"
  local physical_detail="${PUSHGO_ANDROID_PHYSICAL_MAX_DETAIL_MS:-}"
  local physical_frame="${PUSHGO_ANDROID_PHYSICAL_MAX_FRAME_MS:-}"
  if [[ -z "$physical_serial$physical_startup$physical_detail$physical_frame" ]]; then
    not_run+=("physical-device cold-start/detail/frame performance: no explicit non-personal device and owner-approved budgets")
  elif [[ -z "$physical_serial" || -z "$physical_startup" || -z "$physical_detail" || -z "$physical_frame" ]]; then
    echo "status=BLOCKED"
    echo "reason=incomplete_physical_performance_contract"
    exit 2
  else
    selected_claims+=("Physical-device cold-start/detail/frame performance within explicit budgets")
    "$repo_root/scripts/run_android_physical_macrobenchmark.sh"
    claims+=("Physical-device cold-start/detail/frame performance within explicit budgets")
  fi
}

run_accessibility_localization() {
  local doctor_output
  local device_serial
  local device_api
  selected_claims+=("Android zh-CN large-font real message-detail and add-channel journey")
  python3 "$repo_root/scripts/verify_android_localizations.py"
  doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
  printf '%s\n' "$doctor_output"
  device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
  device_api="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_api" { print $2; exit }')"
  [[ -n "$device_serial" ]] || {
    echo "status=BLOCKED"
    echo "reason=quality_doctor_missing_device_serial"
    exit 2
  }
  [[ "$device_api" =~ ^[0-9]+$ ]] && (( device_api >= 33 )) || {
    echo "status=BLOCKED"
    echo "reason=accessibility_localization_requires_api_33_or_newer:$device_api"
    exit 2
  }
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" connectedDebugAndroidTest \
    --rerun-tasks \
    "-Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.testing.QualityAccessibilityLocalizationJourneyInstrumentedTest"
  claims+=("Android zh-CN large-font real message-detail and add-channel journey")
}

case "$lane" in
  focused)
    [[ -n "${TEST_FILTER:-}" || -n "${ANDROID_TEST_CLASS:-}" ]] || {
      echo "status=BLOCKED"
      echo "reason=focused_lane_requires_TEST_FILTER_or_ANDROID_TEST_CLASS"
      exit 2
    }
    if [[ -n "${ANDROID_TEST_CLASS:-}" ]]; then
      doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
      printf '%s\n' "$doctor_output"
      device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
      [[ -n "$device_serial" ]] || {
        echo "status=BLOCKED"
        echo "reason=quality_doctor_missing_device_serial"
        exit 2
      }
      selected_claims+=("Android focused device behavior: $ANDROID_TEST_CLASS")
      ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" connectedDebugAndroidTest \
        --rerun-tasks \
        "-Pandroid.testInstrumentationRunnerArguments.class=$ANDROID_TEST_CLASS"
      claims+=("Android focused device behavior: $ANDROID_TEST_CLASS")
    else
      selected_claims+=("Android focused JVM behavior: $TEST_FILTER")
      "$repo_root/scripts/quality_doctor.sh" --allow-no-device
      "$repo_root/gradlew" testDebugUnitTest --tests "$TEST_FILTER"
      claims+=("Android focused JVM behavior: $TEST_FILTER")
    fi
    ;;
  performance)
    run_performance
    ;;
  accessibility)
    run_accessibility_localization
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
    run_system_notification_journey
    run_accessibility_localization
    ;;
  release)
    run_jvm_and_compile_device_tests
    run_quality_device_classes
    run_device_classes "$nightly_data_classes"
    run_system_notification_journey
    run_accessibility_localization
    run_performance
    ;;
  *)
    echo "status=BLOCKED"
    echo "reason=unsupported_lane:$lane"
    exit 2
    ;;
esac

echo "status=PASSED"
echo "lane=$lane"
