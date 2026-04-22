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
