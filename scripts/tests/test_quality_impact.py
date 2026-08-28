import importlib.util
import unittest
from pathlib import Path


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

    def test_message_viewmodel_selects_device_evidence(self):
        plan = self.plan("app/src/main/java/io/ethan/pushgo/ui/viewmodel/MessageListViewModel.kt")

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("device", plan["recommended_lane"])
        self.assertIn("messages", plan["impacted_capabilities"])
        self.assertIn("Android accurate content/search/delete/relaunch UI journeys", plan["minimum_evidence"])

    def test_room_schema_expands_across_capabilities(self):
        plan = self.plan("app/src/main/java/io/ethan/pushgo/data/db/PushGoDatabase.kt")

        self.assertEqual("device", plan["recommended_lane"])
        self.assertTrue({"messages", "events", "things", "ingress-ack"}.issubset(plan["impacted_capabilities"]))
        self.assertTrue(plan["known_evidence_gaps"])

    def test_runtime_composition_requires_release_isolation(self):
        plan = self.plan("app/src/main/java/io/ethan/pushgo/data/AppContainer.kt")

        self.assertEqual("release", plan["recommended_lane"])
        self.assertIn("release-runtime-isolation", plan["impacted_capabilities"])

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

    def test_instrumented_test_change_requires_device_execution(self):
        plan = self.plan("app/src/androidTest/java/io/ethan/pushgo/testing/NewJourneyInstrumentedTest.kt")

        self.assertEqual("READY", plan["plan_status"])
        self.assertEqual("pr-ui", plan["recommended_lane"])
        self.assertIn("Android core App UI representative lane", plan["minimum_evidence"])

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

    def test_behavior_and_performance_change_promotes_release(self):
        plan = self.plan(
            "app/src/main/java/io/ethan/pushgo/ui/viewmodel/MessageListViewModel.kt",
            "macrobenchmark/src/main/java/io/ethan/pushgo/macrobenchmark/PushGoMacrobenchmark.kt",
        )

        self.assertEqual("release", plan["recommended_lane"])


if __name__ == "__main__":
    unittest.main()
