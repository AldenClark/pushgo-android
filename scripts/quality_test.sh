#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
lane="${1:-pr}"

run_jvm_and_compile_device_tests() {
  "$repo_root/scripts/quality_doctor.sh" --allow-no-device
  "$repo_root/gradlew" \
    testDebugUnitTest \
    compileDebugAndroidTestKotlin \
    assembleDebug
}

# These classes prove the highest-value product outcomes and storage boundaries.
# Keeping the routine device lane curated prevents diagnostics and rare platform
# permutations from consuming the feedback budget on every run.
quality_device_classes="io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest"
core_data_classes="io.ethan.pushgo.data.db.PushGoDatabaseMigrationDeviceTest,io.ethan.pushgo.data.PendingLocalDeletionRoomDeviceTest,io.ethan.pushgo.data.ProviderAckScopeDeviceTest"
nightly_data_classes="$core_data_classes,io.ethan.pushgo.testing.RuntimeDataLayerInstrumentedTest,io.ethan.pushgo.testing.RuntimeChannelSwitchInstrumentedTest,io.ethan.pushgo.testing.RuntimePrivateChannelStateFlowInstrumentedTest,io.ethan.pushgo.ui.PendingLocalDeletionWorkBoundaryDeviceTest"

run_device_classes() {
  local classes="$1"
  "$repo_root/scripts/quality_doctor.sh"
  "$repo_root/gradlew" connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$classes"
}

run_quality_device_classes() {
  local session_id="android-lane-$(date +%s)"
  local payload
  payload="$(printf '{"schema_version":1,"session_id":"%s","fixture":"empty.clean","faults":{}}' "$session_id" | base64 | tr -d '\n')"
  "$repo_root/scripts/quality_doctor.sh"
  "$repo_root/gradlew" connectedDebugAndroidTest \
    "-Pandroid.testInstrumentationRunnerArguments.class=$quality_device_classes" \
    "-Pandroid.testInstrumentationRunnerArguments.pushgoQualitySessionBase64=$payload"
}

case "$lane" in
  focused)
    [[ -n "${TEST_FILTER:-}" ]] || {
      echo "status=BLOCKED"
      echo "reason=focused_lane_requires_TEST_FILTER"
      exit 2
    }
    "$repo_root/scripts/quality_doctor.sh" --allow-no-device
    "$repo_root/gradlew" testDebugUnitTest --tests "$TEST_FILTER"
    ;;
  pr)
    run_jvm_and_compile_device_tests
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
