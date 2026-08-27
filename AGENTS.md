# PushGo Android engineering rules

- A test proves product behavior only when it reaches a user-visible, Room, network, notification, service, or OS result. `testTag`, Manifest declarations, fixture files, version strings, and Automation State are diagnostics, not sufficient oracles.
- Map behavior changes to `docs/quality/capability-coverage.md` and add the smallest test that fails when the intended user outcome breaks.
- Run focused tests while editing and `scripts/quality_test.sh pr` before handing off product changes. If a change affects Compose UI, navigation, Room queries/migrations, persistent mutations, loading/error/retry behavior, or a quality fixture/test seam, also run `scripts/quality_test.sh device` on one representative emulator before handoff. The scheduled nightly lane provides daily device coverage without forcing the emulator cost onto every unrelated PR. For documentation/CI-only changes, syntax/static validation is sufficient when the product lane is explicitly `NOT RUN`.
- Keep broad input permutations in JVM/property/Room tests and a small number of representative Compose/device journeys. Do not create device × locale × state × fault Cartesian products.
- UI changes cover affected loading/content/empty/error/retry states and assert accurate displayed data or action results. Persistent behavior must be checked after repository recreation or relaunch where applicable.
- Performance work couples correct content with TTID/TTFD/frame/trace milestones in nightly/release scope; proxy file or timing checks do not count.
- Retries may recover classified emulator/runner faults only, never product assertion failures.
- Report `PASSED`, `FAILED`, `FLAKY`, `BLOCKED`, and `NOT RUN` separately. Emulator success does not prove real FCM, permission, Doze, reboot, installer, or physical accessibility behavior.
- Update the capability matrix and Apple workstream progress record when scope/evidence changes. The cross-platform policy is `../pushgo/design/workstreams/pushgo-quality-testing-overhaul/ai-development-policy.md` when both repositories are checked out as siblings.
