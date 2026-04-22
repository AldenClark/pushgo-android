# Release Notes

This file contains end-user-facing release notes for Google Play and GitHub Releases.

Policy:
- Beta tags use `vX.Y.Z-beta.N`, and read from `[Unreleased]`.
- Release tags use `vX.Y.Z`, and read from `[vX.Y.Z]`.
- Keep entries user-visible and outcome-focused.
- Internal refactors, CI changes, and implementation details belong in `release/CHANGELOG.md`.

## [Unreleased]

### Changed
- Placeholder for next beta cycle.

## [v1.2.1]

### Improved
- Improved inbound message reliability during app startup by queueing and retrying delivery processing instead of dropping early-runtime messages.
- Improved private/provider ingress handling consistency with stricter routing and safer duplicate suppression.
- Improved settings and message detail state behavior for more stable screen updates and clearer load-failure feedback.
- Improved event and object pages with more stable detail synchronization, smoother pagination, and clearer close/delete interactions.

### Fixed
- Fixed startup-window ingress instability by promoting retries and lifecycle-aware processing in the inbound pipeline.

## [v1.2.0]

### Fixed
- Fixed Android update install-blocked flow so users now get an in-app error alert immediately.
- Fixed blocked-install fallback so users can continue via Android system installer in one tap.

### Improved
- Improved update continuity and recovery path for Android in-app update failures.
