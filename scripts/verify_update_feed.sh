#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  scripts/verify_update_feed.sh <feed-json-path-or-url> [--check-urls]
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 ]]; then
  usage
  exit 1
fi

feed_ref="$1"
shift

check_urls="false"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --check-urls)
      check_urls="true"
      shift
      ;;
    *)
      echo "Error: unknown option $1" >&2
      exit 1
      ;;
  esac
done

if ! command -v jq >/dev/null 2>&1; then
  echo "Error: jq is required" >&2
  exit 1
fi

if [[ "$feed_ref" =~ ^https?:// ]]; then
  raw="$(curl -fsSL "$feed_ref")"
else
  raw="$(cat "$feed_ref")"
fi

echo "$raw" | jq -e '.payload.schemaVersion == 1' >/dev/null
echo "$raw" | jq -e '.payload.entries | type == "array" and length > 0' >/dev/null
echo "$raw" | jq -e '
  .payload.entries[]
  | .versionCode
  | type == "number"
' >/dev/null
echo "$raw" | jq -e '
  .payload.entries[]
  | (.versionName | type == "string" and length > 0)
    and (.apkUrl | type == "string" and length > 0)
    and (.apkSha256 | type == "string" and length == 64)
' >/dev/null
echo "$raw" | jq -e '
  ((.signature? == null) or (.signature | type == "string"))
    and (
      (.signatures? == null)
      or (
        (.signatures | type == "object")
        and (
          [.signatures | to_entries[] | ((.key | type == "string" and length > 0) and (.value | type == "string" and length > 0))]
          | all
        )
      )
    )
' >/dev/null
echo "$raw" | jq -e '
  .payload.entries[]
  | ((.notes? == null) or (.notes | type == "string"))
    and (
      (.notesI18n? == null)
      or (
        (.notesI18n | type == "object")
        and ([.notesI18n[] | type == "string"] | all)
      )
    )
' >/dev/null

dup_count="$(echo "$raw" | jq -r '[.payload.entries[].versionCode] | length - (unique | length)')"
if [[ "$dup_count" != "0" ]]; then
  echo "Error: duplicate versionCode entries found in feed" >&2
  exit 1
fi

if [[ "$check_urls" == "true" ]]; then
  echo "Checking entry URLs..."
  while IFS= read -r url; do
    if [[ -z "$url" ]]; then
      continue
    fi
    curl -fsI "$url" >/dev/null
  done < <(echo "$raw" | jq -r '.payload.entries[] | .apkUrl, (.releaseNotesUrl // empty)')
fi

echo "Feed verification passed"
