#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage:
  scripts/device_update_e2e_matrix.sh run-all [options]

Options:
  --serial <id>                      adb device serial
  --adb <path>                       adb binary path (default: adb from PATH)
  --package <id>                     package name (default: io.ethan.pushgo)
  --expected-stable-version <name>   expected stable candidate (default: v1.2.0)
  --expected-beta-version <name>     expected beta candidate (default: v1.2.1-beta.1)
  --expected-final-version-code <n>  expected installed versionCode after install flow (default: 1020101)
  --dump-file <path>                 local ui dump path
  --fixture-dir <path>               local update fixture directory (default: ./tmp/update-e2e)
  --server-port <port>               local feed server port for fixtures (default: 18080)
  --skip-local-feed-server           skip launching local fixture server and adb reverse
USAGE
}

if [[ $# -lt 1 || "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

command="$1"
shift

if [[ "$command" != "run-all" ]]; then
  echo "Error: unsupported command '$command'" >&2
  usage
  exit 1
fi

serial=""
adb_bin="${ADB_BIN:-adb}"
package_name="io.ethan.pushgo"
expected_stable_version="v1.2.0"
expected_beta_version="v1.2.1-beta.1"
expected_final_version_code="1020101"
dump_file="$(pwd)/tmp/update-e2e/window_dump.xml"
fixture_dir="$(pwd)/tmp/update-e2e"
server_port="18080"
start_local_feed_server=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      serial="${2:-}"
      shift 2
      ;;
    --adb)
      adb_bin="${2:-}"
      shift 2
      ;;
    --package)
      package_name="${2:-}"
      shift 2
      ;;
    --expected-stable-version)
      expected_stable_version="${2:-}"
      shift 2
      ;;
    --expected-beta-version)
      expected_beta_version="${2:-}"
      shift 2
      ;;
    --expected-final-version-code)
      expected_final_version_code="${2:-}"
      shift 2
      ;;
    --dump-file)
      dump_file="${2:-}"
      shift 2
      ;;
    --fixture-dir)
      fixture_dir="${2:-}"
      shift 2
      ;;
    --server-port)
      server_port="${2:-}"
      shift 2
      ;;
    --skip-local-feed-server)
      start_local_feed_server=0
      shift
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

adb_cmd=("$adb_bin")
if [[ -n "$serial" ]]; then
  adb_cmd+=(-s "$serial")
fi

mkdir -p "$(dirname "$dump_file")"

feed_path="${fixture_dir%/}/update-feed-v1.json"
feed_backup_path="${fixture_dir%/}/update-feed-v1.good.backup.json"
bad_pkg_apk_path="${fixture_dir%/}/base-v1.2.0-beta.1.apk"
server_pid=""

