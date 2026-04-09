# Release Notes

This file contains end-user-facing release notes for Google Play and GitHub Releases.

Policy:
- Beta tags use `vX.Y.Z-beta.N`, and read from `[Unreleased]`.
- Release tags use `vX.Y.Z`, and read from `[vX.Y.Z]`.
- Keep entries user-visible and outcome-focused.
- Internal refactors, CI changes, and implementation details belong in `release/CHANGELOG.md`.

## [Unreleased]

### Added
- Added self-hosted in-app update flow for APK releases.
- Added update channel selection in Settings: `stable` and `beta`.

### Improved
- Beta mode now compares both stable and beta candidates and picks the latest version.
- Added `Skip this version` and reminder cooldown to reduce repeated interruption.
- Added clearer installer recovery guidance when system installer blocks update flow.
- Improved app stability and reliability for daily notification delivery.
