#!/usr/bin/env bash
set -euo pipefail

if [[ "${PUSHGO_ANDROID_QUALITY_SCRIPT_SNAPSHOT:-0}" != "1" ]]; then
  script_path="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
  export PUSHGO_ANDROID_QUALITY_SCRIPT_SNAPSHOT=1
  export PUSHGO_ANDROID_QUALITY_REPO_ROOT="$(cd "$(dirname "$script_path")/.." && pwd)"
  exec /bin/bash -s -- "$@" < "$script_path"
fi

repo_root="${PUSHGO_ANDROID_QUALITY_REPO_ROOT:?missing quality script repository root}"
lane="${1:-pr}"
results_root="${QUALITY_RESULTS_ROOT:-$repo_root/build/quality-results}"
result_file="${QUALITY_RESULT_FILE:-$results_root/android-$lane-summary.json}"
lane_started_at_epoch="$(python3 -c 'import time; print(time.time())')"
mkdir -p "$results_root"

claims=()
selected_claims=()
test_system_issue_ids=()
not_run=(
  "real FCM/private delivery, reboot, production-distributed/OEM install policy, physical audio, and physical accessibility evidence"
)
controlled_system_not_run="controlled-emulator notification permission, Doze, system notification, and Private Service journeys"
controlled_system_physical_not_run="physical/OEM notification, Doze, and Private Service behavior beyond the controlled-emulator system journeys"
if [[ "$lane" != "nightly" && "$lane" != "release" ]]; then
  not_run+=("$controlled_system_not_run")
else
  not_run+=("$controlled_system_physical_not_run")
fi
if [[ "$lane" != "performance" && "$lane" != "release" ]]; then
  not_run+=("opt-in 100k production Room performance evidence")
  not_run+=("Release-like Macrobenchmark mechanics and physical-device performance evidence")
fi

android_device_lock_timeout="${QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS:-15}"
android_device_lock_root="${QUALITY_ANDROID_LOCK_ROOT:-${TMPDIR:-/tmp}/pushgo-android-quality-locks}"
android_device_lock_dir=""
android_device_lock_acquired=0

acquire_android_device_lock() {
  local device_serial="$1"
  local safe_serial owner_pid deadline
  [[ "$android_device_lock_timeout" =~ ^[1-9][0-9]*$ ]] || {
    echo "status=BLOCKED"
    echo "reason=QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS must be a positive integer"
    return 2
  }
  safe_serial="$(printf '%s' "$device_serial" | tr -c 'A-Za-z0-9_.-' '_')"
  android_device_lock_dir="$android_device_lock_root/$safe_serial"
  if ! mkdir -p "$android_device_lock_root"; then
    android_device_lock_dir=""
    echo "status=FAILED_TEST_SYSTEM"
    echo "reason=android_device_lock_root_unavailable:$android_device_lock_root"
    return 3
  fi
  deadline=$((SECONDS + android_device_lock_timeout))
  while ! mkdir "$android_device_lock_dir" 2>/dev/null; do
    owner_pid=""
    [[ -f "$android_device_lock_dir/pid" ]] && owner_pid="$(<"$android_device_lock_dir/pid")"
    if [[ "$owner_pid" =~ ^[0-9]+$ ]] && ! kill -0 "$owner_pid" >/dev/null 2>&1; then
      rmdir "$android_device_lock_dir" 2>/dev/null || true
      continue
    fi
    if (( SECONDS >= deadline )); then
      android_device_lock_dir=""
      echo "status=BLOCKED"
      echo "reason=selected Android device is busy: $device_serial"
      return 2
    fi
    sleep 0.25
  done
  printf '%s\n' "$$" >"$android_device_lock_dir/pid"
  android_device_lock_acquired=1
}

