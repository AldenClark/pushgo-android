from __future__ import annotations

import unittest

from scripts.verify_android_instrumentation_identity import identity_matches, observed_identity


class VerifyAndroidInstrumentationIdentityTests(unittest.TestCase):
    def output(self, class_name="example.PermissionJourney", method="enabledDecision"):
        return (
            f"INSTRUMENTATION_STATUS: class={class_name}\n"
            f"INSTRUMENTATION_STATUS: test={method}\n"
            "INSTRUMENTATION_STATUS_CODE: 1\n"
            f"INSTRUMENTATION_STATUS: class={class_name}\n"
            f"INSTRUMENTATION_STATUS: test={method}\n"
            "INSTRUMENTATION_STATUS_CODE: 0\n"
            "OK (1 test)\n"
        )

    def test_accepts_repeated_status_for_one_exact_selector(self):
        output = self.output()
        self.assertEqual(
            ({"example.PermissionJourney"}, {"enabledDecision"}),
            observed_identity(output),
        )
        self.assertTrue(identity_matches(output, "example.PermissionJourney", "enabledDecision"))

    def test_rejects_same_count_wrong_method_or_extra_identity(self):
        self.assertFalse(
            identity_matches(
                self.output(method="wrongDecision"),
                "example.PermissionJourney",
                "enabledDecision",
            )
        )
        extra = self.output() + "INSTRUMENTATION_STATUS: test=unexpected\n"
        self.assertFalse(
            identity_matches(extra, "example.PermissionJourney", "enabledDecision")
        )

    def test_rejects_ok_count_without_identity(self):
        self.assertFalse(
            identity_matches("OK (1 test)\n", "example.PermissionJourney", "enabledDecision")
        )


if __name__ == "__main__":
    unittest.main()
