import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class HostPrebuildTests(unittest.TestCase):
    def run_prebuild(self, lane):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        (root / "scripts").mkdir()
        for name in ["prebuild_android_device_artifacts.sh", "quality_result.py"]:
            shutil.copy(ROOT / "scripts" / name, root / "scripts" / name)
        gradle = root / "gradlew"
        gradle.write_text('#!/bin/bash\nprintf "%s\\n" "$@" > "$(dirname "$0")/tasks.txt"\n')
        gradle.chmod(0o755)
        result = subprocess.run(["bash", str(root / "scripts/prebuild_android_device_artifacts.sh"), lane],
                                cwd=root, capture_output=True, text=True,
                                env={**os.environ, "PYTHONPATH": str(ROOT),
                                     "QUALITY_RESULTS_ROOT": str(root / "build/quality-results")})
        return root, result

    def test_selected_host_lane_builds_only_the_app_and_still_preserves_native_lanes(self):
        root, result = self.run_prebuild("process-restart")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((root / "tasks.txt").read_text().splitlines(), [":app:assembleDebug", "--console=plain"])
        root, result = self.run_prebuild("pr-ui")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(":app:assembleDebugAndroidTest", (root / "tasks.txt").read_text())

    def test_unknown_lane_retains_preparation_failure_and_never_starts_gradle(self):
        root, result = self.run_prebuild("unknown-lane")
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertFalse((root / "tasks.txt").exists())
        path = root / "build/quality-results/android-prebuild-summary.json"
        self.assertTrue(path.exists(), result.stdout + result.stderr)
        receipt = json.loads(path.read_text())
        self.assertEqual(receipt["product_capability_status"], "NOT_RUN")
        self.assertEqual(receipt["test_system_status"], "BLOCKED")

    def test_update_install_prebuilds_only_its_real_universal_benchmark_package(self):
        root, result = self.run_prebuild("update-install")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(
            [":app:assembleBenchmark", "--console=plain", "-Ppushgo.enableAbiSplits=false"],
            (root / "tasks.txt").read_text().splitlines(),
        )

    def test_reused_focused_and_release_profile_prebuild_tasks_are_unchanged(self):
        for lane in ("focused", "performance", "release"):
            with self.subTest(lane=lane):
                root, result = self.run_prebuild(lane)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                expected = [":app:assembleDebug", ":app:assembleDebugAndroidTest"]
                if lane != "focused":
                    expected += [":app:assembleBenchmark", ":macrobenchmark:assembleBenchmarkBenchmark"]
                self.assertEqual(expected + ["--console=plain"], (root / "tasks.txt").read_text().splitlines())


if __name__ == "__main__":
    unittest.main()
