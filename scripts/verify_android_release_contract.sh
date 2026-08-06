#!/usr/bin/env bash
set -euo pipefail

tag="${1:-}"
if [[ -z "$tag" ]]; then
  echo "Usage: $0 <vX.Y.Z|vX.Y.Z-beta.N>" >&2
  exit 2
fi

if [[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)-beta\.([1-9][0-9]*)$ ]]; then
  section="Unreleased"
elif [[ "$tag" =~ ^v([0-9]+\.[0-9]+\.[0-9]+)$ ]]; then
  section="$tag"
else
  echo "Unsupported Android release tag: $tag" >&2
  exit 1
fi

release_notes_file="release/RELEASE_NOTES.md"
update_notes_file="release/update-notes/${tag}.json"
[[ -f "$release_notes_file" ]] || { echo "Missing $release_notes_file" >&2; exit 1; }
[[ -f "$update_notes_file" ]] || { echo "Missing $update_notes_file" >&2; exit 1; }

# Keep this extractor byte-for-byte equivalent in behavior to the release workflow gate.
section_body="$(awk -v sec="$section" '
  BEGIN { in_section = 0 }
  /^## \[/ {
    if (in_section == 1) exit
    if ($0 ~ "^## \\[" sec "\\]([[:space:]]|$|[[:space:]]-)") {
      in_section = 1
      next
    }
  }
  in_section == 1 { print }
' "$release_notes_file")"
section_body="$(printf "%s\n" "$section_body" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')"
if [[ -z "$section_body" ]]; then
  echo "RELEASE_NOTES section not found or empty: [$section]" >&2
  exit 1
fi

jq -e 'type == "object"' "$update_notes_file" >/dev/null
for locale in en zh-CN zh-TW; do
  jq -e --arg locale "$locale" '
    .[$locale] | type == "string" and (gsub("\\s+"; "") | length > 0)
  ' "$update_notes_file" >/dev/null
done

version_info="$(./gradlew -q :app:printReleaseVersionInfo -Ppushgo.versionName="$tag")"
version_name="$(printf '%s\n' "$version_info" | awk -F= '$1=="versionName"{print $2}' | tail -n1)"
version_code="$(printf '%s\n' "$version_info" | awk -F= '$1=="versionCode"{print $2}' | tail -n1)"
[[ "$version_name" == "$tag" ]] || {
  echo "Gradle versionName mismatch: expected=$tag actual=$version_name" >&2
  exit 1
}
[[ "$version_code" =~ ^[0-9]+$ ]] || {
  echo "Gradle versionCode is not numeric: $version_code" >&2
  exit 1
}

echo "android release contract passed tag=$tag section=[$section] versionCode=$version_code updateNotes=$update_notes_file"
