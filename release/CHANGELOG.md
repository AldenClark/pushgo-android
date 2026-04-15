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
- Added startup delivery guard dialogs for notification permission and battery optimization guidance, including one-month snooze support.
- Added provider route bootstrap fallback in subscription sync path (`fetchFcmTokenForIngress`) to reduce first-run pull failures.
- Added route-repair step before provider pull execution in ingress coordinator.
- Added versioned update notes source: `release/update-notes/v1.2.0-beta.3.json`.

### Changed
- Centralized token-update handling in app startup/runtime, and trigger provider ingress pull on `token_update`.
- Push ingress processing now runs provider wakeup-pull path earlier and keeps direct-delivery ACK handling aligned in the same flow.
- Notification presentation mapping is now normalized and channel defaults were tuned (schema version `6`, private lock-screen visibility, higher normal priority).
- Bumped default `appVersionName` to `v1.2.0-beta.3`.

### Fixed
- Fixed stale provider route snapshots causing pull misses after token or mode changes.
- Fixed startup/foreground sequencing gaps where ingress pull could be skipped before startup sync completion.
