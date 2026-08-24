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

### Changed
- Placeholder for next development cycle.

## [v1.3.0] - 2026-08-24

### Added
- Added durable, Gateway/device-scoped provider delivery and ACK state so process death, network interruption, Gateway switching, and duplicate delivery remain recoverable.
- Added durable pending-deletion execution and WorkManager recovery for messages, events, things, and channels, including notification and credential cleanup after restart.
- Added Room schemas and migrations through version 30 for provider staging, scoped ACK state, durable deletion, bounded ACK tombstones, and legacy pre-send uncertainty.
- Added normalized indexed search for large message histories, broader Event/Thing search semantics, bounded continuation, and a 100k-message performance regression gate.
- Added localized product documentation links, accessibility semantics, and focused minimum/modern Android runtime coverage.

### Changed
- Finalized Android `versionName=v1.3.0` and stable `versionCode=1030099`; downgrade to `v1.3.0-beta.1` is intentionally unsupported.
- Provider recovery prefers the non-destructive `POST /v2/messages/pull` and explicit `POST /v2/messages/ack` contract, but falls back to legacy `POST /messages/pull` only for an exact `404 route_not_found`, matching the Apple client; legacy responses are transactionally staged in Room before parsing or canonical persistence.
- Persisted ACK Gateway/device ownership and protocol contract, routed v2 batches only through `/v2/messages/ack`, and retained legacy single ACK routing only for already-attributed legacy/direct deliveries.
- Added `has_more` Pull draining with the outer queue `delivery_id` as the authoritative identity.
- Upgraded the Android build stack to AGP `9.2.1`, refreshed core Android dependencies, and aligned package-install, image-decoding, Compose, backup, and SDK compatibility paths; retained the direct system request for excluding PushGo from battery optimization.
- Restored the v1.2.6 delivery matrix with signed `arm64-v8a`, `armeabi-v7a`, `x86_64`, and universal APK artifacts.
- Pinned the Rust/Android JNI toolchain and kept release-time checks deterministic without emulator startup in the tag-triggered publication job.

### Fixed
- Made v2 delete-all ACKs terminal for structurally valid zero, partial, and full removal counts, and persisted legacy pre-send ambiguity so process death cannot strand already-removed deliveries.
- Prevented successful Pull from clearing local ACK state before a structurally valid Gateway ACK confirms the requested immutable batch.
- Propagated persistence, Pull, notification, page-state, cancellation, and retryable failures correctly through WorkManager instead of reporting false success.
- Repaired duplicate delivery visibility, notification/media/page projections, silent replay behavior, private delivery identity, ACK scheduling fairness, and long-offline tombstone retention.
- Prevented late older Event/Thing operations from rolling back the current derived head while retaining their immutable change-log entries.
- Made Thing child messages addressable and deletable through notification local IDs, and bounded message-list payload projections to 16 KiB of list-required fields.
- Prevented deletion recovery from cancelling its own active commit, protected newer channel credentials from older deletion replays, and repaired Room-to-WorkManager handoff gaps.
- Reduced the first search delay on complete 100k-message histories, prevented unnecessary index rebuilds, and continued bounded Event/Thing search after early matches.
- Hardened Rust JNI lifecycle and packaging across `arm64-v8a`, `armeabi-v7a`, and `x86_64`.
- Fixed release-audit noise caused by stale Android resources and launcher asset duplication after the build-stack refresh.
- Fixed Android compatibility, image-cache/rendering, accessibility, settings navigation, and package-install edge cases surfaced by the upgraded toolchain.

## [v1.2.6] - 2026-06-09

### Added
- Added versioned stable update notes source: `release/update-notes/v1.2.6.json`.
- Added Android 9 focused regression coverage for markdown media rendering and API 28 compatibility paths.
- Added projection/detail correctness coverage for entity patch merge, storage, and notification ingress flows.