release_android_device_lock() {
  local owner_pid=""
  [[ "$android_device_lock_acquired" -eq 1 && -n "$android_device_lock_dir" ]] || return 0
  [[ -f "$android_device_lock_dir/pid" ]] && owner_pid="$(<"$android_device_lock_dir/pid")"
  if [[ "$owner_pid" == "$$" ]]; then
    rm -f "$android_device_lock_dir/pid"
    rmdir "$android_device_lock_dir" 2>/dev/null || true
  fi
  android_device_lock_dir=""
  android_device_lock_acquired=0
}

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
  for item in "${selected_claims[@]-}"; do [[ -z "$item" ]] || args+=(--selected-claim "$item"); done
  for item in "${claims[@]-}"; do [[ -z "$item" ]] || args+=(--claim "$item"); done
  for item in "${not_run[@]}"; do args+=(--not-run "$item"); done
  for item in "${test_system_issue_ids[@]-}"; do [[ -z "$item" ]] || args+=(--test-system-issue-id "$item"); done
  [[ -z "$reason" ]] || args+=(--reason "$reason")
  python3 "$repo_root/scripts/quality_result.py" "${args[@]}"
}

classify_current_test_system_failure() {
  local report_root="$repo_root/app/build/outputs/androidTest-results/connected"
  python3 "$repo_root/scripts/classify_android_test_failure.py" \
    --report-root "$report_root" \
    --started-at-epoch "$lane_started_at_epoch"
}

verify_device_tests_executed() {
  local started_at_epoch="$1"
  local report_root="$2"
  local -a verify_args=(
    --report-root "$report_root"
    --started-at-epoch "$started_at_epoch"
  )
  if [[ -n "${QUALITY_EXPECTED_ANDROID_TEST_COUNT:-}" ]]; then
    [[ "${QUALITY_EXPECTED_ANDROID_TEST_COUNT}" =~ ^[1-9][0-9]*$ ]] || {
      echo "status=BLOCKED"
      echo "reason=invalid_expected_android_test_count:${QUALITY_EXPECTED_ANDROID_TEST_COUNT}"
      return 2
    }
    verify_args+=(--expected-test-count "$QUALITY_EXPECTED_ANDROID_TEST_COUNT")
  fi
  if [[ -n "${QUALITY_EXPECTED_ANDROID_TEST_SELECTORS:-}" ]]; then
    verify_args+=(--expected-selectors "$QUALITY_EXPECTED_ANDROID_TEST_SELECTORS")
  fi
  if ! python3 "$repo_root/scripts/verify_android_test_execution.py" "${verify_args[@]}"; then
    return 3
  fi
}

on_exit() {
  local status=$?
  local classification=""
  local issue_ids=""
  local issue_id=""
  local -a classified_ids=()
  release_android_device_lock || true
  if [[ $status -eq 0 ]]; then
    write_result PASSED PASSED
  elif [[ $status -eq 2 ]]; then
    write_result NOT_RUN BLOCKED "lane preparation was blocked before product evidence completed"
  elif [[ $status -eq 3 ]]; then
    write_result NOT_RUN FAILED "fresh Android execution did not exactly match every selected scope; no product claim was completed"
  elif [[ $status -eq 4 ]]; then
    write_result NOT_RUN FAILED "a required test-system sensitivity control did not reject the deliberately broken behavior"
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

quality_minimum_free_bytes="${QUALITY_MIN_FREE_BYTES:-3221225472}"
# A named host-JVM focused check does not compile androidTest or assemble an APK.
# Keep a smaller, explicit reserve only for that low-growth path so a full PR
# lane cannot be accidentally weakened when shared disk space is tight.
if [[ -z "${QUALITY_MIN_FREE_BYTES:-}" \
  && "$lane" == "focused" \
  && -n "${TEST_FILTER:-}" \
  && -z "${ANDROID_TEST_CLASS:-}" ]]; then
  quality_minimum_free_bytes=1073741824
fi

if ! python3 "$repo_root/scripts/quality_disk_preflight.py" \
  --path "$results_root" \
  --minimum-free-bytes "$quality_minimum_free_bytes"; then
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
      android-preparation-contract)
        selected_claims+=("Android App-owned preparation rejects invalid sessions within 10 seconds and recovers to accurate functional empty state")
        "$repo_root/scripts/run_android_preparation_contract.sh"
        claims+=("Android App-owned preparation rejects invalid sessions within 10 seconds and recovers to accurate functional empty state")
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
  local host_test_started_at
  selected_claims+=("Android JVM behavior suite, localization completeness, and androidTest compilation")
  "$repo_root/scripts/quality_doctor.sh" --allow-no-device
  python3 "$repo_root/scripts/verify_android_localizations.py"
  host_test_started_at="$(python3 -c 'import time; print(time.time())')"
  "$repo_root/gradlew" \
    testDebugUnitTest --rerun-tasks \
    compileDebugAndroidTestKotlin \
    assembleDebug
  verify_device_tests_executed \
    "$host_test_started_at" \
    "$repo_root/app/build/test-results/testDebugUnitTest"
  claims+=("Android JVM behavior suite, localization completeness, and androidTest compilation")
}