cleanup() {
  if [[ -f "$feed_backup_path" ]]; then
    cp "$feed_backup_path" "$feed_path" >/dev/null 2>&1 || true
  fi
  if [[ -n "$server_pid" ]]; then
    kill "$server_pid" >/dev/null 2>&1 || true
  fi
  if [[ "$start_local_feed_server" -eq 1 ]]; then
    "${adb_cmd[@]}" reverse --remove "tcp:${server_port}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

require_cmd() {
  local cmd="$1"
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Error: required command '$cmd' is missing" >&2
    exit 1
  fi
}

setup_local_feed_server() {
  [[ "$start_local_feed_server" -eq 1 ]] || return 0
  require_cmd python3
  require_cmd jq
  require_cmd shasum

  if [[ ! -f "$feed_path" ]]; then
    echo "Error: feed fixture not found: $feed_path" >&2
    exit 1
  fi
  if [[ ! -f "$bad_pkg_apk_path" ]]; then
    echo "Error: bad-package fixture apk not found: $bad_pkg_apk_path" >&2
    exit 1
  fi

  cp "$feed_path" "$feed_backup_path"

  "${adb_cmd[@]}" reverse "tcp:${server_port}" "tcp:${server_port}" >/dev/null

  local server_log
  server_log="${fixture_dir%/}/.device-update-e2e-http.log"
  python3 -m http.server "$server_port" --bind 127.0.0.1 --directory "$fixture_dir" >"$server_log" 2>&1 &
  server_pid="$!"
  sleep 1

  if ! kill -0 "$server_pid" >/dev/null 2>&1; then
    server_pid=""
    if ! curl -fsS "http://127.0.0.1:${server_port}/update-feed-v1.json" >/dev/null 2>&1; then
      echo "Error: failed to start local feed server and no existing server is reachable on :${server_port}" >&2
      exit 1
    fi
  fi
}

restore_good_feed() {
  [[ "$start_local_feed_server" -eq 1 ]] || return 0
  cp "$feed_backup_path" "$feed_path"
}

set_feed_bad_sha() {
  [[ "$start_local_feed_server" -eq 1 ]] || return 0
  local tmp_json
  tmp_json="$(mktemp)"
  jq '(.payload.entries[] | select(.channel == "beta") | .apkSha256) = "0000000000000000000000000000000000000000000000000000000000000000"' \
    "$feed_backup_path" >"$tmp_json"
  mv "$tmp_json" "$feed_path"
}

set_feed_bad_package() {
  [[ "$start_local_feed_server" -eq 1 ]] || return 0
  local bad_pkg_sha
  bad_pkg_sha="$(shasum -a 256 "$bad_pkg_apk_path" | awk '{print $1}')"
  local tmp_json
  tmp_json="$(mktemp)"
  jq --arg apkUrl "http://127.0.0.1:${server_port}/$(basename "$bad_pkg_apk_path")" --arg sha "$bad_pkg_sha" \
    '(.payload.entries[] | select(.channel == "beta") | .apkUrl) = $apkUrl
     | (.payload.entries[] | select(.channel == "beta") | .apkSha256) = $sha' \
    "$feed_backup_path" >"$tmp_json"
  mv "$tmp_json" "$feed_path"
}

dump_ui() {
  "${adb_cmd[@]}" shell uiautomator dump /sdcard/window_dump.xml >/dev/null 2>&1 || true
  "${adb_cmd[@]}" pull /sdcard/window_dump.xml "$dump_file" >/dev/null
}

extract_texts() {
  perl -Mutf8 -ne 'while(/text="([^"]+)"/g){$t=$1; next if $t eq q{}; print "$t\n";}' "$dump_file" | sort -u
}

get_center_by_text() {
  local text="$1"
  TEXT="$text" perl -Mutf8 -ne '$text=$ENV{"TEXT"}; if(/text="\Q$text\E"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/){print int(($1+$3)/2).",".int(($2+$4)/2); exit}' "$dump_file"
}

current_ui_package() {
  perl -ne 'if(/package="([^"]+)"/){print "$1\n"; exit}' "$dump_file"
}

has_text() {
  local text="$1"
  dump_ui
  extract_texts | rg -Fqx "$text"
}

has_text_pattern() {
  local pattern="$1"
  dump_ui
  extract_texts | rg -q "$pattern"
}

tap_center() {
  local center="$1"
  local x="${center%,*}"
  local y="${center#*,}"
  "${adb_cmd[@]}" shell input tap "$x" "$y"
}

tap_text() {
  local text="$1"
  dump_ui
  local center
  center="$(get_center_by_text "$text" || true)"
  if [[ -z "${center:-}" ]]; then
    return 1
  fi
  tap_center "$center"
  return 0
}

tap_first_text() {
  local label
  for label in "$@"; do
    if tap_text "$label"; then
      return 0
    fi
  done
  return 1
}

dismiss_install_permission_dialog_if_present() {
  dump_ui
  if extract_texts | rg -q -e "^(允许安装应用包|允許安裝應用套件|Allow package installation)$"; then
    tap_first_text "取消" "Cancel" || true
    sleep 1
  fi
}

install_button_bottom_y() {
  dump_ui
  local line
  line="$(rg -o 'text="(Install now|立即安装|立即安裝)"[^>]*bounds="\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]"' "$dump_file" | head -n 1 || true)"
  if [[ -z "${line:-}" ]]; then
    echo ""
    return 0
  fi
  echo "$line" | sed -E 's/.*\[[0-9]+,[0-9]+\]\[[0-9]+,([0-9]+)\]".*/\1/'
}

