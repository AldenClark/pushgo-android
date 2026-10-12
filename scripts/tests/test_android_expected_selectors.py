from __future__ import annotations

from pathlib import Path
import tempfile
import unittest

from scripts.android_expected_selectors import discover_classes, resolve


class AndroidExpectedSelectorsTests(unittest.TestCase):
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
