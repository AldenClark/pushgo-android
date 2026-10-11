#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
lane="${1:-}"
case "$lane" in
  pr-ui|planned-device|focused|device|nightly|release-smoke|accessibility|performance|release) ;;
  *)
    echo "status=BLOCKED"
    echo "reason=unsupported_device_prebuild_lane:${lane:-missing}"
    exit 2
    ;;
esac

tasks=(
  :app:assembleDebug
  :app:assembleDebugAndroidTest
)
if [[ "$lane" == "performance" || "$lane" == "release" ]]; then
  tasks+=(
    :app:assembleBenchmark
    :macrobenchmark:assembleBenchmarkBenchmark
  )
fi

echo "device_prebuild_lane=$lane"
printf 'device_prebuild_task=%s\n' "${tasks[@]}"
"$repo_root/gradlew" "${tasks[@]}" --console=plain