# These classes prove the highest-value product outcomes and storage boundaries.
# Keeping the routine device lane curated prevents diagnostics and rare platform
# permutations from consuming the feedback budget on every run.
quality_device_classes="io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest,io.ethan.pushgo.testing.QualityEntityJourneyInstrumentedTest,io.ethan.pushgo.testing.QualityChannelJourneyInstrumentedTest,io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest"
# PR uses one representative purpose chain per high-value owner, while explicit
# device runs retain the broader positive set. Impact-selected PR changes still
# replace this fallback with their exact required_device_scopes. Full classes
# remain in nightly/release.
pr_device_scopes="io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#standardFixtureShowsAccurateContentAndSurvivesActivityRelaunch,io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#workflowFixtureLoadsSecondPageAndPersistsReadActions,io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#primaryNavigationUsesRealControlsAndReachesEveryProductScreen,io.ethan.pushgo.testing.QualityEntityJourneyInstrumentedTest#eventClosePersistsAndOngoingFilterReflectsTheRealProjection,io.ethan.pushgo.testing.QualityChannelJourneyInstrumentedTest#createRenameAndBothUnsubscribeOutcomesReachAccuratePersistentUserResults,io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest#serverConfigurationRejectsInvalidInputAndScopesDataAfterRelaunch"
positive_device_scopes="io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#markdownFixtureRendersMajorStructuresInTheRealDetail,io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#standardFixtureShowsAccurateContentAndSurvivesActivityRelaunch,io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#historyCleanupRemovesOnlyOldMessagesAndPersistsAcrossRelaunch,io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#workflowFixtureLoadsSecondPageAndPersistsReadActions,io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#channelTagCombinedUngroupedFiltersAndScopedReadPersist,io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#primaryNavigationUsesRealControlsAndReachesEveryProductScreen,io.ethan.pushgo.testing.QualityEntityJourneyInstrumentedTest#eventClosePersistsAndOngoingFilterReflectsTheRealProjection,io.ethan.pushgo.testing.QualityEntityJourneyInstrumentedTest#thingFixtureShowsAccurateOverviewAndAllThreeRealRelationTabs,io.ethan.pushgo.testing.QualityChannelJourneyInstrumentedTest#createRenameAndBothUnsubscribeOutcomesReachAccuratePersistentUserResults,io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest#encryptedMessageRecoversThroughRealSettingsEntryAndSurvivesRelaunch,io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest#dataPageVisibilityUsesRealControlsAndPersistsAcrossRelaunch,io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest#serverConfigurationRejectsInvalidInputAndScopesDataAfterRelaunch"
core_data_classes="io.ethan.pushgo.data.db.PushGoDatabaseMigrationDeviceTest,io.ethan.pushgo.data.PendingLocalDeletionRoomDeviceTest,io.ethan.pushgo.data.ProviderAckScopeDeviceTest"
nightly_data_classes="$core_data_classes,io.ethan.pushgo.testing.RuntimeDataLayerInstrumentedTest,io.ethan.pushgo.testing.RuntimeChannelSwitchInstrumentedTest,io.ethan.pushgo.testing.RuntimePrivateChannelStateFlowInstrumentedTest,io.ethan.pushgo.ui.PendingLocalDeletionWorkBoundaryDeviceTest"
system_notification_classes="io.ethan.pushgo.testing.QualitySystemNotificationJourneyInstrumentedTest,io.ethan.pushgo.testing.QualityPrivateForegroundServiceJourneyInstrumentedTest"

