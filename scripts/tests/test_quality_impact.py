import importlib.util
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("quality_impact", REPO / "scripts/quality_impact.py")
assert SPEC and SPEC.loader
QUALITY_IMPACT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUALITY_IMPACT)


class QualityImpactPlanTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = QUALITY_IMPACT.load_manifest(REPO / "config/quality-impact.json")

    def plan(self, *paths):
        return QUALITY_IMPACT.build_plan(list(paths), self.manifest, "unit-test")

    @staticmethod
    def kotlin_test_source(changed_value: str = "extended") -> str:
        return f"""package io.ethan.pushgo.testing

import org.junit.Test

class RuntimeExtendedJourneyInstrumentedTest {{
    @Test
    fun routinePositive() {{
        check("positive".isNotEmpty())
    }}

    @Test
    fun extendedRiskRecovery() {{
        check("{changed_value}".isNotEmpty())
    }}

    private fun sharedFixture() = Unit
}}
"""

    def test_changed_extended_method_replaces_unrelated_fixed_positive_selection(self):
        path = (
            "app/src/androidTest/java/io/ethan/pushgo/testing/"
            "RuntimeExtendedJourneyInstrumentedTest.kt"
        )
        old_source = self.kotlin_test_source("before")
        new_source = self.kotlin_test_source("after")
        changed_line = next(
            index for index, line in enumerate(new_source.splitlines(), 1) if 'check("after"' in line
        )
        scopes = QUALITY_IMPACT.exact_changed_test_scopes(
            path,
            old_source,
            new_source,
            f"@@ -{changed_line} +{changed_line} @@\n-check before\n+check after\n",
        )
        expected = (
            "io.ethan.pushgo.testing.RuntimeExtendedJourneyInstrumentedTest"
            "#extendedRiskRecovery"
        )
        self.assertEqual([expected], scopes)

        plan = QUALITY_IMPACT.build_plan(
            [path], self.manifest, "unit-test", {path: scopes}
        )

        self.assertEqual("pr-ui", plan["recommended_lane"])
        self.assertEqual("focused", plan["device_scope_selection"])
        self.assertEqual("exact-method", plan["instrumented_test_scope_selection"])
        self.assertEqual([expected], plan["required_device_scopes"])
        self.assertEqual(
            [{"profile": "generic", "scopes": [expected], "expected_test_count": 1}],
            plan["required_device_runs"],
        )

    def test_changed_test_helper_falls_back_to_changed_class(self):
        path = (
            "app/src/androidTest/java/io/ethan/pushgo/testing/"
            "RuntimeExtendedJourneyInstrumentedTest.kt"
        )
        old_source = self.kotlin_test_source()
        new_source = old_source.replace("sharedFixture() = Unit", "sharedFixture() = checkNotNull(Unit)")
        changed_line = next(
            index for index, line in enumerate(new_source.splitlines(), 1) if "checkNotNull" in line
        )
        impact = QUALITY_IMPACT.resolve_kotlin_instrumented_test_change(
            path,
            old_source,
            new_source,
            f"@@ -{changed_line} +{changed_line} @@\n-old helper\n+new helper\n",
        )
        plan = QUALITY_IMPACT.build_plan(
            [path], self.manifest, "unit-test", {path: impact}
        )

        self.assertEqual("pr-ui", plan["recommended_lane"])
        self.assertEqual("focused", plan["device_scope_selection"])
        self.assertEqual("changed-class", plan["instrumented_test_scope_selection"])
        self.assertEqual(2, len(plan["required_device_scopes"]))
        self.assertTrue(all("#" in scope for scope in plan["required_device_scopes"]))
        self.assertEqual(2, plan["required_device_runs"][0]["expected_test_count"])

    def test_pure_assertion_insertion_and_deletion_stay_on_the_existing_method(self):
        path = "app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeExtendedJourneyInstrumentedTest.kt"
        base = self.kotlin_test_source("before")
        inserted = '        check("second".isNotEmpty())\n'
        with_insert = base.replace(
            '        check("before".isNotEmpty())\n',
            '        check("before".isNotEmpty())\n' + inserted,
        )
        inserted_line = next(
            index for index, line in enumerate(with_insert.splitlines(), 1) if '"second"' in line
        )
        insertion = QUALITY_IMPACT.resolve_kotlin_instrumented_test_change(
            path,
            base,
            with_insert,
            f"@@ -{inserted_line - 1},0 +{inserted_line},1 @@\n+assertion\n",
        )
        deletion = QUALITY_IMPACT.resolve_kotlin_instrumented_test_change(
            path,
            with_insert,
            base,
            f"@@ -{inserted_line},1 +{inserted_line - 1},0 @@\n-assertion\n",
        )

        expected = [
            "io.ethan.pushgo.testing.RuntimeExtendedJourneyInstrumentedTest#extendedRiskRecovery"
        ]
        self.assertEqual("exact-method", insertion["selection"])
        self.assertEqual(expected, insertion["scopes"])
        self.assertEqual("exact-method", deletion["selection"])
        self.assertEqual(expected, deletion["scopes"])

    def test_adjacent_method_hunk_selects_every_covered_method(self):
        path = "app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeExtendedJourneyInstrumentedTest.kt"
        old_source = self.kotlin_test_source("before")
        new_source = old_source.replace(
            '        check("before".isNotEmpty())\n',
            '        check("before".isNotEmpty())\n'
            '        check("new assertion".isNotEmpty())\n'
            '    }\n\n'
            '    @Test\n'
            '    fun newAdjacentJourney() {\n'
            '        check("new".isNotEmpty())\n',
        )
        impact = QUALITY_IMPACT.resolve_kotlin_instrumented_test_change(
            path,
            old_source,
            new_source,
            "@@ -15,0 +15,7 @@\n+adjacent methods\n",
        )
        self.assertEqual("exact-method", impact["selection"])
        self.assertEqual(
            [
                "io.ethan.pushgo.testing.RuntimeExtendedJourneyInstrumentedTest#extendedRiskRecovery",
                "io.ethan.pushgo.testing.RuntimeExtendedJourneyInstrumentedTest#newAdjacentJourney",
            ],
            impact["scopes"],
        )

    def test_removed_test_method_and_deleted_source_block_before_execution(self):
        path = "app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeExtendedJourneyInstrumentedTest.kt"
        old_source = self.kotlin_test_source()
        removed_block = '''    @Test
    fun extendedRiskRecovery() {
        check("extended".isNotEmpty())
    }

'''
        new_source = old_source.replace(removed_block, "")
        old_line = next(
            index for index, line in enumerate(old_source.splitlines(), 1) if "@Test" in line and index > 8
        )
        removed = QUALITY_IMPACT.resolve_kotlin_instrumented_test_change(
            path,
            old_source,
            new_source,
            f"@@ -{old_line},5 +{old_line},0 @@\n",
        )
        deleted = QUALITY_IMPACT.resolve_kotlin_instrumented_test_change(
            path, old_source, None, "@@ -1,18 +0,0 @@\n"
        )

        self.assertEqual("blocked", removed["selection"])
        self.assertIn("removed or renamed", removed["blocker"])
        self.assertEqual("blocked", deleted["selection"])
        self.assertIn("was deleted", deleted["blocker"])

    def test_instrumented_test_file_rename_is_explicitly_blocked(self):
        old_path = "app/src/androidTest/java/io/ethan/pushgo/testing/RuntimeExtendedJourneyInstrumentedTest.kt"
        new_path = "app/src/androidTest/java/io/ethan/pushgo/testing/RenamedJourneyInstrumentedTest.kt"
        args = SimpleNamespace(base=None, head="HEAD")
        with mock.patch.object(
            QUALITY_IMPACT,
            "git_text",
            return_value=f"R100\t{old_path}\t{new_path}\n",
        ):
            impacts = QUALITY_IMPACT.instrumented_test_impacts(
                args, REPO, [new_path], "working-tree"
            )
        plan = QUALITY_IMPACT.build_plan(
            [new_path], self.manifest, "working-tree", impacts
        )

        self.assertEqual("BLOCKED", plan["plan_status"])
        self.assertIn("was renamed", plan["selection_blockers"][0])

    def test_shared_journey_base_change_selects_every_runnable_consumer_by_profile(self):
        path = "app/src/androidTest/java/io/ethan/pushgo/testing/QualityAppJourneyTestCase.kt"
        impact = QUALITY_IMPACT.shared_test_support_consumer_impact(
            REPO, path, (REPO / path).read_text()
        )
        self.assertIsNotNone(impact)
        plan = QUALITY_IMPACT.build_plan(
            [path], self.manifest, "unit-test", {path: impact}
        )

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("changed-consumers", plan["instrumented_test_scope_selection"])
        profiles = {
            run["profile"]: len(run["scopes"])
            for run in plan["required_device_runs"]
        }
        self.assertEqual(
            {"accessibility": 1, "app-owned": 33, "system-notification": 3},
            profiles,
        )
        self.assertEqual(37, len(plan["required_device_scopes"]))

    def test_unknown_changed_instrumented_class_forces_full_lane(self):
        path = "app/src/androidTest/java/io/ethan/pushgo/testing/Unknown.kt"
        plan = QUALITY_IMPACT.build_plan([path], self.manifest, "unit-test", {path: []})

        self.assertEqual("BLOCKED", plan["plan_status"])
        self.assertEqual("pr-ui", plan["recommended_lane"])
        self.assertEqual("full-lane", plan["device_scope_selection"])
        self.assertEqual("full-lane", plan["instrumented_test_scope_selection"])
        self.assertEqual([], plan["required_device_scopes"])
        self.assertEqual(
            [f"unable to resolve a runnable changed instrumented test class: {path}"],
            plan["selection_blockers"],
        )

    def test_non_test_hunk_cannot_be_misattributed_to_a_green_test_method(self):
        path = (
            "app/src/androidTest/java/io/ethan/pushgo/testing/"
            "RuntimeExtendedJourneyInstrumentedTest.kt"
        )
        old_source = self.kotlin_test_source()
        new_source = old_source.replace("sharedFixture() = Unit", "sharedFixture() = checkNotNull(Unit)")
        changed_line = next(
            index for index, line in enumerate(new_source.splitlines(), 1) if "checkNotNull" in line
        )

        self.assertIsNone(
            QUALITY_IMPACT.exact_changed_test_scopes(
                path,
                old_source,
                new_source,
                f"@@ -{changed_line} +{changed_line} @@\n-old helper\n+new helper\n",
            )
        )

    def test_message_viewmodel_selects_device_evidence(self):
        plan = self.plan("app/src/main/java/io/ethan/pushgo/ui/viewmodel/MessageListViewModel.kt")

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("device", plan["recommended_lane"])
        self.assertIn("messages", plan["impacted_capabilities"])
        self.assertIn("Android accurate content/search/delete/relaunch UI journeys", plan["minimum_evidence"])
        self.assertIn(
            "io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#failedLoadShowsUsableRetryAndRecoversToTheCanonicalMessageDetail",
            plan["required_device_scopes"],
        )

    def test_search_viewmodel_selects_exact_recovery_device_oracle(self):
        plan = self.plan("app/src/main/java/io/ethan/pushgo/ui/viewmodel/MessageSearchViewModel.kt")

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("device", plan["recommended_lane"])
        self.assertIn("search-filter", plan["impacted_capabilities"])
        self.assertIn(
            "Android failed search must not masquerade as empty or stale results; Retry reaches one exact canonical result and matching detail",
            plan["minimum_evidence"],
        )
        self.assertIn(
            "io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#searchReturnsOnlyTheTargetAndOpensItsRealDetail",
            plan["required_device_scopes"],
        )

    def test_entity_screen_change_prefers_positive_pr_ui_evidence(self):
        plan = self.plan("app/src/main/java/io/ethan/pushgo/ui/screens/ThingListScreen.kt")

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("pr-ui", plan["recommended_lane"])
        self.assertTrue({"events", "things"}.issubset(plan["impacted_capabilities"]))
        self.assertEqual(2, len(plan["required_device_scopes"]))
        self.assertTrue(
            all("QualityEntityJourneyInstrumentedTest#" in scope for scope in plan["required_device_scopes"])
        )

    def test_entity_data_owner_change_keeps_device_evidence(self):
        plan = self.plan("app/src/main/java/io/ethan/pushgo/data/EntityProjectionRepository.kt")

        self.assertEqual("device", plan["recommended_lane"])
        self.assertEqual([], plan["required_device_scopes"])

    def test_navigation_change_selects_reachability_and_badge_positive_scopes(self):
        plan = self.plan("app/src/main/java/io/ethan/pushgo/ui/PushGoAppRoot.kt")

        self.assertEqual("pr-ui", plan["recommended_lane"])
        self.assertEqual(3, len(plan["required_device_scopes"]))
        self.assertTrue(any("primaryNavigation" in scope for scope in plan["required_device_scopes"]))
        self.assertTrue(any("workflowFixture" in scope for scope in plan["required_device_scopes"]))
        self.assertTrue(
            any(
                "deleteWithoutUndoPermanentlyRemovesOnlyTargetAcrossStorageRecreation" in scope
                for scope in plan["required_device_scopes"]
            )
        )

    def test_same_lane_rule_without_scopes_forces_full_lane(self):
        plan = self.plan(
            "app/src/main/java/io/ethan/pushgo/ui/screens/ThingListScreen.kt",
            "app/src/androidTest/java/io/ethan/pushgo/testing/NewJourneyInstrumentedTest.kt",
        )

        self.assertEqual("pr-ui", plan["recommended_lane"])
        self.assertEqual("full-lane", plan["device_scope_selection"])
        self.assertEqual([], plan["required_device_scopes"])

    def test_lower_lane_rule_does_not_prevent_safe_pr_ui_focus(self):
        plan = self.plan(
            "app/src/main/java/io/ethan/pushgo/ui/screens/ThingListScreen.kt",
            "scripts/tests/test_quality_impact.py",
        )

        self.assertEqual("pr-ui", plan["recommended_lane"])
        self.assertEqual("focused", plan["device_scope_selection"])
        self.assertEqual(2, len(plan["required_device_scopes"]))

    def test_room_schema_expands_across_capabilities(self):
        plan = self.plan("app/src/main/java/io/ethan/pushgo/data/db/PushGoDatabase.kt")

        self.assertEqual("device", plan["recommended_lane"])
        self.assertTrue({"messages", "events", "things", "ingress-ack"}.issubset(plan["impacted_capabilities"]))
        self.assertTrue(plan["known_evidence_gaps"])

    def test_runtime_composition_requires_release_isolation(self):
        plan = self.plan("app/src/main/java/io/ethan/pushgo/data/AppContainer.kt")

        self.assertEqual("release", plan["recommended_lane"])
        self.assertIn("release-runtime-isolation", plan["impacted_capabilities"])
        self.assertIn("android-preparation-contract", plan["required_checks"])

    def test_app_owned_provider_change_selects_the_dedicated_preparation_contract(self):
        plan = self.plan(
            "app/src/benchmark/java/io/ethan/pushgo/testing/BenchmarkFixtureProvider.kt"
        )

        self.assertEqual("READY", plan["plan_status"])
        self.assertIn("android-preparation-contract", plan["required_checks"])
        self.assertIn("app-owned-quality-runtime", plan["impacted_capabilities"])

    def test_unmapped_product_screen_is_blocked(self):
        path = "app/src/main/java/io/ethan/pushgo/ui/screens/NewCapabilityScreen.kt"
        plan = self.plan(path)

        self.assertEqual("BLOCKED", plan["plan_status"])
        self.assertEqual([path], plan["unmapped_product_paths"])

    def test_document_only_change_runs_no_product_lane(self):
        plan = self.plan("README.md", "docs/quality/capability-coverage.md")

        self.assertEqual("NOT_RUN", plan["plan_status"])
        self.assertEqual("not-run", plan["recommended_lane"])
        self.assertFalse(plan["manual_impact_review_required"])

    def test_selector_test_change_requires_representative_quality_evidence(self):
        plan = self.plan("scripts/tests/test_quality_impact.py")

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("pr", plan["recommended_lane"])
        self.assertIn("quality-system-trustworthiness", plan["impacted_capabilities"])

    def test_doze_runner_change_is_not_ignored_by_quality_impact(self):
        plan = self.plan("scripts/run_android_doze_positive.sh")

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("pr", plan["recommended_lane"])
        self.assertIn("quality-system-trustworthiness", plan["impacted_capabilities"])
        self.assertNotIn("scripts/run_android_doze_positive.sh", plan["ignored_paths"])

    def test_instrumented_test_change_requires_device_execution(self):
        plan = self.plan("app/src/androidTest/java/io/ethan/pushgo/testing/NewJourneyInstrumentedTest.kt")

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("pr-ui", plan["recommended_lane"])
        self.assertIn(
            "exact changed @Test method when diff attribution is reliable, otherwise every runnable method in the changed instrumented class; required execution profiles and exact fresh XML selector identities must match before evidence is accepted",
            plan["minimum_evidence"],
        )

    def test_system_notification_journey_change_runs_its_nightly_system_surface(self):
        plan = self.plan(
            "app/src/androidTest/java/io/ethan/pushgo/testing/QualitySystemNotificationJourneyInstrumentedTest.kt"
        )

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("nightly", plan["recommended_lane"])
        self.assertIn("notification-system-route", plan["impacted_capabilities"])
        self.assertIn("alert-playback", plan["impacted_capabilities"])
        self.assertIn(
            "focused durable critical inbound to system notification/USAGE_ALARM playback/PendingIntent/stop journey",
            plan["minimum_evidence"],
        )
        self.assertEqual("focused", plan["device_scope_selection"])
        self.assertEqual(3, len(plan["required_device_scopes"]))

    def test_private_service_journey_change_runs_its_nightly_system_surface(self):
        plan = self.plan(
            "app/src/androidTest/java/io/ethan/pushgo/testing/QualityPrivateForegroundServiceJourneyInstrumentedTest.kt"
        )

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("nightly", plan["recommended_lane"])
        self.assertIn("private-foreground-service", plan["impacted_capabilities"])
        self.assertIn(
            "focused Settings to Private foreground Service/system notification/persistence/stop journey",
            plan["minimum_evidence"],
        )
        self.assertEqual("focused", plan["device_scope_selection"])
        self.assertEqual(3, len(plan["required_device_scopes"]))

    def test_notification_owner_change_selects_minimum_system_and_durable_boundaries(self):
        plan = self.plan(
            "app/src/main/java/io/ethan/pushgo/notifications/AlertPlaybackService.kt"
        )

        self.assertEqual("nightly", plan["recommended_lane"])
        self.assertEqual("focused", plan["device_scope_selection"])
        self.assertEqual(6, len(plan["required_device_scopes"]))
        self.assertTrue(any("QualitySystemNotificationJourney" in scope for scope in plan["required_device_scopes"]))
        self.assertTrue(any("RuntimeChannelSwitch" in scope for scope in plan["required_device_scopes"]))

    def test_unscoped_nightly_rule_prevents_unsafe_notification_focus(self):
        plan = self.plan(
            "app/src/main/java/io/ethan/pushgo/notifications/AlertPlaybackService.kt",
            "app/src/main/java/io/ethan/pushgo/markdown/PushGoMarkdown.kt",
        )

        self.assertEqual("nightly", plan["recommended_lane"])
        self.assertEqual("full-lane", plan["device_scope_selection"])
        self.assertEqual([], plan["required_device_scopes"])

    def test_settings_journey_change_keeps_transaction_and_ingress_semantics(self):
        plan = self.plan(
            "app/src/androidTest/java/io/ethan/pushgo/testing/QualitySettingsJourneyInstrumentedTest.kt"
        )

        self.assertEqual("device", plan["recommended_lane"])
        self.assertTrue(
            {
                "gateway-settings",
                "decryption-settings",
                "transport-selector",
                "messages",
                "ingress-ack",
            }.issubset(plan["impacted_capabilities"])
        )

    def test_channel_screen_change_selects_sheet_error_owner_journey(self):
        plan = self.plan(
            "app/src/main/java/io/ethan/pushgo/ui/screens/ChannelListScreen.kt"
        )

        self.assertEqual("device", plan["recommended_lane"])
        self.assertEqual("focused", plan["device_scope_selection"])
        self.assertIn(
            "io.ethan.pushgo.testing.QualityChannelJourneyInstrumentedTest#remoteRejectionStaysInSheetAndRetryPersists",
            plan["required_device_scopes"],
        )

    def test_notification_product_change_requires_system_route_evidence(self):
        plan = self.plan(
            "app/src/main/java/io/ethan/pushgo/notifications/NotificationHelper.kt"
        )

        self.assertEqual("nightly", plan["recommended_lane"])
        self.assertIn("notification-system-route", plan["impacted_capabilities"])
        self.assertIn(
            "durable direct ingress to real system notification/PendingIntent and exact detail/read/dedupe/relaunch journey",
            plan["minimum_evidence"],
        )

    def test_room_schema_export_requires_device_migration_evidence(self):
        plan = self.plan("app/schemas/io.ethan.pushgo.data.db.PushGoDatabase/31.json")

        self.assertEqual("device", plan["recommended_lane"])
        self.assertIn("messages", plan["impacted_capabilities"])

    def test_machine_consumed_update_feed_runs_contract_without_device_lane(self):
        plan = self.plan("release/update-feed-v1.json")

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("pr", plan["recommended_lane"])
        self.assertIn("update-distribution", plan["impacted_capabilities"])
        self.assertEqual(["android-update-distribution-contract"], plan["required_checks"])

    def test_update_installer_change_requires_strict_positive_release_evidence(self):
        plan = self.plan("app/src/main/java/io/ethan/pushgo/update/UpdateInstaller.kt")

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("release", plan["recommended_lane"])
        self.assertIn("update-ui-install", plan["impacted_capabilities"])
        self.assertIn(
            "controlled PackageInstaller version advance and exact post-upgrade data",
            plan["minimum_evidence"],
        )
        self.assertTrue(any("physical/OEM" in gap for gap in plan["known_evidence_gaps"]))

    def test_service_consumer_upgrades_app_change_to_nightly(self):
        plan = self.plan(
            "app/src/main/java/io/ethan/pushgo/MainActivity.kt",
            "app/src/main/java/io/ethan/pushgo/notifications/PrivateChannelForegroundService.kt",
        )

        self.assertEqual("nightly", plan["recommended_lane"])
        self.assertIn("private-foreground-service", plan["impacted_capabilities"])

    def test_historical_connection_diagnosis_surface_maps_to_transport_evidence(self):
        plan = self.plan(
            "app/src/main/java/io/ethan/pushgo/ui/screens/ConnectionDiagnosisScreen.kt",
            "app/src/main/java/io/ethan/pushgo/ui/viewmodel/ConnectionDiagnosisViewModel.kt",
        )

        self.assertEqual("device", plan["recommended_lane"])
        self.assertIn("transport-selector", plan["impacted_capabilities"])

    def test_transport_switch_coordinator_selects_durable_transport_evidence(self):
        path = "app/src/main/java/io/ethan/pushgo/data/TransportSwitchCoordinator.kt"
        plan = self.plan(path)

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual([], plan["unmapped_product_paths"])
        self.assertEqual("device", plan["recommended_lane"])
        self.assertEqual(["transport-selector"], plan["impacted_capabilities"])
        self.assertEqual("focused", plan["device_scope_selection"])
        self.assertEqual(
            [
                "io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest#privateTransportLocalCommitFailureRollsBackBeforeRetryCommits",
                "io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest#transportRejectionsPreserveActiveRouteUntilRetryAndPersistAfterRelaunch",
            ],
            plan["required_device_scopes"],
        )

    def test_macrobenchmark_change_selects_performance_lane(self):
        plan = self.plan(
            "macrobenchmark/src/main/java/io/ethan/pushgo/macrobenchmark/PushGoMacrobenchmark.kt"
        )

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("performance", plan["recommended_lane"])
        self.assertIn("message-detail-frame-performance", plan["impacted_capabilities"])

    def test_generated_profile_change_selects_performance_lane(self):
        plan = self.plan("app/src/release/generated/baselineProfiles/baseline-prof.txt")

        self.assertEqual("performance", plan["recommended_lane"])
        self.assertIn("baseline-profile", plan["impacted_capabilities"])

    def test_message_list_ui_change_keeps_workflow_and_load_recovery_scopes(self):
        plan = self.plan(
            "app/src/main/java/io/ethan/pushgo/ui/screens/MessageListScreen.kt"
        )

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("device", plan["recommended_lane"])
        self.assertEqual("focused", plan["device_scope_selection"])
        self.assertEqual(
            [
                "io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#failedLoadShowsUsableRetryAndRecoversToTheCanonicalMessageDetail",
                "io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#workflowFixtureLoadsSecondPageAndPersistsReadActions"
            ],
            plan["required_device_scopes"],
        )
        self.assertNotIn("app-launch-performance", plan["impacted_capabilities"])

    def test_behavior_and_performance_change_promotes_release(self):
        plan = self.plan(
            "app/src/main/java/io/ethan/pushgo/ui/viewmodel/MessageListViewModel.kt",
            "macrobenchmark/src/main/java/io/ethan/pushgo/macrobenchmark/PushGoMacrobenchmark.kt",
        )

        self.assertEqual("release", plan["recommended_lane"])


if __name__ == "__main__":
    unittest.main()
