#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/generate_update_feed.sh <stable|beta> <dist_dir> [options]

Options:
  --tag <vX.Y.Z or vX.Y.Z-beta.N>      Release tag (required)
  --repo <owner/repo>                   Repository slug for release asset URL (required)
  --existing-feed <url-or-path>         Existing feed to merge entries from
  --output <path>                       Output signed feed JSON (default: <dist_dir>/update-feed-v1.json)
  --private-key-file <path>             Ed25519 private key (PKCS8 PEM) for payload signature
  -h, --help                            Show this help
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 2 ]]; then
  usage
  exit 1
fi

track="$1"
dist_dir="$2"
shift 2

if [[ "$track" != "stable" && "$track" != "beta" ]]; then
  echo "Error: track must be stable or beta" >&2
  exit 1
fi

if [[ ! -d "$dist_dir" ]]; then
  echo "Error: dist directory not found: $dist_dir" >&2
  exit 1
fi

tag=""
repo=""
existing_feed=""
output_path="${dist_dir%/}/update-feed-v1.json"
private_key_file=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      tag="${2:-}"
      shift 2
      ;;
    --repo)
      repo="${2:-}"
      shift 2
      ;;
    --existing-feed)
      existing_feed="${2:-}"
      shift 2
      ;;
    --output)
      output_path="${2:-}"
      shift 2
      ;;
    --private-key-file)
      private_key_file="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Error: unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if [[ -z "$tag" || -z "$repo" ]]; then
  echo "Error: --tag and --repo are required" >&2
  exit 1
fi

if [[ ! -f "${dist_dir%/}/BUILD_INFO.txt" ]]; then
  echo "Error: BUILD_INFO.txt not found under $dist_dir" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "Error: jq is required" >&2
  exit 1
fi

apk_path="$(find "$dist_dir" -maxdepth 1 -type f -name '*.apk' | sort | grep -E 'universal|all' | head -n 1 || true)"
if [[ -z "$apk_path" ]]; then
  apk_path="$(find "$dist_dir" -maxdepth 1 -type f -name '*.apk' | sort | head -n 1 || true)"
fi
if [[ -z "$apk_path" ]]; then
  echo "Error: no APK found under $dist_dir" >&2
  exit 1
fi

apk_name="$(basename "$apk_path")"
version_name="$(awk -F= '$1=="versionName"{print $2}' "${dist_dir%/}/BUILD_INFO.txt" | tr -d '\r' | tail -n1)"
version_code="$(awk -F= '$1=="versionCode"{print $2}' "${dist_dir%/}/BUILD_INFO.txt" | tr -d '\r' | tail -n1)"
if [[ -z "$version_name" || -z "$version_code" ]]; then
  echo "Error: versionName/versionCode missing in BUILD_INFO.txt" >&2
  exit 1
fi

sha256=""
if [[ -f "${dist_dir%/}/SHA256SUMS.txt" ]]; then
  sha256="$(awk -v f="$apk_name" '$2==f{print $1}' "${dist_dir%/}/SHA256SUMS.txt" | tr -d '\r' | tail -n1)"
fi
if [[ -z "$sha256" ]]; then
  sha256="$(sha256sum "$apk_path" | awk '{print $1}')"
fi

release_notes_url="https://github.com/${repo}/releases/tag/${tag}"
apk_url="https://github.com/${repo}/releases/download/${tag}/${apk_name}"
generated_at_ms="$(($(date +%s) * 1000))"

new_entry_json="$(jq -n \
  --arg channel "$track" \
  --arg versionName "$version_name" \
  --arg apkUrl "$apk_url" \
  --arg apkSha256 "$sha256" \
  --arg releaseNotesUrl "$release_notes_url" \
  --argjson versionCode "$version_code" \
  --argjson generatedAt "$generated_at_ms" \
  '{
    channel: $channel,
    versionCode: $versionCode,
    versionName: $versionName,
    apkUrl: $apkUrl,
    apkSha256: $apkSha256,
    releaseNotesUrl: $releaseNotesUrl,
    publishedAtEpochMs: $generatedAt,
    critical: false
  }')"

existing_entries_json="[]"
if [[ -n "$existing_feed" ]]; then
  existing_raw=""
  if [[ "$existing_feed" =~ ^https?:// ]]; then
    existing_raw="$(curl -fsSL "$existing_feed" || true)"
  elif [[ -f "$existing_feed" ]]; then
    existing_raw="$(cat "$existing_feed")"
  fi
  if [[ -n "$existing_raw" ]]; then
    existing_entries_json="$(printf '%s' "$existing_raw" | jq -c '.payload.entries // []' 2>/dev/null || echo "[]")"
  fi
fi

merged_entries_json="$(jq -c -n \
  --argjson existing "$existing_entries_json" \
  --argjson entry "$new_entry_json" \
  '([$entry] + $existing) | unique_by(.versionCode) | sort_by(.versionCode)')"

payload_json="$(jq -n \
  --argjson generatedAt "$generated_at_ms" \
  --argjson entries "$merged_entries_json" \
  '{
    schemaVersion: 1,
    generatedAtEpochMs: $generatedAt,
    policy: {
      scheduledCheckIntervalSeconds: 21600,
      impatientReminderIntervalSeconds: 604800
    },
    entries: $entries
  }')"

payload_canonical="$(printf '%s' "$payload_json" | jq -cS '.')"

signature=""
if [[ -n "$private_key_file" ]]; then
  if [[ ! -f "$private_key_file" ]]; then
    echo "Error: private key file not found: $private_key_file" >&2
    exit 1
  fi
  sign_input_file="$(mktemp)"
  trap 'rm -f "$sign_input_file"' EXIT
  printf '%s' "$payload_canonical" >"$sign_input_file"
  signature="$(openssl pkeyutl -sign -inkey "$private_key_file" -rawin -in "$sign_input_file" \
    | base64 | tr -d '\n')"
  rm -f "$sign_input_file"
  trap - EXIT
fi

mkdir -p "$(dirname "$output_path")"
if [[ -n "$signature" ]]; then
  jq -n \
    --argjson payload "$payload_canonical" \
    --arg signature "$signature" \
    '{payload: $payload, signature: $signature}' >"$output_path"
else
  jq -n \
    --argjson payload "$payload_canonical" \
    '{payload: $payload, signature: null}' >"$output_path"
fi

echo "Generated update feed: $output_path"
