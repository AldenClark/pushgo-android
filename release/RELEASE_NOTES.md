# Release Notes

This file contains end-user-facing release notes for Google Play and GitHub Releases.

Policy:
- Beta tags use `vX.Y.Z-beta.N`, and read from `[Unreleased]`.
- Release tags use `vX.Y.Z`, and read from `[vX.Y.Z]`.
- Keep entries user-visible and outcome-focused.
- Internal refactors, CI changes, and implementation details belong in `release/CHANGELOG.md`.

## [Unreleased]

### Improved
- Improved ACK + pull reliability for provider-delivered notifications, especially after token refresh and app cold start.
- Improved startup guidance for critical delivery permissions (notification permission and battery optimization).
- Improved foreground re-entry behavior so provider ingress sync runs at the right lifecycle point.
- Improved notification presentation consistency for normal/high/critical levels and lock-screen privacy defaults.
