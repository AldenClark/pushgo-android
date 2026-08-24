#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/publish_update_artifacts.sh <dist_dir> <remote_user_host> <remote_base_path> [stable|beta]

Example:
  scripts/publish_update_artifacts.sh dist deploy@update.pushgo.cn /var/www/update.pushgo.cn/android beta

Requirements:
  - ssh access configured (optionally via PUSHGO_UPDATE_DEPLOY_SSH_KEY_FILE)
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 3 ]]; then
  usage
  exit 1
fi

dist_dir="$1"
remote_user_host="$2"
remote_base_path="$3"
track="${4:-stable}"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ ! "$remote_user_host" =~ ^[A-Za-z0-9._@:-]+$ ]]; then
  echo "Error: remote host contains unsupported characters" >&2
  exit 1
fi
if [[ ! "$remote_base_path" =~ ^/[A-Za-z0-9._/-]+$ ]]; then
  echo "Error: remote base path must be an absolute path with safe characters" >&2
  exit 1
fi

if [[ "$track" != "stable" && "$track" != "beta" ]]; then
  echo "Error: track must be stable or beta, got: $track" >&2
  exit 1
fi

if [[ ! -d "$dist_dir" ]]; then
  echo "Error: dist directory not found: $dist_dir" >&2
  exit 1
fi

if [[ ! -f "${dist_dir%/}/deploy/update-server-manifest.json" ]]; then
  if [[ -x "${script_dir}/generate_update_deploy_config.sh" ]]; then
    "${script_dir}/generate_update_deploy_config.sh" "$dist_dir" --deploy-path "$remote_base_path"
  fi
fi

required_files=(
  "update-feed-v1.json"
)
for name in "${required_files[@]}"; do
  if [[ ! -f "${dist_dir%/}/$name" ]]; then
    echo "Error: required artifact missing: ${dist_dir%/}/$name" >&2
    exit 1
  fi
done