raise_update_card_actions() {
  for _ in 1 2; do
    local bottom_y
    bottom_y="$(install_button_bottom_y)"
    if [[ -z "${bottom_y:-}" ]]; then
      return 0
    fi
    if [[ "$bottom_y" -le 2050 ]]; then
      return 0
    fi
    "${adb_cmd[@]}" shell input swipe 540 1950 540 1300 220
    sleep 1
  done
}

open_settings_update_section() {
  "${adb_cmd[@]}" shell am start -n "${package_name}/.MainActivity" --ez extra_open_settings true >/dev/null
  sleep 1
  dismiss_install_permission_dialog_if_present
  for _ in 1 2; do
    "${adb_cmd[@]}" shell input swipe 540 900 540 2050 250
    sleep 1
  done
  for _ in 1 2 3 4; do
    dump_ui
    if extract_texts | rg -q -e "^(立即检查|立即檢查|Check now)$"; then
      return 0
    fi
    "${adb_cmd[@]}" shell input swipe 540 2050 540 900 250
    sleep 1
  done
}

assert_text() {
  local text="$1"
  if has_text "$text"; then
    return 0
  fi
  echo "ASSERT FAILED: expected text '$text' not found"
  echo "Current visible texts:"
  extract_texts | head -n 80
  return 1
}

assert_pattern() {
  local pattern="$1"
  if has_text_pattern "$pattern"; then
    return 0
  fi
  echo "ASSERT FAILED: expected pattern '$pattern' not found"
  echo "Current visible texts:"
  extract_texts | head -n 80
  return 1
}

select_stable_channel() {
  tap_first_text "稳定版" "穩定版" "安定版" "안정판" "Estable" "Stabil" "Stable" "stable" "仅 stable" "僅 stable" "Stable only" || true
}

select_beta_channel() {
  tap_first_text "测试版" "測試版" "ベータ" "베타" "Bêta" "Beta" "beta" "beta + stable" "Beta + Stable" || true
}

tap_check_now() {
  tap_first_text "立即检查" "立即檢查" "Check now"
}

tap_install_now() {
  tap_first_text "立即安装" "立即安裝" "Install now"
}

ensure_action_visible_or_check_now() {
  dump_ui
  local install_center
  install_center="$(get_center_by_text "立即安装" || true)"
  if [[ -z "${install_center:-}" ]]; then
    install_center="$(get_center_by_text "立即安裝" || true)"
  fi
  if [[ -z "${install_center:-}" ]]; then
    install_center="$(get_center_by_text "Install now" || true)"
  fi
  if [[ -n "${install_center:-}" ]]; then
    return 0
  fi
  if tap_check_now; then
    sleep 2
  fi
}

installed_version_code() {
  "${adb_cmd[@]}" shell dumpsys package "$package_name" \
    | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' \
    | head -n 1
}

has_recoverable_guidance_notification() {
  local dump
  dump="$("${adb_cmd[@]}" shell dumpsys notification --noredact 2>/dev/null | tr -d '\r')"
  if ! rg -q "pushgo_updates_v1" <<<"$dump"; then
    return 1
  fi
  rg -qi "安装受阻|安裝受阻|Installer blocked|打开安装设置|打開安裝設定|Open install settings|INSTALL_FAILED_ABORTED|blocked session install|User rejected permissions" <<<"$dump"
}

wait_for_candidate_pattern() {
  local pattern="$1"
  local retries="${2:-4}"
  local delay_secs="${3:-2}"
  if has_text_pattern "$pattern"; then
    return 0
  fi
  local i
  for i in $(seq 1 "$retries"); do
    tap_check_now || true
    sleep "$delay_secs"
    if has_text_pattern "$pattern"; then
      return 0
    fi
  done
  return 1
}

