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


class AndroidControlSourceSetTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.repo = Path(self.directory.name)
        for variant in ("main", "debug", "benchmark", "nonMinifiedRelease"):
            target = self.repo / f"app/src/{variant}/AndroidManifest.xml"
            target.parent.mkdir(parents=True)
            target.write_text((REPO / target.relative_to(self.repo)).read_text())

    def test_exact_quality_variants_and_empty_product_manifest_are_accepted(self):
        VERIFIER.verify_control_source_sets(self.repo)

    def test_missing_or_modified_quality_component_is_rejected(self):
        for variant in ("debug", "benchmark", "nonMinifiedRelease"):
            with self.subTest(variant=variant):
                target = self.repo / f"app/src/{variant}/AndroidManifest.xml"
                original = target.read_text()
                target.write_text(original.replace('android.permission.DUMP', 'android.permission.INTERNET'))
                with self.assertRaisesRegex(VERIFIER.ContractError, "attributes changed"):
                    VERIFIER.verify_control_source_sets(self.repo)
                target.write_text(original)

    def test_main_or_release_control_reference_is_rejected(self):
        for variant in ("main", "release"):
            with self.subTest(variant=variant):
                target = self.repo / f"app/src/{variant}/AndroidManifest.xml"
                target.parent.mkdir(exist_ok=True)
                original = target.read_text() if target.exists() else None
                target.write_text((REPO / "app/src/debug/AndroidManifest.xml").read_text())
                with self.assertRaisesRegex(VERIFIER.ContractError, "quality-control declaration"):
                    VERIFIER.verify_control_source_sets(self.repo)
                if original is None:
                    target.unlink()
                else:
                    target.write_text(original)


if __name__ == "__main__":
    unittest.main()