run_device_classes() {
  local classes="$1"
  local claim="${2:-Android migration/deletion/ACK/transport data boundaries: $classes}"
  local doctor_output
  local device_serial
  selected_claims+=("$claim")
  doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
  printf '%s\n' "$doctor_output"
  device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
  [[ -n "$device_serial" ]] || {
    echo "status=BLOCKED"
    echo "reason=quality_doctor_missing_device_serial"
    exit 2
  }
  acquire_android_device_lock "$device_serial"
  local device_test_started_at
  device_test_started_at="$(python3 -c 'import time; print(time.time())')"
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$classes"
  verify_device_tests_executed "$device_test_started_at" "$repo_root/app/build/outputs/androidTest-results/connected"
  release_android_device_lock
  claims+=("$claim")
}

run_quality_device_classes() {
  local classes="${1:-$quality_device_classes}"
  local session_id="android-lane-$(date +%s)"
  local payload
  local doctor_output
  local device_serial
  payload="$(printf '{"schema_version":1,"session_id":"%s","fixture":"empty.clean","faults":{}}' "$session_id" | base64 | tr -d '\n')"
  selected_claims+=("Android selected App-owned UI journeys: $classes")
  doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
  printf '%s\n' "$doctor_output"
  device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
  [[ -n "$device_serial" ]] || {
    echo "status=BLOCKED"
    echo "reason=quality_doctor_missing_device_serial"
    exit 2
  }
  acquire_android_device_lock "$device_serial"
  local device_test_started_at
  device_test_started_at="$(python3 -c 'import time; print(time.time())')"
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$classes" \
    "-Pandroid.testInstrumentationRunnerArguments.pushgoQualitySessionBase64=$payload"
  verify_device_tests_executed "$device_test_started_at" "$repo_root/app/build/outputs/androidTest-results/connected"
  release_android_device_lock
  claims+=("Android selected App-owned UI journeys: $classes")
}

run_notification_permission_host_profile() {
  local device_serial="$1"
  local expected_selector="$2"
  local permission_output
  local permission_status
  set +e
  permission_output="$(ANDROID_SERIAL="$device_serial" "$repo_root/scripts/run_android_notification_permission_positive.sh")"
  permission_status=$?
  set -e
  printf '%s\n' "$permission_output"
  if printf '%s\n' "$permission_output" | grep -q '^cleanup_status=FAILED$'; then
    return 3
  fi
  case "$permission_status" in
    0) ;;
    2) return 2 ;;
    3) return 3 ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$permission_output" | python3 \
    "$repo_root/scripts/verify_android_host_execution_receipt.py" \
    --expected-selector "$expected_selector" || return 3
}

mark_planned_controlled_system_profile() {
  local profile="$1"
  local -a remaining=()
  local item
  for item in "${not_run[@]}"; do
    [[ "$item" == "$controlled_system_not_run" || "$item" == "$controlled_system_physical_not_run" || "$item" == "controlled-emulator Doze, system notification, and Private Service journeys" ]] \
      || remaining+=("$item")
  done
  not_run=("${remaining[@]}")
  if [[ "$profile" == "notification-permission" ]]; then
    not_run+=("controlled-emulator Doze, system notification, and Private Service journeys")
    not_run+=("$controlled_system_physical_not_run")
  else
    not_run+=("$controlled_system_physical_not_run")
  fi
}

