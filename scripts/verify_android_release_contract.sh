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
changelog_file="release/CHANGELOG.md"
update_notes_file="release/update-notes/${tag}.json"
[[ -f "$release_notes_file" ]] || { echo "Missing $release_notes_file" >&2; exit 1; }
[[ -f "$changelog_file" ]] || { echo "Missing $changelog_file" >&2; exit 1; }
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

if [[ "$section" != "Unreleased" ]]; then
  changelog_section_body="$(awk -v sec="$section" '
    BEGIN { in_section = 0 }
    /^## \[/ {
      if (in_section == 1) exit
      if ($0 ~ "^## \\[" sec "\\]([[:space:]]|$|[[:space:]]-)") {
        in_section = 1
        next
      }
    }
    in_section == 1 { print }
  ' "$changelog_file")"
  changelog_section_body="$(printf "%s\n" "$changelog_section_body" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')"
  if [[ -z "$changelog_section_body" ]]; then
    echo "CHANGELOG section not found or empty: [$section]" >&2
    exit 1
  fi

  unreleased_notes_body="$(awk '
    /^## \[Unreleased\]/ { in_section = 1; next }
    /^## \[/ { if (in_section == 1) exit }
    in_section == 1 { print }
  ' "$release_notes_file" | sed '/./,$!d' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')"
  if [[ "$unreleased_notes_body" != $'### Changed\n- Placeholder for next development cycle.' ]]; then
    echo "Stable release requires RELEASE_NOTES [Unreleased] to contain only the next-cycle placeholder." >&2
    exit 1
  fi

  unreleased_changelog_body="$(awk '
    /^## \[Unreleased\]/ { in_section = 1; next }
    /^## \[/ { if (in_section == 1) exit }
    in_section == 1 { print }
  ' "$changelog_file" | sed '/./,$!d' | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}')"
  if [[ "$unreleased_changelog_body" != $'### Changed\n- Placeholder for next development cycle.' ]]; then
    echo "Stable release requires CHANGELOG [Unreleased] to contain only the next-cycle placeholder." >&2
    exit 1
  fi
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

default_version_info="$(./gradlew -q :app:printReleaseVersionInfo)"
default_version_name="$(printf '%s\n' "$default_version_info" | awk -F= '$1=="versionName"{print $2}' | tail -n1)"
default_version_code="$(printf '%s\n' "$default_version_info" | awk -F= '$1=="versionCode"{print $2}' | tail -n1)"
[[ "$default_version_name" == "$tag" ]] || {
  echo "Default Gradle versionName mismatch: expected=$tag actual=$default_version_name" >&2
  exit 1
}
[[ "$default_version_code" == "$version_code" ]] || {
  echo "Default Gradle versionCode mismatch: expected=$version_code actual=$default_version_code" >&2
  exit 1
}

database_source="app/src/main/java/io/ethan/pushgo/data/db/PushGoDatabase.kt"
database_version="$(sed -n 's/^[[:space:]]*version = \([0-9][0-9]*\),$/\1/p' "$database_source" | head -n1)"
[[ "$database_version" =~ ^[0-9]+$ ]] || {
  echo "Unable to resolve Room database version from $database_source" >&2
  exit 1
}
schema_dir="app/schemas/io.ethan.pushgo.data.db.PushGoDatabase"
for schema_version in $(seq 24 "$database_version"); do
  schema_file="$schema_dir/$schema_version.json"
  [[ -f "$schema_file" ]] || {
    echo "Missing Room schema: $schema_file" >&2
    exit 1
  }
  git ls-files --error-unmatch "$schema_file" >/dev/null 2>&1 || {
    echo "Room schema is not tracked by Git: $schema_file" >&2
    exit 1
  }
  jq -e --argjson expected "$schema_version" '.database.version == $expected' "$schema_file" >/dev/null || {
    echo "Room schema version mismatch: $schema_file" >&2
    exit 1
  }
done

echo "android release contract passed tag=$tag section=[$section] versionCode=$version_code updateNotes=$update_notes_file"
