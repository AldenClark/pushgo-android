from __future__ import annotations

import base64
from pathlib import Path
import re
import subprocess
import unittest


REPO = Path(__file__).resolve().parents[2]


class AndroidRunnerScopeEncodingTests(unittest.TestCase):
    def argument(self, scopes: str) -> list[str]:
        script = (REPO / "scripts/quality_test.sh").read_text()
        helper = re.search(
            r"android_instrumentation_scope_argument\(\) \{.*?\n\}", script, re.DOTALL
        ).group(0)
        result = subprocess.run(
            ["bash", "-c", helper + '\nandroid_instrumentation_scope_argument "$1"', "test", scopes],
            check=True, text=True, capture_output=True,
        )
        return result.stdout.strip().removeprefix(
            "-Pandroid.testInstrumentationRunnerArguments."
        ).split("=", 1)

    @staticmethod
    def agp_argument_map(value: str) -> dict[str, str]:
        # Official android-test-engine 1.0.1 AndroidTestConfiguration.kt:105-112.
        # Exercise the transport's delimiter loss rather than relaxing the native oracle.
        return dict(part.split("=", 1) for part in value.split(",") if "=" in part)

    def test_negative_control_plain_csv_loses_the_other_three_classes(self):
        scopes = "example.Messages,example.Entities,example.Channels,example.Settings"
        self.assertEqual(
            {"class": "example.Messages", "session": "c2Vzc2lvbg=="},
            self.agp_argument_map("class=" + scopes + ",session=c2Vzc2lvbg=="),
        )

    def test_actual_shell_adapter_preserves_all_classes_and_method_scopes(self):
        script = (REPO / "scripts/quality_test.sh").read_text()
        original_classes = re.search(r'^quality_device_classes="([^"]+)"', script, re.MULTILINE).group(1)
        for scopes in (original_classes, "example.Messages#cancel,example.Settings#save"):
            with self.subTest(scopes=scopes):
                key, encoded = self.argument(scopes)
                self.assertEqual("pushgo.test.classSelectorsBase64", key)
                transported = self.agp_argument_map(key + "=" + encoded + ",session=c2Vzc2lvbg==")
                self.assertEqual(scopes, base64.b64decode(transported[key], validate=True).decode())
                self.assertEqual("c2Vzc2lvbg==", transported["session"])

    def test_single_selector_keeps_the_standard_android_runner_argument(self):
        self.assertEqual(["class", "example.Messages#cancel"], self.argument("example.Messages#cancel"))
        with self.assertRaises(subprocess.CalledProcessError):
            self.argument("")

    def test_every_csv_callsite_uses_adapter_and_native_verifier_remains_strict(self):
        script = (REPO / "scripts/quality_test.sh").read_text()
        for scope in ("instrumentation_scope", "classes", "system_notification_classes", "ANDROID_TEST_CLASS"):
            self.assertIn('"$(android_instrumentation_scope_argument "$' + scope + '")"', script)
        verifier = (REPO / "scripts/verify_android_test_execution.py").read_text()
        self.assertIn("Counter(actual) == Counter(expected)", verifier)
        self.assertIn("selected_android_test_identity_mismatch", verifier)
        self.assertIn("fresh_android_test_report_contains_skipped_tests", verifier)


if __name__ == "__main__":
    unittest.main()
