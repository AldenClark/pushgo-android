import re
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]


class QualityLaneCostContractTests(unittest.TestCase):
    def test_focused_host_jvm_requires_fresh_execution_without_weakening_full_lanes(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()

        self.assertIn('quality_minimum_free_bytes="${QUALITY_MIN_FREE_BYTES:-3221225472}"', runner)
        self.assertIn('"$lane" == "focused"', runner)
        self.assertIn('-n "${TEST_FILTER:-}"', runner)
        self.assertIn('-z "${ANDROID_TEST_CLASS:-}"', runner)
        self.assertIn("quality_minimum_free_bytes=1073741824", runner)
        self.assertIn('--minimum-free-bytes "$quality_minimum_free_bytes"', runner)

        focused_lane = re.search(
            r"  focused\)\n(?P<body>.*?)\n    ;;",
            runner,
            re.DOTALL,
        )
        self.assertIsNotNone(focused_lane)
        focused_body = focused_lane.group("body")
        self.assertIn('testDebugUnitTest --rerun-tasks --tests "$TEST_FILTER"', focused_body)
        self.assertIn('"$repo_root/app/build/test-results/testDebugUnitTest"', focused_body)
        self.assertIn("verify_device_tests_executed", focused_body)
        self.assertIn("No tests found for given includes:", focused_body)
        self.assertIn("focused_jvm_filter_matched_no_tests:$TEST_FILTER", focused_body)
        self.assertIn("exit 3", focused_body)

        for full_lane in ("pr", "device", "nightly", "release"):
            full_body = re.search(
                rf"  {full_lane}\)\n(?P<body>.*?)\n    ;;",
                runner,
                re.DOTALL,
            )
            self.assertIsNotNone(full_body)
            self.assertNotIn("quality_minimum_free_bytes=1073741824", full_body.group("body"))

    def test_full_host_jvm_stage_rejects_cached_zero_execution(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        host_stage = re.search(
            r"run_jvm_and_compile_device_tests\(\) \{(?P<body>.*?)\n\}",
            runner,
            re.DOTALL,
        )
        self.assertIsNotNone(host_stage)
        host_body = host_stage.group("body")
        self.assertIn('testDebugUnitTest --rerun-tasks', host_body)
        self.assertIn('"$repo_root/app/build/test-results/testDebugUnitTest"', host_body)
        self.assertIn("verify_device_tests_executed", host_body)
        self.assertLess(
            host_body.index("testDebugUnitTest --rerun-tasks"),
            host_body.index("compileDebugAndroidTestKotlin"),
        )

    def test_entity_tab_reselection_reuses_existing_positive_journeys(self) -> None:
        source = (
            REPO
            / "app/src/androidTest/java/io/ethan/pushgo/testing/QualityEntityJourneyInstrumentedTest.kt"
        ).read_text()
        fixture = (REPO / "app/src/main/java/io/ethan/pushgo/data/AppContainer.kt").read_text()
        runner = (REPO / "scripts/quality_test.sh").read_text()

        pr_scopes = runner.split('pr_device_scopes="', 1)[1].split('"', 1)[0]
        positive_scopes = runner.split('positive_device_scopes="', 1)[1].split('"', 1)[0]
        self.assertEqual(1, pr_scopes.count("eventClosePersistsAndOngoingFilterReflectsTheRealProjection"))
        self.assertEqual(1, positive_scopes.count("eventClosePersistsAndOngoingFilterReflectsTheRealProjection"))
        self.assertNotIn("thingFixtureShowsAccurateOverviewAndAllThreeRealRelationTabs", pr_scopes)
        self.assertEqual(1, positive_scopes.count("thingFixtureShowsAccurateOverviewAndAllThreeRealRelationTabs"))
        self.assertEqual(2, source.count("assertCurrentTabDoubleTapReturnsToTop(") - 1)
        self.assertEqual(2, fixture.count("(0 until 16).forEach"))
        self.assertIn('offscreenRowTag = "event.row.quality-event-navigation-00"', source)
        self.assertIn('offscreenRowTag = "thing.row.quality-thing-navigation-00"', source)
        self.assertIn("performScrollToIndex(16)", source)
        self.assertIn("performTouchInput { doubleClick() }", source)
        self.assertIn("assertIsNotDisplayed()", source)

    def test_changed_runner_help_exits_before_tests_or_stale_lane_selection(self) -> None:
        runner = (REPO / "scripts/quality_changed.sh").read_text()

        help_guard = runner.index('if [[ "${1:-}" == "-h"')
        script_tests = runner.index("python3 -m unittest discover")
        lane_execution = runner.index('exec "$repo_root/scripts/quality_test.sh"')
        self.assertLess(help_guard, script_tests)
        self.assertLess(help_guard, lane_execution)

    def test_changed_and_nested_runners_share_an_injectable_results_root(self) -> None:
        changed_runner = (REPO / "scripts/quality_changed.sh").read_text()
        lane_runner = (REPO / "scripts/quality_test.sh").read_text()
        performance_control = (
            REPO / "scripts/run_android_performance_negative_control.sh"
        ).read_text()
        update_runner = (REPO / "scripts/run_android_update_install_positive.sh").read_text()
        preparation_runner = (REPO / "scripts/run_android_preparation_contract.sh").read_text()

        self.assertIn(
            'results_root="${QUALITY_RESULTS_ROOT:-$repo_root/build/quality-results}"',
            changed_runner,
        )
        self.assertIn('export QUALITY_RESULTS_ROOT="$results_root"', changed_runner)
        self.assertIn(
            'results_root="${QUALITY_RESULTS_ROOT:-$repo_root/build/quality-results}"',
            lane_runner,
        )
        self.assertIn(
            'results_root="${QUALITY_RESULTS_ROOT:-$repo_root/build/quality-results}"',
            performance_control,
        )
        for nested_runner in (update_runner, preparation_runner):
            self.assertIn(
                'quality_results_root="${QUALITY_RESULTS_ROOT:-$repo_root/build/quality-results}"',
                nested_runner,
            )

    def test_ci_receipts_outlive_the_full_observation_window(self) -> None:
        workflow = (REPO / ".github/workflows/android-quality.yml").read_text()
        compact_uploads = re.findall(
            r"- name: Upload compact (?:host|device) receipts(?P<body>.*?)(?=\n\s*- name:|\Z)",
            workflow,
            re.DOTALL,
        )

        self.assertEqual(2, len(compact_uploads))
        for upload in compact_uploads:
            self.assertIn("if-no-files-found: error", upload)
            self.assertIn("retention-days: 21", upload)
            self.assertIn("path: build/quality-results/*-summary.json", upload)
            self.assertNotIn("app/build/reports", upload)
        diagnostic_uploads = re.findall(
            r"- name: Upload (?:JVM|device) evidence(?P<body>.*?)(?=\n\s*- name:|\Z)",
            workflow,
            re.DOTALL,
        )
        self.assertEqual(2, len(diagnostic_uploads))
        self.assertTrue(all("retention-days: 14" in upload for upload in diagnostic_uploads))
        self.assertTrue(
            all("!build/quality-results/*-summary.json" in upload for upload in diagnostic_uploads)
        )
        global_permissions = workflow.split("permissions:", 1)[1].split("concurrency:", 1)[0]
        self.assertNotIn("actions: read", global_permissions)
        observation_job = workflow.split("\n  observation:\n", 1)[1]
        self.assertIn("github.event.schedule == '43 18 * * *'", observation_job)
        self.assertIn("needs: device-regression", observation_job)
        self.assertIn("actions: read", observation_job)
        self.assertIn("quality_observation_collect.py", observation_job)
        self.assertIn("--workflow android-quality.yml", observation_job)
        self.assertIn("quality_observation.py", observation_job)
        self.assertIn("--artifact-name-prefix android-quality-receipts-", observation_job)
        self.assertNotIn("--artifact-name-prefix android- ", observation_job)
        self.assertNotIn("--require-ready", observation_job)
        self.assertIn("if-no-files-found: error", observation_job)

    def test_pr_ui_is_unique_discoverable_positive_breadth(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        pr_match = re.search(r'^pr_device_scopes="([^"]+)"$', runner, re.MULTILINE)
        positive_match = re.search(r'^positive_device_scopes="([^"]+)"$', runner, re.MULTILINE)
        self.assertIsNotNone(pr_match)
        self.assertIsNotNone(positive_match)
        pr_scopes = pr_match.group(1).split(",")
        positive_scopes = positive_match.group(1).split(",")
        self.assertEqual(len(pr_scopes), len(set(pr_scopes)))
        self.assertEqual(len(positive_scopes), len(set(positive_scopes)))
        self.assertEqual(6, len(pr_scopes))
        self.assertEqual(12, len(positive_scopes))
        self.assertTrue(set(pr_scopes).issubset(positive_scopes))

        sources = {
            path.stem: path.read_text()
            for path in (REPO / "app/src/androidTest/java/io/ethan/pushgo/testing").glob(
                "Quality*JourneyInstrumentedTest.kt"
            )
        }
        for scope in positive_scopes:
            class_name, method = scope.rsplit(".", 1)[-1].split("#", 1)
            self.assertIn(class_name, sources)
            self.assertRegex(sources[class_name], rf"\bfun\s+{re.escape(method)}\s*\(")
        for required_fragment in ("MessageJourney", "EntityJourney", "ChannelJourney", "SettingsJourney"):
            self.assertTrue(any(required_fragment in scope for scope in pr_scopes), required_fragment)
        for required_purpose in (
            "standardFixtureShowsAccurateContentAndSurvivesActivityRelaunch",
            "workflowFixtureLoadsSecondPageAndPersistsReadActions",
            "primaryNavigationUsesRealControlsAndReachesEveryProductScreen",
            "eventClosePersistsAndOngoingFilterReflectsTheRealProjection",
            "createRenameAndBothUnsubscribeOutcomesReachAccuratePersistentUserResults",
            "serverConfigurationRejectsInvalidInputAndScopesDataAfterRelaunch",
        ):
            self.assertTrue(any(required_purpose in scope for scope in pr_scopes), required_purpose)
        for deferred_fragment in ("Failure", "failure", "Corrupt", "corrupt", "Slow", "slow", "Delete", "delete", "Rejection"):
            self.assertFalse(any(deferred_fragment in scope for scope in pr_scopes), deferred_fragment)
        self.assertFalse(any("emptyFixtureShows" in scope for scope in pr_scopes))
        self.assertFalse(any("searchReturnsOnly" in scope for scope in pr_scopes))
        standard_source = sources["QualityMessageJourneyInstrumentedTest"]
        standard_journey = standard_source.split(
            "fun standardFixtureShowsAccurateContentAndSurvivesActivityRelaunch()", 1
        )[1].split("\n    @Test", 1)[0]
        self.assertIn("QualityMessageRefreshScenario.NEW_MESSAGE", standard_journey)
        self.assertIn('getByMessageId("quality-refresh-result")', standard_journey)
        self.assertIn('assertUnreadNavigationBadge("1")', standard_journey)
        self.assertIn('assertUnreadNavigationBadge(null)', standard_journey)
        self.assertIn("scenario = launchMainActivity()", standard_journey)
        self.assertNotIn(
            "refreshPersistsNewProviderResultOpensDetailAndSurvivesRelaunch",
            standard_source,
        )
        # This contract owns lane composition and cost only. Business outcomes stay
        # in executable device journeys; mirroring their tags or implementation
        # strings here would add maintenance cost without exercising the product.
        device_lane = re.search(
            r"  device\)\n(?P<body>.*?)\n    ;;",
            runner,
            re.DOTALL,
        )
        self.assertIsNotNone(device_lane)
        self.assertIn(
            'run_quality_device_classes "$positive_device_scopes"',
            device_lane.group("body"),
        )
        pr_ui_lane = re.search(
            r"  pr-ui\)\n(?P<body>.*?)\n    ;;",
            runner,
            re.DOTALL,
        )
        self.assertIsNotNone(pr_ui_lane)
        self.assertIn(
            'run_quality_device_classes "$pr_device_scopes"',
            pr_ui_lane.group("body"),
        )

        for lane in ("nightly", "release"):
            full_lane = re.search(
                rf"  {lane}\)\n(?P<body>.*?)\n    ;;",
                runner,
                re.DOTALL,
            )
            self.assertIsNotNone(full_lane)
            self.assertIn("run_quality_device_classes", full_lane.group("body"))
            self.assertNotIn("positive_device_scopes", full_lane.group("body"))

    def test_event_close_convergence_stays_in_one_positive_journey_with_app_owned_readiness(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        source = (
            REPO
            / "app/src/androidTest/java/io/ethan/pushgo/testing/QualityEntityJourneyInstrumentedTest.kt"
        ).read_text()
        base = (
            REPO
            / "app/src/androidTest/java/io/ethan/pushgo/testing/QualityAppJourneyTestCase.kt"
        ).read_text()
        scopes = runner.split('positive_device_scopes="', 1)[1].split('"', 1)[0]
        method_name = "eventClosePersistsAndOngoingFilterReflectsTheRealProjection"
        journey = source.split(f"fun {method_name}()", 1)[1].split(
            "fun eventCloseFailureKeepsAccurateDetailBlocksDuplicateAndRetryPersists()", 1
        )[0]

        self.assertEqual(1, scopes.count(method_name))
        for purpose in (
            "event.close.cancel",
            "event.filters.ongoing",
            "nav.item.things",
            "field.event.detail.status.closed",
            "event.timeline.count.3",
        ):
            self.assertIn(purpose, journey)
        self.assertIn("fixtureInitializationWasRecorded(app.filesDir)", base)
        self.assertIn("app.startupStorageErrorMessage() == null", base)
        self.assertIn("PushGoAutomation.currentRuntimeErrorCount() == 0", base)

    def test_gateway_positive_journey_proves_the_first_post_commit_business_operation(self) -> None:
        journey_source = (
            REPO
            / "app/src/androidTest/java/io/ethan/pushgo/testing/QualitySettingsJourneyInstrumentedTest.kt"
        ).read_text()
        repository = (
            REPO / "app/src/main/java/io/ethan/pushgo/data/ChannelSubscriptionRepository.kt"
        ).read_text()
        runtime_fake = (
            REPO / "app/src/main/java/io/ethan/pushgo/data/AppContainer.kt"
        ).read_text()
        journey = journey_source.split(
            "fun serverConfigurationRejectsInvalidInputAndScopesDataAfterRelaunch()", 1
        )[1].split("fun gatewayLocalCommitFailureRollsBackBeforeRetryCommits()", 1)[0]

        self.assertIn("expectedChannelMutationGatewayUrl = normalizedAddress", journey)
        self.assertIn('onNodeWithTag("action.channels.add")', journey)
        self.assertIn('onNodeWithTag("channel.row.01H00000000000000000000003")', journey)
        self.assertIn('assertTextContains("New Gateway Channel")', journey)
        self.assertIn("suspend fun ensureProviderRoute(gatewayUrl: String", repository)
        self.assertGreaterEqual(repository.count("gatewayUrl: String"), 4)
        self.assertNotIn("fun requireGateway", repository)
        self.assertGreaterEqual(runtime_fake.count("requireExpectedGateway(gatewayUrl)"), 4)
        self.assertIn("quality channel operation was routed through the wrong gateway", runtime_fake)

    def test_update_install_is_strict_positive_release_evidence(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        update_runner = (REPO / "scripts/run_android_update_install_positive.sh").read_text()
        legacy_matrix = (REPO / "scripts/device_update_e2e_matrix.sh").read_text()
        installer = (
            REPO / "app/src/main/java/io/ethan/pushgo/update/UpdateInstaller.kt"
        ).read_text()

        self.assertRegex(
            runner,
            r"  update-install\)\n\s+run_update_install_positive\n\s+;;",
        )
        release_lane = re.search(
            r"  release\)\n(?P<body>.*?)\n    ;;",
            runner,
            re.DOTALL,
        )
        self.assertIsNotNone(release_lane)
        self.assertIn("run_update_install_positive", release_lane.group("body"))

        self.assertIn('[[ "$installed_version" == "$candidate_version_code" ]]', update_runner)
        self.assertIn('wait_for_node resource "quality-runtime.ready"', update_runner)
        self.assertIn('wait_for_node resource "message.row.quality-standard-message"', update_runner)
        self.assertIn('wait_for_node text "P2 Split Seed Message"', update_runner)
        self.assertIn(
            'wait_for_node text "Seeded from fixture.seed_messages for UI validation."',
            update_runner,
        )
        self.assertIn("adb_with_timeout()", update_runner)
        self.assertIn("QUALITY_ADB_TIMEOUT_SECONDS", update_runner)
        self.assertIn("capture_failure_evidence()", update_runner)
        self.assertIn("failure-metadata.txt", update_runner)
        self.assertIn("acquire_device_lock()", update_runner)
        self.assertIn("device_lock_acquired", update_runner)
        self.assertIn("isolated benchmark package is already installed", update_runner)
        self.assertNotIn("adb -s ", update_runner)
        self.assertNotIn("B006 accepted", legacy_matrix)
        self.assertIn("guidance or installer handoff is not installation success", legacy_matrix)
        self.assertIn("archiveInfo.signingInfo ?: return false", installer)
        self.assertIn("signingInfo.apkContentsSigners ?: return false", installer)
        self.assertIn("if (signers.isEmpty()) return false", installer)

    def test_one_migration_representative_reaches_real_ui_without_a_version_matrix(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        migration = (
            REPO
            / "app/src/androidTest/java/io/ethan/pushgo/data/db/PushGoDatabaseMigrationDeviceTest.kt"
        ).read_text()

        self.assertIn("io.ethan.pushgo.data.db.PushGoDatabaseMigrationDeviceTest", runner)
        self.assertEqual(1, migration.count("ActivityScenario.launch("))
        self.assertIn('onNodeWithText("Legacy title")', migration)
        self.assertIn('onNodeWithTag("field.message.detail.body")', migration)
        self.assertIn('assertTextContains("Legacy body")', migration)

    def test_private_service_system_journey_shares_nightly_install_and_stays_out_of_daily(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        system_class = "io.ethan.pushgo.testing.QualityPrivateForegroundServiceJourneyInstrumentedTest"

        self.assertIn(system_class, runner)
        self.assertNotIn(system_class, runner.split('positive_device_scopes="', 1)[1].split('"', 1)[0])
        self.assertIn('run_system_notification_journeys', runner)
        self.assertNotIn('run_private_foreground_service_journey', runner)

    def test_exact_entity_notification_routes_share_one_system_method_and_stay_out_of_daily(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        journey = (
            REPO
            / "app/src/androidTest/java/io/ethan/pushgo/testing/QualitySystemNotificationJourneyInstrumentedTest.kt"
        ).read_text()
        system_class = "io.ethan.pushgo.testing.QualitySystemNotificationJourneyInstrumentedTest"
        method = journey.split(
            "fun entityInboundNotificationsOpenExactColdEventAndWarmThingDetails()", 1
        )[1].split("private fun grantAndVerifyNotificationPermission()", 1)[0]

        self.assertIn(system_class, runner)
        self.assertNotIn(system_class, runner.split('positive_device_scopes="', 1)[1].split('"', 1)[0])
        self.assertEqual(1, journey.count("entityInboundNotificationsOpenExactColdEventAndWarmThingDetails"))
        self.assertIn('"entity_type" to "event"', method)
        self.assertIn('"entity_type" to "thing"', method)
        self.assertIn("openExactSystemNotification(device, eventTitle, eventSummary)", method)
        self.assertIn("openExactSystemNotification(device, thingTitle, thingSummary)", method)
        self.assertIn("openExactSystemNotification(device, messageTitle, messageBody)", method)
        self.assertIn('hasTestTag("sheet.event.detail")', method)
        self.assertIn('hasTestTag("sheet.thing.detail")', method)
        self.assertIn('hasTestTag("sheet.message.detail")', method)
        self.assertIn('onNodeWithTag("sheet.thing.detail").assertDoesNotExist()', method)
        self.assertIn('onNodeWithTag("event.row.$eventId"', method)
        self.assertIn('onNodeWithTag("thing.row.$thingId"', method)
        self.assertIn("exact Message/Event/Thing cold-warm notification routes", runner)

    def test_notification_permission_uses_one_host_driven_positive_journey_only_in_system_lane(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        host_journey = (REPO / "scripts/run_android_notification_permission_positive.sh").read_text()
        identity_verifier = (REPO / "scripts/verify_android_instrumentation_identity.py").read_text()
        build = (REPO / "app/build.gradle.kts").read_text()
        manifest = (REPO / "app/src/main/AndroidManifest.xml").read_text()

        self.assertEqual(1, runner.count('run_android_notification_permission_positive.sh'))
        self.assertIn('run_system_notification_journeys', runner)
        self.assertIn('permission_deny_button', host_journey)
        self.assertIn('action.delivery_guard.confirm', host_journey)
        self.assertIn('main_switch_bar', host_journey)
        self.assertIn('QualityNotificationPermissionJourneyInstrumentedTest', host_journey)
        self.assertIn('test_method="enabledSystemDecisionRefreshesTheRealAppAndRemovesDisabledDeliveryState"', host_journey)
        self.assertIn('-e class "$test_selector"', host_journey)
        self.assertIn('verify_android_instrumentation_identity.py', host_journey)
        self.assertIn('INSTRUMENTATION_STATUS: class=', identity_verifier)
        self.assertIn('INSTRUMENTATION_STATUS: test=', identity_verifier)
        self.assertIn('ro.kernel.qemu', host_journey)
        self.assertIn('restore_permission', host_journey)
        self.assertIn('CLEAR_SESSION', host_journey)
        self.assertIn('acquire_device_lock()', host_journey)
        self.assertIn('release_device_lock()', host_journey)
        self.assertIn('device_lock_acquired', host_journey)
        self.assertIn('QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS', host_journey)
        self.assertIn('baseline_captured', host_journey)
        self.assertIn('getByName("debug")', build)
        self.assertIn('kotlin.directories.add("src/benchmark/java/io/ethan/pushgo/testing")', build)
        self.assertIn('android:permission="android.permission.DUMP"', manifest)
        pr_ui_case = runner.split('  pr-ui)', 1)[1].split('    ;;', 1)[0]
        self.assertNotIn('run_android_notification_permission_positive.sh', pr_ui_case)
        self.assertIn('grep -q \'^cleanup_status=FAILED$\'', runner)
        self.assertIn('mark_planned_controlled_system_profile "notification-permission"', runner)
        self.assertIn('mark_planned_controlled_system_profile "system-notification"', runner)

    def test_doze_recovery_and_snooze_share_the_system_install_and_stay_out_of_daily(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        journey = (REPO / "scripts/run_android_doze_positive.sh").read_text()

        self.assertEqual(1, runner.count('run_android_doze_positive.sh'))
        self.assertIn('QUALITY_ANDROID_SKIP_INSTALL=1', runner)
        self.assertIn('run_system_notification_journeys', runner)
        self.assertNotIn(
            'run_android_doze_positive.sh',
            runner.split('positive_device_scopes="', 1)[1].split('"', 1)[0],
        )
        self.assertIn('"system_capabilities": ["doze_reminder_journey"]', journey)
        self.assertIn('banner.settings.doze_enabled', journey)
        self.assertIn('action.settings.open_battery_optimization_settings', journey)
        self.assertIn('action.settings.snooze_doze_reminder', journey)
        self.assertIn('android:id/button1', journey)
        self.assertIn('cmd deviceidle whitelist -"$package_name"', journey)
        self.assertIn('restore_battery_optimization', journey)
        self.assertIn('prepare_session "android-doze-isolation-', journey)
        self.assertNotIn('run-as', journey)
        self.assertIn('device_ui_dump="/data/local/tmp/pushgo-doze-positive-$run_id.xml"', journey)
        self.assertIn('wait_for_node leaves the last successful dump in ui_dump', journey)
        self.assertNotIn('dump_ui || failed "UI tree could not be captured before tapping', journey)
        self.assertIn('last_dump_failure', journey)
        self.assertIn('adb_with_timeout()', journey)
        self.assertIn('QUALITY_ADB_TIMEOUT_SECONDS', journey)
        self.assertIn('capture_failure_evidence()', journey)
        self.assertIn('failure_evidence_dir', journey)
        self.assertIn('acquire_device_lock()', journey)
        self.assertIn('device_lock_acquired', journey)

    def test_slow_load_performance_negative_control_runs_only_with_performance(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        control = (REPO / "scripts/run_android_performance_negative_control.sh").read_text()

        self.assertEqual(1, runner.count('run_android_performance_negative_control.sh'))
        performance_function = runner.split("run_performance() {", 1)[1].split(
            "run_accessibility_localization() {", 1
        )[0]
        self.assertIn('run_android_performance_negative_control.sh', performance_function)
        positive_complete = performance_function.index(
            'claims+=("Release-like Macrobenchmark mechanics with exact 1k startup/detail product Oracle'
        )
        negative_control = performance_function.index(
            'run_android_performance_negative_control.sh'
        )
        self.assertLess(positive_complete, negative_control)
        for lane in ("pr", "pr-ui", "device", "nightly"):
            lane_body = runner.split(f"  {lane})", 1)[1].split("    ;;", 1)[0]
            self.assertNotIn('run_android_performance_negative_control.sh', lane_body)
        self.assertIn('pushgo.fixtureLoadDelayMs', control)
        self.assertIn('cold startup-to-accurate-content took', control)
        self.assertIn('"product_status": "NOT_RUN"', control)
        self.assertIn('"test_system_status": "PASSED"', control)
        self.assertNotIn('sleep ', control)
        self.assertNotIn('--rerun-tasks', control)
        self.assertRegex(
            runner,
            r'elif \[\[ \$status -eq 4 \]\]; then\n'
            r'\s+write_result NOT_RUN FAILED "a required test-system sensitivity control',
        )

    def test_system_lanes_do_not_report_their_controlled_doze_journey_as_not_run(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()

        initial_not_run = runner.split("not_run=(", 1)[1].split(")", 1)[0]
        self.assertNotIn("Doze", initial_not_run)
        self.assertIn(
            'if [[ "$lane" != "nightly" && "$lane" != "release" ]]; then',
            runner,
        )
        self.assertIn(
            'controlled-emulator notification permission, Doze, system notification, and Private Service journeys',
            runner,
        )
        self.assertIn(
            'physical/OEM notification, Doze, and Private Service behavior beyond the controlled-emulator system journeys',
            runner,
        )

    def test_process_restart_reuses_the_system_install_and_proves_a_new_real_process(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        host_journey = (REPO / "scripts/run_android_process_restart_positive.sh").read_text()

        self.assertEqual(1, runner.count('run_android_process_restart_positive.sh'))
        self.assertIn('QUALITY_ANDROID_SKIP_INSTALL=1', runner)
        self.assertIn('run_system_notification_journeys', runner)
        self.assertIn('shell pidof "$package_name"', host_journey)
        self.assertIn('shell am force-stop "$package_name"', host_journey)
        self.assertIn('[[ "$second_pid" != "$first_pid" ]]', host_journey)
        self.assertIn('message.row.quality-standard-message', host_journey)
        self.assertIn('action.messages.mark_all_read', host_journey)
        self.assertIn('action.message.open_url', host_journey)
        self.assertIn('cmd package resolve-activity --brief', host_journey)
        self.assertIn('node_text url_bar', host_journey)
        self.assertIn('dat=$expected_handoff_url', host_journey)
        self.assertIn('wrong-url', host_journey)
        self.assertIn('return_from_browser()', host_journey)
        self.assertIn('for _ in 1 2 3; do', host_journey)
        browser_back = host_journey.index('shell input keyevent KEYCODE_BACK')
        returned_detail = host_journey.index(
            'return_from_browser "sheet.message.detail"',
            browser_back,
        )
        external_surface_closed = host_journey.index(
            'external_surface_open=0',
            returned_detail,
        )
        self.assertLess(browser_back, returned_detail)
        self.assertLess(returned_detail, external_surface_closed)
        self.assertIn('row.settings.docs.getting_started', host_journey)
        self.assertIn('pushgo.dev/guides/getting-started/', host_journey)
        self.assertIn('return_from_browser "screen.settings"', host_journey)
        self.assertIn('Seeded from fixture.seed_messages for UI validation.', host_journey)
        self.assertIn('QUALITY_ORACLE_NEGATIVE_CONTROL', host_journey)
        self.assertNotIn('QUALITY_PROCESS_RESTART_EXPECTED_BODY', host_journey)
        self.assertIn('CLEAR_SESSION', host_journey)
        self.assertIn('adb_with_timeout()', host_journey)
        self.assertIn('QUALITY_ADB_TIMEOUT_SECONDS', host_journey)
        self.assertIn('acquire_device_lock()', host_journey)
        self.assertIn('QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS', host_journey)
        self.assertIn('/data/local/tmp/pushgo-process-restart-$run_id.xml', host_journey)
        self.assertIn('capture_failure_evidence()', host_journey)
        self.assertIn('last_dump_failure', host_journey)
        self.assertNotIn('dump_ui || failed "UI tree could not be captured before tapping', host_journey)
        self.assertNotIn('run_android_process_restart_positive.sh', runner.split('  pr-ui)', 1)[1].split('    ;;', 1)[0])

    def test_changed_device_workflow_consumes_structured_profiled_scopes(self) -> None:
        workflow = (REPO / ".github/workflows/android-quality.yml").read_text()
        changed_runner = (REPO / "scripts/quality_changed.sh").read_text()

        self.assertIn('required_device_scopes="$(python3 -c', workflow)
        self.assertIn('echo "QUALITY_LANE=planned-device" >> "$GITHUB_ENV"', workflow)
        self.assertNotIn('echo "ANDROID_TEST_CLASS=$required_device_scopes" >> "$GITHUB_ENV"', workflow)
        self.assertIn('if [[ "$phase" == "full" ]]; then', changed_runner)
        self.assertIn('"$repo_root/scripts/quality_test.sh" pr', changed_runner)
        self.assertIn('lane="planned-device"', changed_runner)
        self.assertNotIn('export ANDROID_TEST_CLASS="$required_device_scopes"', changed_runner)
        quality_runner = (REPO / "scripts/quality_test.sh").read_text()
        self.assertIn('quality_planned_device_runs.py', quality_runner)
        self.assertIn("read -r profile scopes expected_count <&3", quality_runner)
        self.assertIn('done 3<<< "$run_lines"', quality_runner)
        self.assertNotIn('done <<< "$run_lines"', quality_runner)


if __name__ == "__main__":
    unittest.main()
