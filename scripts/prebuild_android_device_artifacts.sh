#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
lane="${1:-}"
record_prebuild_failure() {
  local status=$?
  if (( status != 0 )); then
    # Keep the first preparation failure visible even though no native product
    # runner has started and therefore no native summary exists yet.
    python3 "$repo_root/scripts/quality_result.py" \
      --output "${QUALITY_RESULTS_ROOT:-$repo_root/build/quality-results}/android-prebuild-summary.json" \
      --platform android --lane "prebuild-${lane:-missing}" \
      --product-status NOT_RUN --test-system-status BLOCKED \
      --reason "Device artifact preparation failed (exit $status); product execution did not start." || true
  fi
  exit "$status"
}
trap record_prebuild_failure EXIT
case "$lane" in
  pr-ui|planned-device|focused|process-restart|device|nightly|release-smoke|accessibility|performance|release|update-install) ;;
  *)
    echo "status=BLOCKED"
    echo "reason=unsupported_device_prebuild_lane:${lane:-missing}"
    exit 2
    ;;
esac

tasks=(
  :app:assembleDebug
)
gradle_options=(--console=plain)
if [[ "$lane" == "update-install" ]]; then
  # The host update journey uses this package, not the debug app or test APK.
  tasks=(:app:assembleBenchmark)
  gradle_options+=(-Ppushgo.enableAbiSplits=false)
elif [[ "$lane" != "process-restart" ]]; then
  tasks+=(:app:assembleDebugAndroidTest)
fi
if [[ "$lane" == "performance" || "$lane" == "release" ]]; then
  tasks+=(
    :app:assembleBenchmark
    :macrobenchmark:assembleBenchmarkBenchmark
  )
fi

echo "device_prebuild_lane=$lane"
printf 'device_prebuild_task=%s\n' "${tasks[@]}"
"$repo_root/gradlew" "${tasks[@]}" "${gradle_options[@]}"
