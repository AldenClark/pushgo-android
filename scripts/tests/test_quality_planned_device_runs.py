from __future__ import annotations

import unittest

from scripts.quality_planned_device_runs import validated_runs


class PlannedDeviceRunsTests(unittest.TestCase):
    def plan(self):
        scopes = ["example.Journey#one", "example.Journey#two"]
        return {
            "plan_status": "READY",
            "selection_blockers": [],
            "required_device_scopes": scopes,
            "required_device_runs": [
                {
                    "profile": "app-owned",
                    "scopes": scopes,
                    "expected_test_count": 2,
                }
            ],
        }

    def test_accepts_an_exact_non_overlapping_partition(self):
        self.assertEqual(
            [("app-owned", ["example.Journey#one", "example.Journey#two"], 2)],
            validated_runs(self.plan()),
        )

    def test_rejects_unknown_profile_and_wrong_expected_count(self):
        plan = self.plan()
        plan["required_device_runs"][0]["profile"] = "plain-focused"
        with self.assertRaisesRegex(ValueError, "unsupported"):
            validated_runs(plan)
        plan = self.plan()
        plan["required_device_runs"][0]["expected_test_count"] = 1
        with self.assertRaisesRegex(ValueError, "invalid scopes"):
            validated_runs(plan)

    def test_rejects_missing_duplicate_or_unexpected_scope(self):
        for scopes in (
            ["example.Journey#one"],
            ["example.Journey#one", "example.Journey#one"],
            ["example.Journey#one", "example.Other#two"],
        ):
            with self.subTest(scopes=scopes):
                plan = self.plan()
                plan["required_device_runs"][0]["scopes"] = scopes
                plan["required_device_runs"][0]["expected_test_count"] = len(scopes)
                with self.assertRaisesRegex(ValueError, "partition"):
                    validated_runs(plan)

    def test_rejects_blocked_plan_before_any_runner(self):
        plan = self.plan()
        plan["selection_blockers"] = ["ambiguous changed test"]
        with self.assertRaisesRegex(ValueError, "selection blockers"):
            validated_runs(plan)


if __name__ == "__main__":
    unittest.main()