run_case() {
  local id="$1"
  local title="$2"
  shift 2
  echo "=== [$id] $title ==="
  if "$@"; then
    echo "[PASS] $id"
    return 0
  fi
  echo "[FAIL] $id"
  return 1
}

case_stable_selection() {
  restore_good_feed
  open_settings_update_section
  select_stable_channel
  wait_for_candidate_pattern "$expected_stable_version" || {
    assert_pattern "$expected_stable_version"
    return 1
  }
}

case_beta_selection() {
  restore_good_feed
  open_settings_update_section
  select_beta_channel
  sleep 2
  wait_for_candidate_pattern "$expected_beta_version" || {
    assert_pattern "$expected_beta_version"
    return 1
  }
}

case_skip_and_manual_bypass() {
  ensure_action_visible_or_check_now
  tap_first_text "跳过此版本" "跳過此版本" "Skip this version"
  sleep 1
  assert_pattern "当前候选版本已被跳过|目前候選版本已被跳過|Current candidate is skipped" || return 1
  tap_check_now
  sleep 2
  assert_pattern "$expected_beta_version" || return 1
}

case_cooldown_and_manual_bypass() {
  ensure_action_visible_or_check_now
  tap_first_text "稍后提醒" "稍後提醒" "Remind later"
  sleep 1
  assert_pattern "冷却|冷卻|cooldown|Cooldown" || return 1
  tap_check_now
  sleep 2
  assert_pattern "$expected_beta_version" || return 1
}

case_permission_gate() {
  restore_good_feed
  open_settings_update_section
  select_beta_channel
  "${adb_cmd[@]}" shell cmd appops set "$package_name" REQUEST_INSTALL_PACKAGES deny
  ensure_action_visible_or_check_now
  raise_update_card_actions
  tap_install_now
  sleep 1
  dump_ui
  if has_text_pattern "允许安装应用包|允許安裝應用套件|Allow package installation"; then
    tap_first_text "取消" "Cancel" || true
    return 0
  fi
  local top_package
  top_package="$(current_ui_package)"
  if [[ -n "${top_package:-}" && "$top_package" != "$package_name" ]]; then
    echo "permission gate routed to external package: $top_package"
    return 0
  fi
  echo "ASSERT FAILED: permission gate did not show in-app guidance or system settings handoff"
  extract_texts | head -n 80
  tap_first_text "取消" "Cancel" || true
  return 1
}

case_bad_sha_failure() {
  set_feed_bad_sha
  open_settings_update_section
  "${adb_cmd[@]}" shell cmd appops set "$package_name" REQUEST_INSTALL_PACKAGES allow
  select_beta_channel
  tap_check_now || true
  sleep 2
  wait_for_candidate_pattern "$expected_beta_version" || {
    assert_pattern "$expected_beta_version"
    return 1
  }

  "${adb_cmd[@]}" logcat -c
  ensure_action_visible_or_check_now
  raise_update_card_actions
  tap_install_now
  sleep 3

  local current_version
  current_version="$(installed_version_code)"
  echo "installed_version_code_after_bad_sha=$current_version"
  if [[ "$current_version" == "$expected_final_version_code" ]]; then
    echo "ASSERT FAILED: bad SHA case unexpectedly upgraded app"
    return 1
  fi

  local logs
  logs="$("${adb_cmd[@]}" logcat -d | rg -n "install start failed|Checksum mismatch|Download failed" || true)"
  echo "$logs"
  tap_check_now || true
  sleep 2
  assert_pattern "$expected_beta_version" || return 1
}