### Changed
- Finalized Android app version to `v1.2.6` for release builds.
- `versionCode` now resolves to stable code `1020699` from `versionName=v1.2.6`.
- Lowered the supported Android floor to Android 9 (`minSdk 28`) and aligned native/update-feed tooling with that release target.
- Reworked entity projection merge and persistence flow so event/object detail state stays aligned with inbound patch data.
- Updated mark-all-read behavior to operate on the user's current visible message scope instead of a broader implicit set.
- Refined pending-local-deletion refresh behavior so visible message lists stay synchronized while delete/recover actions are in flight.

### Fixed
- Fixed multiple Android 9 / API 28 compatibility issues across database, markdown media, and repository code paths.
- Fixed entity patch storage, projection merge, and notification-driven detail refresh issues that could leave event/object views stale or inconsistent.
- Fixed mark-all-read scope mismatches that could affect messages outside the current filtered list.
- Fixed visible message list refresh gaps during pending deletion so list state updates immediately after local delete/recover operations.

## [v1.2.5] - 2026-05-26

### Added
- Added versioned stable update notes source: `release/update-notes/v1.2.5.json`.

### Changed
- Finalized Android app version to `v1.2.5` for release builds.
- `versionCode` now resolves to stable code `1020599` from `versionName=v1.2.5`.
- Stabilized message-detail bottom-sheet presentation with bounded height handling and safer initial expand behavior.
- Updated message detail state wiring to accept initial message snapshots, reducing blank/loading transitions when opening detail from lists.
- Improved image-load fallback rendering in both Compose surfaces and Markdown-rendered content with explicit localized error placeholders.

### Fixed
- Fixed message detail sheet layout/interaction edge cases that could produce unstable presentation behavior on some screen sizes.
- Fixed image error fallback behavior so failed media now presents clear, user-visible error placeholders instead of ambiguous blank blocks.

## [v1.2.4] - 2026-05-18

### Added
- Added versioned stable update notes source: `release/update-notes/v1.2.4.json`.

### Changed
- Finalized Android app version to `v1.2.4` for release builds.
- `versionCode` now resolves to stable code `1020499` from `versionName=v1.2.4`.
- Improved message-list unread-only filter continuity by persisting the unread-only toggle state across app restarts.
- Improved private channel runtime testability hooks and automation visible-screen reporting to keep runtime-quality validation and automation state snapshots more consistent.

### Fixed
- Fixed gateway/channel subscription error mapping for `channel_subscriber_limit_exceeded` and surfaced clearer user-facing guidance in both English and Simplified Chinese.

## [v1.2.3] - 2026-05-11

### Added
- Added undoable local deletion flow with `PendingLocalDeletionCoordinator`/`PendingLocalDeletionBar` to support safer message/event/object deletion and recovery.
- Added message list action to mark all currently displayed items as read.
- Added facet-based multi-select message filters with facet count model (`MessageFacetValueCount` / `MessageFacetOptionCount`) and toolbar refinements.
- Added ingress/decrypt test coverage updates for provider ACK draining, canonical ciphertext parsing, and local deletion coordination paths.
- Added versioned stable update notes source: `release/update-notes/v1.2.3.json`.

### Changed
- Finalized Android app version to `v1.2.3` for release builds.
- `versionCode` now resolves to stable code `1020399` from `versionName=v1.2.3`.
- Refined gateway error mapping and wakeup pull handling across channel subscription and ingress coordination paths.
- Updated provider ingress pipeline to drain provider ACKs and normalize canonical ciphertext fields before downstream rendering.
- Updated message filtering behavior to align list facets, tag-search semantics, and batch read/delete execution paths.
- Updated message/event/object screens and view models for improved filter UX and deletion flow consistency.
- Updated device/instrumentation test runtime wiring for more stable Android migration and integration test execution.