shopt -s nullglob
apk_files=( "${dist_dir%/}"/*.apk )
shopt -u nullglob
if [[ ${#apk_files[@]} -eq 0 ]]; then
  echo "Error: no APK artifacts found under ${dist_dir%/}" >&2
  exit 1
fi

if ! command -v ssh >/dev/null 2>&1; then
  echo "Error: ssh is required" >&2
  exit 1
fi

if ! command -v tar >/dev/null 2>&1; then
  echo "Error: tar is required" >&2
  exit 1
fi

ssh_opts=()
if [[ -n "${PUSHGO_UPDATE_DEPLOY_SSH_KEY_FILE:-}" ]]; then
  if [[ ! -f "${PUSHGO_UPDATE_DEPLOY_SSH_KEY_FILE}" ]]; then
    echo "Error: PUSHGO_UPDATE_DEPLOY_SSH_KEY_FILE not found: ${PUSHGO_UPDATE_DEPLOY_SSH_KEY_FILE}" >&2
    exit 1
  fi
  ssh_opts=(-i "${PUSHGO_UPDATE_DEPLOY_SSH_KEY_FILE}" -o IdentitiesOnly=yes)
fi

version_name="$(awk -F= '$1=="versionName"{print $2}' "${dist_dir%/}/BUILD_INFO.txt" | tr -d '\r' | tail -n1)"
if [[ -z "$version_name" ]]; then
  echo "Error: unable to parse versionName from BUILD_INFO.txt" >&2
  exit 1
fi
if [[ ! "$version_name" =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-beta\.[1-9][0-9]*)?$ ]]; then
  echo "Error: unsupported versionName in BUILD_INFO.txt: $version_name" >&2
  exit 1
fi

release_dir="${remote_base_path%/}/${track}/${version_name}"
active_feed_file="${remote_base_path%/}/update-feed-v1.json"

retry_ssh() {
  local cmd="$1"
  local attempt
  for attempt in 1 2 3; do
    if ssh "${ssh_opts[@]}" "$remote_user_host" "$cmd"; then
      return 0
    fi
    if [[ "$attempt" -lt 3 ]]; then
      echo "SSH command failed (attempt ${attempt}/3), retrying..." >&2
      sleep "$attempt"
    fi
  done
  return 1
}

retry_upload() {
  local attempt
  local apk_names=()
  local apk_path
  for apk_path in "${apk_files[@]}"; do
    apk_names+=( "$(basename "$apk_path")" )
  done
  for attempt in 1 2 3; do
    if tar -C "${dist_dir%/}" -cf - "${apk_names[@]}" \
      | ssh "${ssh_opts[@]}" "$remote_user_host" "tar -xf - -C '${release_dir}'"; then
      return 0
    fi
    if [[ "$attempt" -lt 3 ]]; then
      echo "Upload failed (attempt ${attempt}/3), retrying..." >&2
      sleep "$attempt"
    fi
  done
  return 1
}

retry_upload_active_files() {
  local attempt
  local backup_tmp
  local expected_sha256
  local previous_tmp
  local remote_tmp
  expected_sha256="$(sha256sum "${dist_dir%/}/update-feed-v1.json" | awk '{print $1}')"
  for attempt in 1 2 3; do
    remote_tmp="${active_feed_file}.incoming.${version_name}.${attempt}"
    backup_tmp="${active_feed_file}.backup.incoming.${version_name}.${attempt}"
    previous_tmp="${active_feed_file}.previous.incoming.${version_name}.${attempt}"
    if ssh "${ssh_opts[@]}" "$remote_user_host" \
        "umask 077; cat > '${remote_tmp}' && test \"\$(sha256sum '${remote_tmp}' | awk '{print \$1}')\" = '${expected_sha256}' && if test -f '${active_feed_file}'; then current_record=\"\$(sha256sum '${active_feed_file}')\" && current_sha=\"\${current_record%% *}\" && test \"\${#current_sha}\" -eq 64 && case \"\$current_sha\" in *[!0-9a-fA-F]*) false ;; *) true ;; esac; else current_sha=''; fi && if test \"\$current_sha\" = '${expected_sha256}'; then rm -f '${remote_tmp}'; else if test -n \"\$current_sha\"; then backup_file=\"${active_feed_file}.backup.\$current_sha\" && if test -f \"\$backup_file\"; then test \"\$(sha256sum \"\$backup_file\" | awk '{print \$1}')\" = \"\$current_sha\"; else cp -f '${active_feed_file}' '${backup_tmp}' && test \"\$(sha256sum '${backup_tmp}' | awk '{print \$1}')\" = \"\$current_sha\" && mv -f '${backup_tmp}' \"\$backup_file\"; fi && cp -f '${active_feed_file}' '${previous_tmp}' && test \"\$(sha256sum '${previous_tmp}' | awk '{print \$1}')\" = \"\$current_sha\" && mv -f '${previous_tmp}' '${active_feed_file}.previous'; fi && mv -f '${remote_tmp}' '${active_feed_file}'; fi" \
        < "${dist_dir%/}/update-feed-v1.json"; then
      return 0
    fi
    ssh "${ssh_opts[@]}" "$remote_user_host" \
      "rm -f '${remote_tmp}' '${backup_tmp}' '${previous_tmp}'" >/dev/null 2>&1 || true
    if [[ "$attempt" -lt 3 ]]; then
      echo "Active file upload failed (attempt ${attempt}/3), retrying..." >&2
      sleep "$attempt"
    fi
  done
  return 1
}

if ! retry_ssh "mkdir -p '$release_dir' '${remote_base_path%/}'"; then
  echo "Error: failed to create remote directories after 3 attempts" >&2
  exit 1
fi

if ! retry_ssh "rm -rf '${release_dir}'/*"; then
  echo "Error: failed to clean remote release directory after 3 attempts" >&2
  exit 1
fi

if ! retry_upload; then
  echo "Error: failed to upload APK artifacts after 3 attempts" >&2
  exit 1
fi

if ! retry_upload_active_files; then
  echo "Error: failed to upload active feed files after 3 attempts" >&2
  exit 1
fi

active_feed_sha256="$(sha256sum "${dist_dir%/}/update-feed-v1.json" | awk '{print $1}')"
if ! retry_ssh "test -f '${active_feed_file}' && test \"\$(sha256sum '${active_feed_file}' | awk '{print \$1}')\" = '${active_feed_sha256}'"; then
  echo "Error: active update feed failed post-switch integrity verification" >&2
  exit 1
fi

echo "Published APK artifacts to ${remote_user_host}:${release_dir} and refreshed active feed ${active_feed_file}"
