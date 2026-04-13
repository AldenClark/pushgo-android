#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/generate_update_feed.sh <stable|beta> <dist_dir> [options]

Options:
  --tag <vX.Y.Z or vX.Y.Z-beta.N>      Release tag (required)
  --repo <owner/repo>                   Repository slug for release asset URL (required)
  --base-url <https://host/path>        Base URL for update artifacts; package URLs become <base-url>/<track>/<apk>
  --existing-feed <url-or-path>         Existing feed to merge entries from
  --repo-feed-path <path>               Persistent repo feed path (default: release/update-feed-v1.json)
  --update-notes-dir <path>             Directory containing <tag>.json note maps (default: release/update-notes)
  --update-notes-file <path>            Override note file path for this tag
  --output <path>                       Output signed feed JSON (default: <dist_dir>/update-feed-v1.json)
  --private-key-file <path>             Legacy alias of --ed25519-private-key-file
  --ed25519-private-key-file <path>     Ed25519 private key (PKCS8 PEM) for payload signature
  --ecdsa-private-key-file <path>       ECDSA P-256 private key (PKCS8 PEM) for payload signature
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
base_url=""
existing_feed=""
repo_feed_path="release/update-feed-v1.json"
update_notes_dir="release/update-notes"
update_notes_file=""
output_path="${dist_dir%/}/update-feed-v1.json"
private_key_file=""
ed25519_private_key_file=""
ecdsa_private_key_file=""

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
    --base-url)
      base_url="${2:-}"
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
    --ed25519-private-key-file)
      ed25519_private_key_file="${2:-}"
      shift 2
      ;;
    --ecdsa-private-key-file)
      ecdsa_private_key_file="${2:-}"
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

