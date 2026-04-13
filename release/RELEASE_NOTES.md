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
- Message detail header is now cleaner by removing the title copy action.
- Beta mode now compares both stable and beta candidates and picks the latest version.
- Added `Skip this version` and reminder cooldown to reduce repeated interruption.
- Added clearer installer recovery guidance when system installer blocks update flow.
- Improved app stability and reliability for daily notification delivery.

### Update Feed Protocol
- Update feed entry now carries a four-variant package matrix under `packages`:
  - `packages.v8a` for `arm64-v8a`
  - `packages.v7a` for `armeabi-v7a`
  - `packages.x86` for `x86_64/x86`
  - `packages.universal` as fallback
- Each package object contains:
  - `apkUrl`: direct download URL for this ABI package
  - `apkSha256`: SHA-256 checksum for integrity verification
- Client package selection order:
  - Match runtime ABI in order of `Build.SUPPORTED_ABIS`
  - Prefer exact ABI package (`v8a/v7a/x86`)
  - Fall back to `universal` when no exact match exists
