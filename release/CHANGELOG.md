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
- Added canonical update-feed signature regression coverage using a real `v1.2.0-beta.10` feed fixture.
- Added versioned update notes source: `release/update-notes/v1.2.0-beta.11.json`.

### Changed
- Moved update-feed canonical JSON generation into a shared encoder used by both production code and regression tests.
- Bumped default `appVersionName` to `v1.2.0-beta.11`.

### Fixed
- Fixed Android update-feed signature verification by removing whitespace drift from client-side canonical JSON encoding.
- Fixed update prompts so `notes`/`notesI18n` content is exercised by regression fixtures and shown again in update cards.
