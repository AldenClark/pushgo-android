#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
results_root="$repo_root/build/quality-results"
impact_file="$results_root/android-impact-plan.json"
phase="${QUALITY_IMPACT_PHASE:-full}"
mkdir -p "$results_root"

python3 -m unittest discover -s "$repo_root/scripts/tests" -p 'test_quality_impact.py'
python3 "$repo_root/scripts/quality_impact.py" \
  --output "$impact_file" \
  --check \
  "$@"

recommended_lane="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["recommended_lane"])' "$impact_file")"
if [[ "${QUALITY_IMPACT_PLAN_ONLY:-0}" == "1" ]]; then
  echo "status=NOT_RUN"
  echo "reason=impact_plan_only"
  exit 0
fi

case "$phase" in
  full)
    lane="$recommended_lane"
    ;;
  host)
    if [[ "$recommended_lane" == "not-run" ]]; then lane="not-run"; else lane="pr"; fi
    ;;
  device)
    case "$recommended_lane" in
      not-run|pr) lane="not-run" ;;
      *) lane="$recommended_lane" ;;
    esac
    ;;
  *)
    echo "status=BLOCKED"
    echo "reason=unsupported_quality_impact_phase:$phase"
    exit 2
    ;;
esac

if [[ "$lane" == "not-run" ]]; then
  python3 "$repo_root/scripts/quality_result.py" \
    --output "$results_root/android-changed-$phase-summary.json" \
    --platform android \
    --lane "changed-$phase" \
    --product-status NOT_RUN \
    --test-system-status PASSED \
    --not-run "No Android product evidence is required in the $phase phase for this change." \
    --reason "recommended_lane=$recommended_lane"
  echo "status=NOT_RUN"
  echo "reason=no_product_capability_for_phase:$phase"
  exit 0
fi

echo "executing_recommended_lane=$lane"
exec "$repo_root/scripts/quality_test.sh" "$lane"