run_system_notification_journeys() {
  local doctor_output
  local device_serial
  local permission_selector="io.ethan.pushgo.testing.QualityNotificationPermissionJourneyInstrumentedTest#enabledSystemDecisionRefreshesTheRealAppAndRemovesDisabledDeliveryState"
  selected_claims+=("Android notification permission, Doze recovery/snooze isolation, real process restart persistence with exact HTTPS browser handoff/return, critical alert playback, exact Message/Event/Thing cold-warm notification routes, and Private foreground Service system journeys: denied/settings/return plus restricted/system-unrestricted/return/session-snooze plus unread/read/no-PID/new-PID/exact-data/browser-url/detail-return plus durable inbound/audio/PendingIntent/read/dedupe and Settings/start/persist/stop")
  doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
  printf '%s\n' "$doctor_output"
  device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
  [[ -n "$device_serial" ]] || {
    echo "status=BLOCKED"
    echo "reason=quality_doctor_missing_device_serial"
    exit 2
  }
  run_notification_permission_host_profile "$device_serial" "$permission_selector"
  QUALITY_ANDROID_SKIP_INSTALL=1 ANDROID_SERIAL="$device_serial" \
    "$repo_root/scripts/run_android_doze_positive.sh"
  QUALITY_ANDROID_SKIP_INSTALL=1 ANDROID_SERIAL="$device_serial" \
    "$repo_root/scripts/run_android_process_restart_positive.sh"
  local device_test_started_at
  device_test_started_at="$(python3 -c 'import time; print(time.time())')"
  acquire_android_device_lock "$device_serial"
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" connectedDebugAndroidTest \
    --rerun-tasks \
    "-Pandroid.testInstrumentationRunnerArguments.class=$system_notification_classes"
  verify_device_tests_executed "$device_test_started_at" "$repo_root/app/build/outputs/androidTest-results/connected"
  release_android_device_lock
  claims+=("Android notification permission, Doze recovery/snooze isolation, real process restart persistence with exact HTTPS browser handoff/return, critical alert playback, exact Message/Event/Thing cold-warm notification routes, and Private foreground Service system journeys: denied/settings/return plus restricted/system-unrestricted/return/session-snooze plus unread/read/no-PID/new-PID/exact-data/browser-url/detail-return plus durable inbound/audio/PendingIntent/read/dedupe and Settings/start/persist/stop")
}

run_update_install_positive() {
  local doctor_output
  local device_serial
  selected_claims+=("Android positive update replaces the real package and preserves exact canonical data")
  doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
  printf '%s\n' "$doctor_output"
  device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
  [[ -n "$device_serial" ]] || {
    echo "status=BLOCKED"
    echo "reason=quality_doctor_missing_device_serial"
    exit 2
  }
  ANDROID_SERIAL="$device_serial" "$repo_root/scripts/run_android_update_install_positive.sh"
  claims+=("Android positive update replaces the real package and preserves exact canonical data")
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
  acquire_android_device_lock "$device_serial"
  [[ "$device_serial" == emulator-* ]] && [[ "$(adb -s "$device_serial" shell getprop ro.kernel.qemu | tr -d '\r')" == "1" ]] || {
    echo "status=BLOCKED"
    echo "reason=hosted_performance_lane_requires_controlled_emulator:$device_serial"
    exit 2
  }
  local device_test_started_at
  device_test_started_at="$(python3 -c 'import time; print(time.time())')"
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" \
    connectedDebugAndroidTest \
    --rerun-tasks \
    "-Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.testing.RuntimeDataLayerInstrumentedTest#realRoomDaoSearchAndPaging_optIn100000" \
    "-Pandroid.testInstrumentationRunnerArguments.pushgo.runtime.include100k=true" \
    2>&1 | tee "$device_log"
  verify_device_tests_executed "$device_test_started_at" "$repo_root/app/build/outputs/androidTest-results/connected"
  claims+=("Android 100k real Room correctness and provisional selected-emulator search ceiling")

  selected_claims+=("Release-like Macrobenchmark mechanics with exact 1k startup/detail product Oracle on controlled emulator")
  device_test_started_at="$(python3 -c 'import time; print(time.time())')"
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" \
    :macrobenchmark:connectedBenchmarkBenchmarkAndroidTest \
    --rerun-tasks \
    --console=plain \
    "-Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.macrobenchmark.PushGoMacrobenchmark" \
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.dryRunMode.enable=true" \
    "-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR" \
    "-Pandroid.testInstrumentationRunnerArguments.pushgo.maxStartupMs=20000" \
    "-Pandroid.testInstrumentationRunnerArguments.pushgo.maxDetailMs=10000"
  verify_device_tests_executed "$device_test_started_at" "$repo_root/macrobenchmark/build/outputs/androidTest-results/connected"
  claims+=("Release-like Macrobenchmark mechanics with exact 1k startup/detail product Oracle on controlled emulator")

  # This expected-failure control is test-system evidence, not a product claim. It deliberately
  # slows the same 1k App-owned load and must trip the existing startup-to-accurate-content budget.
  ANDROID_SERIAL="$device_serial" "$repo_root/scripts/run_android_performance_negative_control.sh"
  release_android_device_lock

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
  local device_test_started_at
  device_test_started_at="$(python3 -c 'import time; print(time.time())')"
  acquire_android_device_lock "$device_serial"
  ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" connectedDebugAndroidTest \
    --rerun-tasks \
    "-Pandroid.testInstrumentationRunnerArguments.class=io.ethan.pushgo.testing.QualityAccessibilityLocalizationJourneyInstrumentedTest"
  verify_device_tests_executed "$device_test_started_at" "$repo_root/app/build/outputs/androidTest-results/connected"
  release_android_device_lock
  claims+=("Android zh-CN large-font real message-detail and add-channel journey")
}

