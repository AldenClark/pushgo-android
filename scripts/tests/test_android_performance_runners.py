import os
import json
import subprocess
import sys
import tempfile
import time
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]


class AndroidPerformanceRunnerPreflightTests(unittest.TestCase):
    def run_script(self, name, env=None):
        clean_env = os.environ.copy()
        for key in (
            "ANDROID_SERIAL",
            "ANDROID_PERFORMANCE_DEVICE_SERIAL",
            "ANDROID_BASELINE_PROFILE_DEVICE_SERIAL",
            "PUSHGO_ANDROID_PHYSICAL_MAX_STARTUP_MS",
            "PUSHGO_ANDROID_PHYSICAL_MAX_DETAIL_MS",
            "PUSHGO_ANDROID_PHYSICAL_MAX_FRAME_MS",
        ):
            clean_env.pop(key, None)
        clean_env.update(env or {})
        return subprocess.run(
            [str(REPO / "scripts" / name)],
            cwd=REPO,
            env=clean_env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_physical_runner_never_selects_a_device_implicitly(self):
        result = self.run_script("run_android_physical_macrobenchmark.sh")

        self.assertEqual(2, result.returncode)
        self.assertIn("ANDROID_PERFORMANCE_DEVICE_SERIAL is required", result.stderr)

    def test_physical_runner_rejects_invalid_budget_before_adb(self):
        result = self.run_script(
            "run_android_physical_macrobenchmark.sh",
            {
                "ANDROID_PERFORMANCE_DEVICE_SERIAL": "explicit-device",
                "PUSHGO_ANDROID_PHYSICAL_MAX_STARTUP_MS": "0",
                "PUSHGO_ANDROID_PHYSICAL_MAX_DETAIL_MS": "100",
                "PUSHGO_ANDROID_PHYSICAL_MAX_FRAME_MS": "16",
            },
        )

        self.assertEqual(2, result.returncode)
        self.assertIn("startup budget must be greater than zero", result.stderr)

    def test_profile_regeneration_requires_explicit_emulator(self):
        result = self.run_script("regenerate_android_baseline_profile.sh")

        self.assertEqual(2, result.returncode)
        self.assertIn("ANDROID_BASELINE_PROFILE_DEVICE_SERIAL is required", result.stderr)

    def test_slow_load_negative_control_requires_explicit_emulator(self):
        result = self.run_script("run_android_performance_negative_control.sh")

        self.assertEqual(2, result.returncode)
        self.assertIn("ANDROID_SERIAL is required", result.stderr)

    def test_slow_load_negative_control_bounds_adb_calls(self):
        runner = (REPO / "scripts/run_android_performance_negative_control.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("adb_with_timeout()", runner)
        self.assertIn('adb_timeout_seconds="${QUALITY_ADB_TIMEOUT_SECONDS:-8}"', runner)
        self.assertIn("QUALITY_ADB_TIMEOUT_SECONDS must be a positive integer", runner)
        self.assertNotIn("adb -s ", runner)

    def test_startup_reliability_bounds_adb_and_serializes_the_campaign(self):
        runner = (REPO / "scripts/run_android_startup_reliability.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("adb_with_timeout()", runner)
        self.assertIn('adb_timeout_seconds="${QUALITY_ADB_TIMEOUT_SECONDS:-8}"', runner)
        self.assertIn("QUALITY_ADB_TIMEOUT_SECONDS must be a positive integer", runner)
        self.assertIn("acquire_device_lock()", runner)
        self.assertIn("release_device_lock()", runner)
        self.assertIn("QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS", runner)
        self.assertIn("selected Android device is busy", runner)
        self.assertNotIn("adb -s ", runner)


class AndroidPerformanceNegativeNativeOracleTests(unittest.TestCase):
    """Exercise the script's actual native oracle, independent of AGP console text."""

    selector_class = "io.ethan.pushgo.macrobenchmark.PushGoMacrobenchmark"
    method = "coldStartupReachesAccurateLargeStoreContent"
    observed_failure = (
        "java.lang.IllegalStateException: cold startup-to-accurate-content took 5604ms; budget=2000ms\n"
        "\tat io.ethan.pushgo.macrobenchmark.PushGoMacrobenchmark.coldStartupReachesAccurateLargeStoreContent(PushGoMacrobenchmark.kt:49)"
    )

    def run_oracle(self, *, mutate=None, stale=False, missing=False, second_report=False,
                   gradle_status="1", delay="3500"):
        script = (REPO / "scripts/run_android_performance_negative_control.sh").read_text()
        program = "import hashlib" + script.split("<<'PY'\nimport hashlib", 1)[1].split("\nPY", 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            report_root = root / "reports"
            report_root.mkdir()
            output = root / "result.json"
            suite = ET.Element("testsuite", tests="1", failures="1", errors="0", skipped="0")
            case = ET.SubElement(suite, "testcase", classname=self.selector_class, name=self.method)
            ET.SubElement(case, "failure").text = self.observed_failure
            if mutate:
                mutate(suite, case)
            started = time.time() - 1
            if not missing:
                report = report_root / "TEST-control.xml"
                ET.ElementTree(suite).write(report)
                if stale:
                    os.utime(report, (started - 1, started - 1))
                if second_report:
                    (report_root / "TEST-extra.xml").write_bytes(report.read_bytes())
            result = subprocess.run(
                [sys.executable, "-", str(output), "emulator-test", delay, "2000",
                 str(report_root), str(started), gradle_status],
                input=program, capture_output=True, text=True, check=False,
            )
            return result, json.loads(output.read_text()) if output.exists() else None

    def assert_rejected(self, **kwargs):
        result, receipt = self.run_oracle(**kwargs)
        self.assertNotEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIsNone(receipt)

    def test_fresh_exact_native_failure_is_detected_without_console_stack(self):
        result, receipt = self.run_oracle()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(5604, receipt["observed_ms"])
        self.assertEqual(2000, receipt["budget_ms"])
        self.assertEqual("NOT_RUN", receipt["product_status"])
        self.assertEqual("PASSED", receipt["test_system_status"])
        self.assertEqual(self.selector_class + "#" + self.method, receipt["expected_selector"])
        self.assertEqual(64, len(receipt["native_report"]["sha256"]))

    def test_missing_stale_or_multiple_reports_cannot_supply_the_oracle(self):
        for variant in ({"missing": True}, {"stale": True}, {"second_report": True}):
            with self.subTest(variant=variant):
                self.assert_rejected(**variant)

    def test_wrong_selector_zero_or_mixed_tests_are_rejected(self):
        for mutate in (
            lambda suite, case: case.set("name", "anotherMethod"),
            lambda suite, case: case.set("classname", "example.OtherClass"),
            lambda suite, case: suite.remove(case),
            lambda suite, case: ET.SubElement(suite, "testcase", classname="example.Other", name="another"),
            lambda suite, case: suite.set("tests", "0"),
        ):
            with self.subTest(mutate=mutate):
                self.assert_rejected(mutate=mutate)

    def test_errors_skips_success_and_unrelated_failure_cannot_pass(self):
        def replace_child(suite, case, kind):
            case.remove(case.find("failure"))
            ET.SubElement(case, kind).text = self.observed_failure

        for mutate in (
            lambda suite, case: replace_child(suite, case, "error"),
            lambda suite, case: replace_child(suite, case, "skipped"),
            lambda suite, case: case.remove(case.find("failure")),
            lambda suite, case: ET.SubElement(case, "error"),
            lambda suite, case: setattr(case.find("failure"), "text", "java.lang.IllegalStateException: fixture setup failed"),
        ):
            with self.subTest(mutate=mutate):
                self.assert_rejected(mutate=mutate)

    def test_budget_interval_and_gradle_failure_must_all_match(self):
        for replacement in ("budget=3000ms", "budget=200ms"):
            self.assert_rejected(mutate=lambda suite, case: setattr(
                case.find("failure"), "text", self.observed_failure.replace("budget=2000ms", replacement)))
        self.assert_rejected(mutate=lambda suite, case: setattr(
            case.find("failure"), "text", self.observed_failure.replace("5604ms", "1999ms")))
        self.assert_rejected(mutate=lambda suite, case: setattr(
            case.find("failure"), "text", self.observed_failure.replace("IllegalStateException", "AssertionError")))
        self.assert_rejected(gradle_status="0")
        self.assert_rejected(delay="2000")


if __name__ == "__main__":
    unittest.main()
