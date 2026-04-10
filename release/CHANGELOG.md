# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/), and this project follows [Semantic Versioning](https://semver.org/).

PushGo Android policy:
- Release tags use `vX.Y.Z`.
- Beta tags use `vX.Y.Z-beta.N`.
- `versionName` follows the tag exactly (`vX.Y.Z` or `vX.Y.Z-beta.N`).
- `versionCode` is auto-calculated from `versionName`:
  - `v1.1.1-beta.1` -> `1010101`
  - `v5.1.1-beta.2` -> `5010102`
  - `v1.1.1` -> `1010199`
  - `v1.3.2` -> `1030299`
- End-user release copy is sourced from `release/RELEASE_NOTES.md`:
  - beta tags use `[Unreleased]`
  - release tags use `[vX.Y.Z]`
- Engineering implementation history stays in `release/CHANGELOG.md`.

## [Unreleased]

### Added
- Added self-hosted Android updater framework for APK distribution:
  - background silent checks + manual check in settings
  - stable/beta channel selection (`beta` receives both stable + beta and picks newest)
  - skip current version and reminder cooldown policy
  - PackageInstaller recovery guidance when installer is blocked/intercepted
- Added update feed/deploy scripts for release automation:
  - `scripts/generate_update_feed.sh`
  - `scripts/generate_update_deploy_config.sh`
  - `scripts/publish_update_artifacts.sh`
- Added release record workspace under `release/` and update-note source directory:
  - `release/CHANGELOG.md`
  - `release/RELEASE_NOTES.md`
  - `release/update-notes/`
- Added root-level changelog governance for Android release notes and version history.
- Added SemVer tag parsing in Android release workflow for `vX.Y.Z` and `vX.Y.Z-beta.N`.
- Added workflow extraction of GitHub Release notes from `release/RELEASE_NOTES.md`:
  - beta tags use `[Unreleased]`
  - release tags use `[vX.Y.Z]`

### Changed
- Android release workflow now reads user-facing release notes from `release/RELEASE_NOTES.md` instead of `release/CHANGELOG.md`.
- Android release workflow now triggers on SemVer tags (`v*`) instead of legacy `Release-*`.
- Android packaging now takes `versionName` directly from tag input passed to Gradle.
- Release asset naming now uses normalized SemVer version names from tag/Gradle metadata.
- GitHub Release publishing now uses release-notes-driven notes instead of generated release notes.
- Updater feed protocol simplified to a single file (`update-feed-v1.json`), removing separate payload artifact.
- Active online feed path switched to `/android/update-feed-v1.json` and workflow uses repository variable `PUSHGO_UPDATE_FEED_URL` instead of secret.
- Prepared next beta release metadata:
  - bumped default `appVersionName` to `v1.2.0-beta.2`
  - added `release/update-notes/v1.2.0-beta.2.json` for localized updater notes source

### Fixed
- Updated Android version metadata parsing to support both release and beta SemVer formats.
- Fixed `versionCode` strategy to deterministic formula-based derivation from `versionName`.