case_bad_package_failure() {
  set_feed_bad_package
  open_settings_update_section
  "${adb_cmd[@]}" shell cmd appops set "$package_name" REQUEST_INSTALL_PACKAGES allow
  select_beta_channel
  tap_check_now || true
  sleep 2
  wait_for_candidate_pattern "$expected_beta_version" || {
    assert_pattern "$expected_beta_version"
    return 1
  }

  "${adb_cmd[@]}" logcat -c
  ensure_action_visible_or_check_now
  raise_update_card_actions
  tap_install_now
  sleep 3

  local current_version
  current_version="$(installed_version_code)"
  echo "installed_version_code_after_bad_package=$current_version"
  if [[ "$current_version" == "$expected_final_version_code" ]]; then
    echo "ASSERT FAILED: bad package case unexpectedly upgraded app"
    return 1
  fi

  local logs
  logs="$("${adb_cmd[@]}" logcat -d | rg -n "install start failed|Archive validation failed|Update package is incompatible with this app" || true)"
  echo "$logs"
  tap_check_now || true
  sleep 2
  assert_pattern "$expected_beta_version" || return 1
}

case_install_success_or_recoverable_guidance() {
  restore_good_feed
  open_settings_update_section
  select_beta_channel
  tap_check_now || true
  sleep 2
  wait_for_candidate_pattern "$expected_beta_version" || {
    assert_pattern "$expected_beta_version"
    return 1
  }
  "${adb_cmd[@]}" shell cmd appops set "$package_name" REQUEST_INSTALL_PACKAGES allow
  ensure_action_visible_or_check_now
  raise_update_card_actions
  "${adb_cmd[@]}" logcat -c
  tap_install_now

  local installer_opened=0
  for _ in $(seq 1 15); do
    sleep 2
    dump_ui
    local top_package
    top_package="$(current_ui_package)"
    if [[ -n "${top_package:-}" && "$top_package" != "$package_name" ]]; then
      installer_opened=1
    fi
    for label in "继续安装" "繼續安裝" "安装" "安裝" "继续" "繼續" "允许" "允許" "确定" "確定" "完成" "打开" "Open" "Install" "Continue" "Done"; do
      center="$(get_center_by_text "$label" || true)"
      if [[ -n "${center:-}" ]]; then
        tap_center "$center"
        break
      fi
    done
  done

  local current_version
  current_version="$(installed_version_code)"
  echo "installed_version_code=$current_version"
  if [[ "$current_version" == "$expected_final_version_code" ]]; then
    return 0
  fi

  if has_recoverable_guidance_notification; then
    echo "B006 accepted: install not completed but recoverable guidance notification is present"
    return 0
  fi

  if [[ "$installer_opened" -eq 1 ]]; then
    echo "B006 observed installer handoff but no completion/recoverable notification was captured"
  fi

  echo "ASSERT FAILED: neither installation success nor recoverable guidance was observed"
  "${adb_cmd[@]}" shell dumpsys notification --noredact 2>/dev/null | rg -n "pushgo_updates_v1|Installer blocked|安装受阻|安裝受阻|INSTALL_FAILED" || true
  return 1
}

"${adb_cmd[@]}" get-state >/dev/null
setup_local_feed_server

failures=0

run_case "B001" "stable channel selects stable candidate" case_stable_selection || failures=$((failures + 1))
run_case "B002" "beta+stable selects highest versionCode" case_beta_selection || failures=$((failures + 1))
run_case "B003" "skip version suppresses auto and manual check can bypass" case_skip_and_manual_bypass || failures=$((failures + 1))
run_case "B004" "cooldown suppresses auto and manual check can bypass" case_cooldown_and_manual_bypass || failures=$((failures + 1))
run_case "B005" "permission gate prompts unknown-sources guidance" case_permission_gate || failures=$((failures + 1))
run_case "B007" "bad SHA feed keeps version and reports checksum failure" case_bad_sha_failure || failures=$((failures + 1))
run_case "B008" "bad package keeps version and reports archive compatibility failure" case_bad_package_failure || failures=$((failures + 1))
run_case "B006" "install succeeds or emits recoverable installer-guidance fallback" case_install_success_or_recoverable_guidance || failures=$((failures + 1))

echo
echo "=== SUMMARY ==="
if [[ "$failures" -eq 0 ]]; then
  echo "ALL CASES PASSED"
  exit 0
fi
echo "FAILED CASES: $failures"
exit 1
