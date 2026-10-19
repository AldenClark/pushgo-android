import importlib.util
import tempfile
import unittest
from pathlib import Path
from xml.etree import ElementTree


REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "verify_android_performance_contract",
    REPO / "scripts/verify_android_performance_contract.py",
)
assert SPEC and SPEC.loader
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class AndroidPerformanceProfileScopeTests(unittest.TestCase):
    def read_rules(self, text):
        with tempfile.TemporaryDirectory() as directory:
            profile = Path(directory) / "baseline-prof.txt"
            profile.write_text(text, encoding="utf-8")
            return VERIFIER.read_profile(profile)

    def test_product_rule_is_accepted(self):
        rules = self.read_rules("SPLio/ethan/pushgo/MainActivity;->onCreate()V\n")

        self.assertEqual(1, len(rules))

    def test_foreign_rule_is_rejected(self):
        with self.assertRaisesRegex(VERIFIER.ContractError, "non-PushGo"):
            self.read_rules("Landroid/app/Activity;\n")

    def test_quality_control_rule_is_rejected(self):
        with self.assertRaisesRegex(VERIFIER.ContractError, "quality-control"):
            self.read_rules("Lio/ethan/pushgo/testing/BenchmarkFixtureProvider;\n")


class AndroidReleaseControlManifestTests(unittest.TestCase):
    def test_manifest_without_quality_controls_is_accepted(self):
        VERIFIER.verify_release_control_manifest(ElementTree.fromstring("<manifest><application /></manifest>"))

    def assert_disabled_control_is_rejected(self, tag, name):
        root = ElementTree.fromstring(
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application>'
            f'<{tag} android:name="io.ethan.pushgo.testing.{name}" '
            'android:enabled="false" android:exported="false" />'
            '</application></manifest>'
        )
        with self.assertRaisesRegex(VERIFIER.ContractError, "quality-control declaration"):
            VERIFIER.verify_release_control_manifest(root)

    def test_disabled_fixture_provider_is_rejected(self):
        self.assert_disabled_control_is_rejected("provider", "BenchmarkFixtureProvider")

    def test_disabled_unstop_activity_is_rejected(self):
        self.assert_disabled_control_is_rejected("activity", "BenchmarkUnstopActivity")


if __name__ == "__main__":
    unittest.main()
