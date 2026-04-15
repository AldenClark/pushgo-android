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
  "SHA256SUMS.txt"
)
for name in "${required_files[@]}"; do
  if [[ ! -f "${dist_dir%/}/$name" ]]; then
    echo "Error: required artifact missing: ${dist_dir%/}/$name" >&2
    exit 1
  fi
done

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

release_dir="${remote_base_path%/}/${track}/${version_name}"
active_feed_file="${remote_base_path%/}/update-feed-v1.json"
active_sha_file="${remote_base_path%/}/SHA256SUMS.txt"

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
  for attempt in 1 2 3; do
    if tar -C "${dist_dir%/}" -cf - . \
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

if ! retry_ssh "mkdir -p '$release_dir' '${remote_base_path%/}'"; then
  echo "Error: failed to create remote directories after 3 attempts" >&2
  exit 1
fi

if ! retry_ssh "rm -rf '${release_dir}'/*"; then
  echo "Error: failed to clean remote release directory after 3 attempts" >&2
  exit 1
fi

if ! retry_upload; then
  echo "Error: failed to upload artifacts after 3 attempts" >&2
  exit 1
fi

if ! retry_ssh "cp '${release_dir}/update-feed-v1.json' '${active_feed_file}' && \
   cp '${release_dir}/SHA256SUMS.txt' '${active_sha_file}'"; then
  echo "Error: failed to refresh active feed files after 3 attempts" >&2
  exit 1
fi

echo "Published update artifacts to ${remote_user_host}:${release_dir} and refreshed active feed ${active_feed_file}"