if [[ -n "$base_url" && ! "$base_url" =~ ^https?:// ]]; then
  echo "Error: --base-url must start with http:// or https://, got: $base_url" >&2
  exit 1
fi

if [[ -n "$private_key_file" && -z "$ed25519_private_key_file" ]]; then
  ed25519_private_key_file="$private_key_file"
fi

if [[ ! -f "${dist_dir%/}/BUILD_INFO.txt" ]]; then
  echo "Error: BUILD_INFO.txt not found under $dist_dir" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  echo "Error: jq is required" >&2
  exit 1
fi

declare -A apk_by_key=()
while IFS= read -r apk_file; do
  base_name="$(basename "$apk_file")"
  base_lower="$(printf '%s' "$base_name" | tr '[:upper:]' '[:lower:]')"
  package_key=""
  if [[ "$base_lower" == *"universal"* || "$base_lower" == *"-all-"* ]]; then
    package_key="universal"
  elif [[ "$base_lower" == *"arm64-v8a"* || "$base_lower" == *"aarch64"* ]]; then
    package_key="v8a"
  elif [[ "$base_lower" == *"armeabi-v7a"* || "$base_lower" == *"armv7"* || "$base_lower" == *"arm-v7a"* ]]; then
    package_key="v7a"
  elif [[ "$base_lower" == *"x86_64"* || "$base_lower" == *"-x86-"* || "$base_lower" == *"-x86."* ]]; then
    package_key="x86"
  fi
  if [[ -n "$package_key" && -z "${apk_by_key[$package_key]:-}" ]]; then
    apk_by_key[$package_key]="$apk_file"
  fi
done < <(find "$dist_dir" -maxdepth 1 -type f -name '*.apk' | sort)

required_package_keys=(v8a v7a x86 universal)
for required_key in "${required_package_keys[@]}"; do
  if [[ -z "${apk_by_key[$required_key]:-}" ]]; then
    echo "Error: missing ${required_key} package APK under $dist_dir" >&2
    exit 1
  fi
done

universal_apk_path="${apk_by_key[universal]}"
apk_name="$(basename "$universal_apk_path")"
version_name="$(awk -F= '$1=="versionName"{print $2}' "${dist_dir%/}/BUILD_INFO.txt" | tr -d '\r' | tail -n1)"
version_code="$(awk -F= '$1=="versionCode"{print $2}' "${dist_dir%/}/BUILD_INFO.txt" | tr -d '\r' | tail -n1)"
if [[ -z "$version_name" || -z "$version_code" ]]; then
  echo "Error: versionName/versionCode missing in BUILD_INFO.txt" >&2
  exit 1
fi

release_notes_url="https://github.com/${repo}/releases/tag/${tag}"
generated_at_ms="$(($(date +%s) * 1000))"

packages_json="{}"
for package_key in "${required_package_keys[@]}"; do
  package_path="${apk_by_key[$package_key]}"
  package_name="$(basename "$package_path")"
  package_sha256=""
  if [[ -f "${dist_dir%/}/SHA256SUMS.txt" ]]; then
    package_sha256="$(awk -v f="$package_name" '$2==f{print $1}' "${dist_dir%/}/SHA256SUMS.txt" | tr -d '\r' | tail -n1)"
  fi
  if [[ -z "$package_sha256" ]]; then
    package_sha256="$(sha256sum "$package_path" | awk '{print $1}')"
  fi
  if [[ -n "$base_url" ]]; then
    package_url="${base_url%/}/${track}/${version_name}/${package_name}"
  else
    package_url="https://github.com/${repo}/releases/download/${tag}/${package_name}"
  fi
  packages_json="$(jq -c \
    --arg key "$package_key" \
    --arg url "$package_url" \
    --arg sha "$package_sha256" \
    '. + {($key): {apkUrl: $url, apkSha256: $sha}}' <<<"$packages_json")"
done

apk_url="$(printf '%s' "$packages_json" | jq -r '.universal.apkUrl')"
sha256="$(printf '%s' "$packages_json" | jq -r '.universal.apkSha256')"

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
  --argjson packages "$packages_json" \
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
    packages: $packages,
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

ed25519_signature=""
ecdsa_signature=""
if [[ -n "$ed25519_private_key_file" || -n "$ecdsa_private_key_file" ]]; then
  sign_input_file="$(mktemp)"
  trap 'rm -f "$sign_input_file"' EXIT
  printf '%s' "$payload_canonical" >"$sign_input_file"

  if [[ -n "$ed25519_private_key_file" ]]; then
    if [[ ! -f "$ed25519_private_key_file" ]]; then
      echo "Error: private key file not found: $ed25519_private_key_file" >&2
      exit 1
    fi
    ed25519_signature="$(openssl pkeyutl -sign -inkey "$ed25519_private_key_file" -rawin -in "$sign_input_file" \
      | base64 | tr -d '\n')"
  fi

  if [[ -n "$ecdsa_private_key_file" ]]; then
    if [[ ! -f "$ecdsa_private_key_file" ]]; then
      echo "Error: private key file not found: $ecdsa_private_key_file" >&2
      exit 1
    fi
    ecdsa_signature="$(openssl dgst -sha256 -sign "$ecdsa_private_key_file" "$sign_input_file" \
      | base64 | tr -d '\n')"
  fi

  rm -f "$sign_input_file"
  trap - EXIT
fi

signatures_json="$(jq -n \
  --arg ed25519 "$ed25519_signature" \
  --arg ecdsa "$ecdsa_signature" \
  '{
    "ed25519": $ed25519,
    "ecdsa-p256-sha256": $ecdsa
  }
  | with_entries(select(.value | length > 0))')"

mkdir -p "$(dirname "$output_path")"
jq -n \
  --argjson payload "$payload_canonical" \
  --arg signature "$ed25519_signature" \
  --argjson signatures "$signatures_json" \
  '{payload: $payload, signature: (if ($signature | length) > 0 then $signature else null end), signatures: $signatures}' >"$output_path"

echo "Generated update feed: $output_path"
if [[ -n "$repo_feed_path" ]]; then
  mkdir -p "$(dirname "$repo_feed_path")"
  if [[ "$output_path" != "$repo_feed_path" ]]; then
    cp "$output_path" "$repo_feed_path"
  fi
  echo "Updated repo feed: $repo_feed_path"
fi
