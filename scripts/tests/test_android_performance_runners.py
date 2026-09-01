import os
import subprocess
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]


class AndroidPerformanceRunnerPreflightTests(unittest.TestCase):
    def run_script(self, name, env=None):
        clean_env = os.environ.copy()
        for key in (
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


if __name__ == "__main__":
    unittest.main()
