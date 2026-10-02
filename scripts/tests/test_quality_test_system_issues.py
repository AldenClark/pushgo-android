import copy
import importlib.util
import json
import subprocess
import unittest
from datetime import date
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "quality_test_system_issues", REPO / "scripts/quality_test_system_issues.py"
)
assert SPEC and SPEC.loader
ISSUES = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ISSUES)


class QualityTestSystemIssueTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registry = json.loads(
            (REPO / "config/quality-test-system-issues.json").read_text(encoding="utf-8")
        )

    def test_current_registry_has_owned_unexpired_issues(self):
        ISSUES.validate_registry(self.registry, date.today())

        self.assertTrue(self.registry["issues"])
        self.assertTrue(all(issue["owner"] for issue in self.registry["issues"]))

    def test_expired_active_flake_is_rejected(self):
        registry = copy.deepcopy(self.registry)
        flake = next(issue for issue in registry["issues"] if issue["kind"] == "flake")
        # Exercise the active-flake policy even after the real issue is resolved.
        flake["status"] = "active"
        flake["expires_on"] = "2026-08-27"

        with self.assertRaisesRegex(ValueError, "expired"):
            ISSUES.validate_registry(registry, date(2026, 8, 28))

    def test_active_flake_cannot_be_renewed_beyond_fourteen_days(self):
        registry = copy.deepcopy(self.registry)
        flake = next(issue for issue in registry["issues"] if issue["kind"] == "flake")
        # Exercise the active-flake policy even after the real issue is resolved.
        flake["status"] = "active"
        flake["expires_on"] = "2026-09-12"

        with self.assertRaisesRegex(ValueError, "exceeds 14 days"):
            ISSUES.validate_registry(registry, date(2026, 8, 28))

    def test_unbacked_quarantine_is_rejected(self):
        registry = copy.deepcopy(self.registry)
        registry["issues"][0]["quarantined"] = True

        with self.assertRaisesRegex(ValueError, "replacement_evidence"):
            ISSUES.validate_registry(registry, date(2026, 8, 28))

    def test_product_assertion_does_not_match_registered_issue(self):
        matched = ISSUES.active_matches(
            self.registry,
            "Expected accurate channel row to exist",
        )

        self.assertEqual([], matched)

    def test_startup_reliability_rejects_invalid_iterations_before_doctor(self):
        process = subprocess.run(
            [str(REPO / "scripts/run_android_startup_reliability.sh")],
            cwd=REPO,
            env={"PATH": "/usr/bin:/bin:/usr/sbin:/sbin", "ITERATIONS": "101"},
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            check=False,
        )

        self.assertEqual(2, process.returncode)
        self.assertIn("iterations_must_be_between_1_and_100:101", process.stdout)
        self.assertNotIn("device_serial=", process.stdout)


if __name__ == "__main__":
    unittest.main()
