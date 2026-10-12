import re
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]


class AndroidReleaseIsolationLaneTests(unittest.TestCase):
    def test_standalone_lane_is_host_only_and_full_performance_reuses_the_same_check(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text(encoding="utf-8")
        helper = runner.split("run_release_isolation_checks() {", 1)[1].split("\n}\n", 1)[0]
        self.assertIn(":app:assembleRelease", helper)
        self.assertIn("verify_android_performance_contract.py", helper)
        self.assertIn("verify_android_unsigned_release_apks.py", helper)
        for forbidden in (
            "adb",
            "connectedDebugAndroidTest",
            "connectedBenchmarkBenchmarkAndroidTest",
            "run_system_notification_journeys",
            "run_update_install_positive",
        ):
            self.assertNotIn(forbidden, helper)

        standalone = re.search(
            r"  release-isolation\)\n(?P<body>.*?)\n    ;;",
            runner,
            re.DOTALL,
        )
        self.assertIsNotNone(standalone)
        self.assertIn("run_release_isolation", standalone.group("body"))
        performance = runner.split("run_performance() {", 1)[1].split("\n}\n", 1)[0]
        self.assertIn("run_release_isolation_checks", performance)

    def test_standalone_lane_reports_functional_and_external_boundaries_as_not_run(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text(encoding="utf-8")
        self.assertIn('if [[ "$lane" == "release-isolation" ]]; then', runner)
        self.assertIn(
            'Android functional UI, system notification, Provider/FCM, physical-device, and update-install evidence',
            runner,
        )


if __name__ == "__main__":
    unittest.main()
