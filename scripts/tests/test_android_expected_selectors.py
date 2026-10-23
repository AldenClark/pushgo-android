from __future__ import annotations

from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

from scripts.android_expected_selectors import discover_classes, resolve


REPO = Path(__file__).resolve().parents[2]


class AndroidExpectedSelectorsTests(unittest.TestCase):
    def test_resolves_inline_and_multiline_test_declarations_without_selecting_other_classes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Journey.kt").write_text(
                "package example\nclass Journey {\n"
                "    @Test fun inlinePurpose() = Unit\n"
                "    @Test\n    fun multilinePurpose() = Unit\n}\n"
            )
            (root / "Other.kt").write_text(
                'package example\nclass Other {\n    @Test fun unrelatedPurpose() {\n'
                '        val phase = "body must not become a declaration"\n    }\n}\n'
            )
            self.assertEqual(
                ["example.Journey#inlinePurpose", "example.Journey#multilinePurpose"],
                resolve(root, ["example.Journey"], set()),
            )
            self.assertEqual(
                ["example.Journey#inlinePurpose"],
                resolve(root, ["example.Journey#inlinePurpose"], set()),
            )
            with self.assertRaisesRegex(ValueError, "zero @Test"):
                resolve(root, ["example.Journey#inlinePurpose"], {"example.Journey#inlinePurpose"})

    def test_actual_quality_class_scope_resolves_with_inline_tests_elsewhere_in_tree(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        scopes = re.search(r'^quality_device_classes="([^"]+)"', runner, re.MULTILINE).group(1).split(",")
        selectors = resolve(REPO / "app/src/androidTest", scopes, set())
        self.assertTrue(selectors)
        self.assertEqual(set(scopes), {selector.split("#")[0] for selector in selectors})
        self.assertIn(
            "io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest#standardFixtureShowsAccurateContentAndSurvivesActivityRelaunch",
            selectors,
        )

    def test_cli_failure_keeps_real_reason_on_stderr_for_shell_capture(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            process = subprocess.run(
                [sys.executable, str(REPO / "scripts/android_expected_selectors.py"),
                 "--source-root", directory, "--scopes", "example.Missing"],
                capture_output=True, text=True,
            )
            self.assertEqual(2, process.returncode)
            self.assertEqual("", process.stdout)
            self.assertIn("unknown Android instrumentation class: example.Missing", process.stderr)

    def test_resolves_class_scope_and_supports_explicit_opt_in_exclusion(self) -> None:
        source = """
package example

import org.junit.Test

class Journey {
    @Test
    fun firstPurpose() = Unit

    @Test
    fun optInScale() = Unit
}
"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Journey.kt").write_text(source, encoding="utf-8")
            self.assertEqual(
                ["example.Journey#firstPurpose", "example.Journey#optInScale"],
                resolve(root, ["example.Journey"], set()),
            )
            self.assertEqual(
                ["example.Journey#firstPurpose"],
                resolve(root, ["example.Journey"], {"example.Journey#optInScale"}),
            )

    def test_rejects_unknown_method_and_duplicate_class_scope(self) -> None:
        source = """
package example

import org.junit.Test

class Journey {
    @Test
    fun firstPurpose() = Unit
}
"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Journey.kt").write_text(source, encoding="utf-8")
            classes = discover_classes(root)
            self.assertIn("example.Journey", classes)
            with self.assertRaisesRegex(ValueError, "unknown @Test method"):
                resolve(root, ["example.Journey#missing"], set())
            with self.assertRaisesRegex(ValueError, "duplicate selectors"):
                resolve(root, ["example.Journey", "example.Journey"], set())


if __name__ == "__main__":
    unittest.main()
