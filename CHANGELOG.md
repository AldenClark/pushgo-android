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
- End-user release copy is sourced from `RELEASE_NOTES.md`:
  - beta tags use `[Unreleased]`
  - release tags use `[vX.Y.Z]`
- Engineering implementation history stays in `CHANGELOG.md`.

## [Unreleased]

### Added
- Added root-level changelog governance for Android release notes and version history.
- Added SemVer tag parsing in Android release workflow for `vX.Y.Z` and `vX.Y.Z-beta.N`.
- Added workflow extraction of GitHub Release notes from `CHANGELOG.md`:
  - beta tags use `[Unreleased]`
  - release tags use `[vX.Y.Z]`

### Changed
- Android release workflow now reads user-facing release notes from `RELEASE_NOTES.md` instead of `CHANGELOG.md`.
- Android release workflow now triggers on SemVer tags (`v*`) instead of legacy `Release-*`.
- Android packaging now takes `versionName` directly from tag input passed to Gradle.
- Release asset naming now uses normalized SemVer version names from tag/Gradle metadata.
- GitHub Release publishing now uses changelog-driven notes instead of generated release notes.

### Fixed
- Updated Android version metadata parsing to support both release and beta SemVer formats.
- Fixed `versionCode` strategy to deterministic formula-based derivation from `versionName`.
