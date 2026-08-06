# Release Notes

This file contains end-user-facing release notes for Google Play and GitHub Releases.

Policy:
- Beta tags use `vX.Y.Z-beta.N`, and read from `[Unreleased]`.
- Release tags use `vX.Y.Z`, and read from `[vX.Y.Z]`.
- Keep entries user-visible and outcome-focused.
- Internal refactors, CI changes, and implementation details belong in `release/CHANGELOG.md`.

## [Unreleased]

### Improved
- Improved Android build and runtime compatibility by moving the app onto the AGP 9.2.1 toolchain baseline with refreshed core dependencies.
- Improved update install and system-settings handoff behavior so package install permission, battery settings, and related flows behave more consistently on modern Android builds.
- Improved message and detail rendering stability with cleanup across image decoding, Compose resource access, and platform compatibility paths.

### Fixed
- Fixed warning-heavy Android build and lint issues exposed by the latest toolchain refresh.
- Fixed stale resource and launcher asset issues that could interfere with release readiness checks.

## [v1.3.0]

### Improved
- Improved notification recovery so gateway-pulled messages remain available until Android has durably handled them and acknowledged the correct gateway.
- Improved high-volume recovery with paged Pull and batch acknowledgement, including safe retry after app or network interruption.
- Preserved compatibility with beta gateways while keeping legacy destructive Pull and single-message acknowledgement behavior separate from the formal v2 protocol.

### Fixed
- Fixed a gateway-switch edge case that could send an older pending acknowledgement to the newly selected gateway.
- Fixed delivery identity handling so the gateway queue item ID remains authoritative when payload fields are missing or inconsistent.

## [v1.2.6]

### Improved
- Improved Android compatibility by lowering the supported floor to Android 9 and hardening several app behaviors for API 28 devices.
- Improved event and object detail consistency so entity patch updates, notification-driven refreshes, and persisted projection data stay in sync.
- Improved message workflow accuracy by making mark-all-read apply only to the messages currently visible in your active list or filter.
- Improved list responsiveness during pending local deletion so visible message lists refresh more reliably while delete and recovery actions are happening.

### Fixed
- Fixed stale or inconsistent event/object detail content that could appear after entity patch merges or notification-triggered updates.
- Fixed message list refresh gaps during pending deletion that could leave on-screen results temporarily out of date.

## [v1.2.5]

### Improved
- Improved message detail sheet stability and presentation consistency when opening message details.
- Improved image load failure handling with clearer placeholders and localized failure text in both message detail and markdown content.

## [v1.2.4]

### Improved
- Improved unread-only message filtering continuity: your unread-only filter preference is now preserved across app restarts.
- Improved gateway/channel error clarity when a private channel reaches the subscriber cap, with direct actionable guidance in-app.

### Fixed
- Fixed private channel subscriber-limit errors being grouped into generic validation failures, which could make root-cause diagnosis less clear.

## [v1.2.3]

### Improved
- Improved message workflows with safer local deletion: delete actions are now undoable and easier to recover from.
- Improved message list productivity with a new action to mark all currently displayed items as read.
- Improved message filtering with clearer facet-based multi-select controls and better alignment with tag search.
- Improved message receiving reliability and overall in-app message state consistency.

### Fixed
- Fixed animated image playback detection issues in markdown/detail rendering.
- Fixed detail-page media loading behavior so media fetches no longer block screen responsiveness.

## [v1.2.2]

### Improved
- Improved encrypted notification payload handling so decrypted message, URL, image, and event/object metadata stay consistent across message/event/object views.
- Improved message, event, and object surfaces with clearer channel and decryption status chips in both list and detail screens.
- Improved image loading and tap behavior in detail pages with explicit loading/error placeholders and safer click timing after media is ready.

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