### Fixed
- Fixed animated image detection/playback regressions in markdown/detail media rendering.
- Fixed detail-page media loading to non-blocking behavior so page content remains responsive while media resolves.
- Fixed wakeup and provider ingress edge-case handling that could surface unclear gateway errors or unstable pull behavior.

## [v1.2.2] - 2026-04-24

### Added
- Added `DecryptionState` and channel metadata chips to shared message/event/object row/detail rendering surfaces.
- Added image loading state callbacks/placeholders in `PushGoAsyncImage` and `PushGoPlayableImage` to keep media interactions deterministic.
- Added versioned stable update notes source: `release/update-notes/v1.2.2.json`.

### Changed
- Finalized Android app version to `v1.2.2` for release builds.
- `versionCode` now resolves to stable code `1020299` from `versionName=v1.2.2`.
- Updated notification decrypt/ingress flow so decrypted payload overrides are written back into normalized ingress fields (`title/body/url/images/event/thing metadata`).
- Updated message/event/object list/detail UI to surface channel display name mapping and decryption status consistently.
- Updated detail-page media interaction to open previews only after image load completes.

### Fixed
- Fixed encrypted payload metadata drop risk where decrypted `url` and event/object profile/attrs fields could be lost before persistence/rendering.
- Fixed detail-page image tap race that could trigger preview interactions before media load was complete.

## [v1.2.1] - 2026-04-22

### Added
- Added `SettingsUiState` read-model consolidation for `SettingsScreen`.
- Added injectable runtime/hooks pipeline for inbound processing:
  - `InboundProcessorRuntimeResolver`
  - `InboundProcessorHooksFactory`
  - `InboundProcessorHooks`
- Added inbound reliability test coverage:
  - `InboundMessageWorkerPayloadCodecTest`
  - `InboundIngressRouteResolverTest`
  - `InboundMessageProcessorTest`
- Added `PushTokenProvider` abstraction and default `FirebasePushTokenProvider`.
- Added `AppCoroutineDispatchers` for centralized dispatcher wiring.
- Added versioned stable update notes source: `release/update-notes/v1.2.1.json`.

### Changed
- Finalized Android app version to `v1.2.1` for release builds.
- `versionCode` now resolves to stable code `1020199` from `versionName=v1.2.1`.
- Refactored `InboundMessageWorker` to:
  - use unique work enqueue (`KEEP`) for dedupe,
  - use exponential backoff and bounded retries,
  - enrich failure diagnostics with attempt count.
- Refactored `InboundMessageProcessor` to routed processing (`provider_wakeup` / `direct` / `drop`) with explicit runtime-unavailable signaling.
- Updated `PushGoMessagingService` ingress path to always enqueue worker processing first.
- Updated `SettingsRepository` setting flows with `distinctUntilChanged()` to reduce redundant emissions.
- Updated `ChannelSubscriptionService` IO usage to injected dispatcher path.
- Updated `MessageDetailScreen` and `MessageSearchScreen` to lifecycle-aware flow collection.
- Updated `EventListScreen` and `ThingListScreen` detail flows to keep selection synchronized with latest list snapshots and deep-link target resolution.
- Updated event/object list pagination to list-driven incremental loading for more stable long-list behavior.

### Fixed
- Fixed a startup-window ingress risk where messages could be ignored when runtime storage was not yet ready; now handled through worker retry semantics.
- Fixed event-list incremental load trigger to list-level snapshot observation.
- Fixed message-detail load failure surfacing with explicit error state handling.
- Fixed event/object detail actions by adding explicit close/delete confirmation flows and post-action data refresh.

## [v1.2.0] - 2026-04-20

### Added
- Added versioned stable update notes source: `release/update-notes/v1.2.0.json`.

### Changed
- Finalized Android app version to `v1.2.0` for release builds.
- `versionCode` now resolves to stable code `1020099` from `versionName=v1.2.0`.

### Fixed
- Improved blocked-install handling to show immediate in-app alerting and continue with one-tap system-installer fallback.