run_planned_device_evidence() {
  local plan_path="${QUALITY_IMPACT_PLAN:-}"
  local run_lines
  local profile
  local scopes
  local expected_count
  [[ -n "$plan_path" && -f "$plan_path" ]] || {
    echo "status=BLOCKED"
    echo "reason=planned_device_lane_requires_impact_plan"
    exit 2
  }
  if ! run_lines="$(python3 "$repo_root/scripts/quality_planned_device_runs.py" --plan "$plan_path")"; then
    echo "status=BLOCKED"
    echo "reason=invalid_structured_device_execution_plan"
    exit 2
  fi
  # Keep the execution plan on a dedicated descriptor. Gradle and host-profile helpers may read
  # stdin; sharing fd 0 with this loop silently dropped every profile after the first one.
  while IFS=$'\t' read -r profile scopes expected_count <&3; do
    [[ -n "$profile" && -n "$scopes" && -n "$expected_count" ]] || continue
    case "$profile" in
      generic)
        QUALITY_EXPECTED_ANDROID_TEST_SELECTORS="$scopes" \
          QUALITY_EXPECTED_ANDROID_TEST_COUNT="$expected_count" \
          run_device_classes "$scopes" "Android impact-selected generic instrumented behavior: $scopes"
        ;;
      app-owned)
        QUALITY_EXPECTED_ANDROID_TEST_SELECTORS="$scopes" \
          QUALITY_EXPECTED_ANDROID_TEST_COUNT="$expected_count" \
          run_quality_device_classes "$scopes"
        ;;
      accessibility)
        [[ "$scopes" == "io.ethan.pushgo.testing.QualityAccessibilityLocalizationJourneyInstrumentedTest#simplifiedChineseAtLargeFontCompletesMessageDetailAndAddChannelJourney" ]] || {
          echo "status=BLOCKED"
          echo "reason=unsupported_accessibility_planned_scopes:$scopes"
          exit 2
        }
        QUALITY_EXPECTED_ANDROID_TEST_SELECTORS="$scopes" \
          QUALITY_EXPECTED_ANDROID_TEST_COUNT="$expected_count" \
          run_accessibility_localization
        ;;
      notification-permission)
        local permission_scope="io.ethan.pushgo.testing.QualityNotificationPermissionJourneyInstrumentedTest#enabledSystemDecisionRefreshesTheRealAppAndRemovesDisabledDeliveryState"
        [[ "$scopes" == "$permission_scope" && "$expected_count" == "1" ]] || {
          echo "status=BLOCKED"
          echo "reason=unsupported_notification_permission_planned_scopes:$scopes"
          exit 2
        }
        local doctor_output
        local device_serial
        selected_claims+=("Android planned notification-permission host/system journey: $scopes")
        doctor_output="$("$repo_root/scripts/quality_doctor.sh")"
        printf '%s\n' "$doctor_output"
        device_serial="$(printf '%s\n' "$doctor_output" | awk -F= '$1 == "device_serial" { print $2; exit }')"
        [[ -n "$device_serial" ]] || {
          echo "status=BLOCKED"
          echo "reason=quality_doctor_missing_device_serial"
          exit 2
        }
        mark_planned_controlled_system_profile "notification-permission"
        run_notification_permission_host_profile "$device_serial" "$scopes"
        claims+=("Android planned notification-permission host/system journey: $scopes")
        ;;
      system-notification)
        mark_planned_controlled_system_profile "system-notification"
        QUALITY_EXPECTED_ANDROID_TEST_SELECTORS="$scopes" \
          QUALITY_EXPECTED_ANDROID_TEST_COUNT="$expected_count" \
          run_system_notification_journeys
        ;;
    esac
  done 3<<< "$run_lines"
}

