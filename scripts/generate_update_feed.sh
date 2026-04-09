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
  --repo-feed-path <path>               Persistent repo feed path (default: release/update-feed-v1.json)
  --update-notes-dir <path>             Directory containing <tag>.json note maps (default: release/update-notes)
  --update-notes-file <path>            Override note file path for this tag
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
repo_feed_path="release/update-feed-v1.json"
update_notes_dir="release/update-notes"
update_notes_file=""
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
    --repo-feed-path)
      repo_feed_path="${2:-}"
      shift 2
      ;;
    --update-notes-dir)
      update_notes_dir="${2:-}"
      shift 2
      ;;
    --update-notes-file)
      update_notes_file="${2:-}"
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

notes_file="$update_notes_file"
if [[ -z "$notes_file" && -n "$update_notes_dir" ]]; then
  candidate_notes_file="${update_notes_dir%/}/${tag}.json"
  if [[ -f "$candidate_notes_file" ]]; then
    notes_file="$candidate_notes_file"
  fi
fi

notes_i18n_json="{}"
notes_fallback=""
if [[ -n "$notes_file" ]]; then
  if [[ ! -f "$notes_file" ]]; then
    echo "Error: update notes file not found: $notes_file" >&2
    exit 1
  fi
  if ! notes_i18n_json="$(jq -ce '
    if type != "object" then
      error("update notes must be a JSON object")
    else
      with_entries(
        select(
          (.key | type == "string")
          and (.value | type == "string" and (gsub("\\s+"; "") | length > 0))
        )
      )
    end
  ' "$notes_file")"; then
    echo "Error: invalid update notes file: $notes_file" >&2
    exit 1
  fi
  notes_fallback="$(printf '%s' "$notes_i18n_json" | jq -r '
    .["en"]
    // .["zh-CN"]
    // .["zh-TW"]
    // ((to_entries | sort_by(.key) | .[0].value) // "")
  ')"
fi

new_entry_json="$(jq -n \
  --arg channel "$track" \
  --arg versionName "$version_name" \
  --arg apkUrl "$apk_url" \
  --arg apkSha256 "$sha256" \
  --arg releaseNotesUrl "$release_notes_url" \
  --arg notes "$notes_fallback" \
  --argjson notesI18n "$notes_i18n_json" \
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
  }
  + (if ($notes | length) > 0 then {notes: $notes} else {} end)
  + (if ($notesI18n | length) > 0 then {notesI18n: $notesI18n} else {} end)
  ')"

existing_entries_json="[]"
existing_feed_ref="$existing_feed"
if [[ -z "$existing_feed_ref" && -n "$repo_feed_path" && -f "$repo_feed_path" ]]; then
  existing_feed_ref="$repo_feed_path"
fi
if [[ -n "$existing_feed_ref" ]]; then
  existing_raw=""
  if [[ "$existing_feed_ref" =~ ^https?:// ]]; then
    existing_raw="$(curl -fsSL "$existing_feed_ref" || true)"
  elif [[ -f "$existing_feed_ref" ]]; then
    existing_raw="$(cat "$existing_feed_ref")"
  fi
  if [[ -n "$existing_raw" ]]; then
    existing_entries_json="$(printf '%s' "$existing_raw" | jq -c '.payload.entries // []' 2>/dev/null || echo "[]")"
  fi
fi

merged_entries_json="$(jq -c -n \
  --argjson existing "$existing_entries_json" \
  --argjson entry "$new_entry_json" \
  '($existing + [$entry])
   | map(select(.channel | type == "string" and length > 0))
   | sort_by(.channel, .versionCode)
   | group_by(.channel)
   | map(last)
   | sort_by(.versionCode)')"

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
if [[ -n "$repo_feed_path" ]]; then
  mkdir -p "$(dirname "$repo_feed_path")"
  if [[ "$output_path" != "$repo_feed_path" ]]; then
    cp "$output_path" "$repo_feed_path"
  fi
  echo "Updated repo feed: $repo_feed_path"
fi
