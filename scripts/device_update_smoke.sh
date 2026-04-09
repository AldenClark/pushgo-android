#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/device_update_smoke.sh <command> [options]

Commands:
  precheck        Print device/package/update prerequisites.
  open-settings   Open PushGo settings page on device.
  monitor         Stream update-related logs.
  snapshot        Print current package version and installer source.

Options:
  --serial <id>   adb device serial
  --package <id>  package name (default: io.ethan.pushgo)
EOF
}

if [[ $# -lt 1 || "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

command="$1"
shift

serial=""
package_name="io.ethan.pushgo"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      serial="${2:-}"
      shift 2
      ;;
    --package)
      package_name="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Error: unknown option $1" >&2
      exit 1
      ;;
  esac
done

adb_cmd=(adb)
if [[ -n "$serial" ]]; then
  adb_cmd+=( -s "$serial" )
fi

case "$command" in
  precheck)
    "${adb_cmd[@]}" get-state >/dev/null
    echo "[Device]"
    "${adb_cmd[@]}" shell getprop ro.product.model | tr -d '\r'
    "${adb_cmd[@]}" shell getprop ro.build.version.release | tr -d '\r'
    echo
    echo "[Package presence]"
    "${adb_cmd[@]}" shell pm path "$package_name" || true
    echo
    echo "[Unknown sources setting hint]"
    echo "Device path: Settings -> Apps -> Special app access -> Install unknown apps -> $package_name"
    echo
    echo "[Suggested test flow]"
    echo "1. Enable beta+stable in app settings."
    echo "2. Tap manual check."
    echo "3. Verify candidate version selection and actions (install/skip/remind later)."
    echo "4. Trigger install and verify PackageInstaller flow."
    ;;
  open-settings)
    "${adb_cmd[@]}" shell am start \
      -n "${package_name}/.MainActivity" \
      --ez extra_open_settings true
    ;;
  monitor)
    echo "Streaming logs (Ctrl+C to stop)..."
    "${adb_cmd[@]}" logcat -v time \
      | grep -E "UpdateManager|UpdateInstaller|UpdateInstallStatus|UpdateCheckScheduler|PackageInstaller" || true
    ;;
  snapshot)
    echo "[dumpsys package ${package_name}]"
    "${adb_cmd[@]}" shell dumpsys package "$package_name" \
      | grep -E "versionCode=|versionName=|installerPackageName=|firstInstallTime=|lastUpdateTime=" || true
    ;;
  *)
    usage
    exit 1
    ;;
esac