case "$lane" in
  preparation)
    selected_claims+=("Android App-owned preparation rejects invalid sessions within 10 seconds and recovers to accurate functional empty state")
    "$repo_root/scripts/run_android_preparation_contract.sh"
    claims+=("Android App-owned preparation rejects invalid sessions within 10 seconds and recovers to accurate functional empty state")
    ;;
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
      device_test_started_at="$(python3 -c 'import time; print(time.time())')"
      acquire_android_device_lock "$device_serial"
      ANDROID_SERIAL="$device_serial" "$repo_root/gradlew" connectedDebugAndroidTest \
        --rerun-tasks \
        "-Pandroid.testInstrumentationRunnerArguments.class=$ANDROID_TEST_CLASS"
      verify_device_tests_executed "$device_test_started_at" "$repo_root/app/build/outputs/androidTest-results/connected"
      release_android_device_lock
      claims+=("Android focused device behavior: $ANDROID_TEST_CLASS")
    else
      focused_jvm_output=""
      focused_jvm_exit_code=0
      selected_claims+=("Android focused JVM behavior: $TEST_FILTER")
      "$repo_root/scripts/quality_doctor.sh" --allow-no-device
      host_test_started_at="$(python3 -c 'import time; print(time.time())')"
      set +e
      focused_jvm_output="$(
        "$repo_root/gradlew" testDebugUnitTest --rerun-tasks --tests "$TEST_FILTER" 2>&1
      )"
      focused_jvm_exit_code=$?
      set -e
      printf '%s\n' "$focused_jvm_output"
      if (( focused_jvm_exit_code != 0 )); then
        if grep -Fq "No tests found for given includes:" <<< "$focused_jvm_output"; then
          echo "status=FAILED_TEST_SYSTEM"
          echo "reason=focused_jvm_filter_matched_no_tests:$TEST_FILTER"
          exit 3
        fi
        exit "$focused_jvm_exit_code"
      fi
      verify_device_tests_executed \
        "$host_test_started_at" \
        "$repo_root/app/build/test-results/testDebugUnitTest"
      claims+=("Android focused JVM behavior: $TEST_FILTER")
    fi
    ;;
  planned-device)
    run_planned_device_evidence
    ;;
  performance)
    run_performance
    ;;
  update-install)
    run_update_install_positive
    ;;
  accessibility)
    run_accessibility_localization
    ;;
  pr)
    run_jvm_and_compile_device_tests
    ;;
  pr-ui)
    run_quality_device_classes "$pr_device_scopes"
    ;;
  device)
    run_jvm_and_compile_device_tests
    run_quality_device_classes "$positive_device_scopes"
    run_device_classes "$core_data_classes"
    ;;
  nightly)
    run_jvm_and_compile_device_tests
    run_quality_device_classes
    run_device_classes "$nightly_data_classes"
    run_system_notification_journeys
    run_accessibility_localization
    ;;
  release)
    run_jvm_and_compile_device_tests
    run_quality_device_classes
    run_device_classes "$nightly_data_classes"
    run_system_notification_journeys
    run_accessibility_localization
    run_performance
    run_update_install_positive
    ;;
  *)
    echo "status=BLOCKED"
    echo "reason=unsupported_lane:$lane"
    exit 2
    ;;
esac

echo "status=PASSED"
echo "lane=$lane"
