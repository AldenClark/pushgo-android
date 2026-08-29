import re
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]


class QualityLaneCostContractTests(unittest.TestCase):
    def test_pr_ui_is_unique_discoverable_positive_breadth(self) -> None:
        runner = (REPO / "scripts/quality_test.sh").read_text()
        match = re.search(r'^positive_device_scopes="([^"]+)"$', runner, re.MULTILINE)
        self.assertIsNotNone(match)
        scopes = match.group(1).split(",")
        self.assertEqual(len(scopes), len(set(scopes)))
        self.assertEqual(12, len(scopes))

        sources = {
            path.stem: path.read_text()
            for path in (REPO / "app/src/androidTest/java/io/ethan/pushgo/testing").glob(
                "Quality*JourneyInstrumentedTest.kt"
            )
        }
        for scope in scopes:
            class_name, method = scope.rsplit(".", 1)[-1].split("#", 1)
            self.assertIn(class_name, sources)
            self.assertRegex(sources[class_name], rf"\bfun\s+{re.escape(method)}\s*\(")
        for required_fragment in ("MessageJourney", "EntityJourney", "ChannelJourney", "SettingsJourney"):
            self.assertTrue(any(required_fragment in scope for scope in scopes), required_fragment)
        for deferred_fragment in ("Failure", "failure", "Corrupt", "corrupt", "Slow", "slow", "Delete", "delete", "Rejection"):
            self.assertFalse(any(deferred_fragment in scope for scope in scopes), deferred_fragment)
        self.assertFalse(any("emptyFixtureShows" in scope for scope in scopes))
        self.assertFalse(any("searchReturnsOnly" in scope for scope in scopes))
        self.assertIn('run_quality_device_classes "$positive_device_scopes"', runner)


if __name__ == "__main__":
    unittest.main()
