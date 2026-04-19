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
- Added in-app install-blocked dialog state bridge from installer receiver to settings UI.
- Added versioned update notes source: `release/update-notes/v1.2.0-beta.15.json`.
- Added shared manual install launcher used by both UI actions and receiver fallback.

### Changed
- Bumped default `appVersionName` to `v1.2.0-beta.15`.
- Marked this beta line as the publication baseline for all Android changes accumulated since the previous Android tag.

### Fixed
- Fixed update blocked-install handling so app now surfaces a visible in-app error prompt.
- Fixed blocked-install recovery flow to retry via system package installer when possible.
