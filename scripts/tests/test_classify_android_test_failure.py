import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import classify_android_test_failure as CLASSIFIER
from scripts.tests.quality_issue_fixture import write_synthetic_registry


class AndroidTestFailureClassificationTests(unittest.TestCase):
    def setUp(self):
        fixture = tempfile.TemporaryDirectory()
        self.addCleanup(fixture.cleanup)
        self.registry_path = write_synthetic_registry(Path(fixture.name) / "issues.json")
        self.classify = CLASSIFIER.classified_test_system_issue_ids

    def issue_ids(self, report_root: Path, started_at_epoch: float) -> list[str]:
        return self.classify(
            report_root, started_at_epoch, registry_path=self.registry_path
        )

    def known(self, report_root: Path, started_at_epoch: float) -> bool:
        # The boolean wrapper has no registry argument. Bind its one classifier
        # call to the same synthetic registry without changing production API.
        with patch.object(CLASSIFIER, "classified_test_system_issue_ids", side_effect=self.issue_ids):
            return CLASSIFIER.has_known_test_system_failure(report_root, started_at_epoch)

    def test_current_snapshot_observer_failure_is_test_system_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            started_at = time.time() - 1
            (root / "result.xml").write_text(
                "<testsuite><testcase><failure>Detected multithreaded access to SnapshotStateObserver</failure></testcase></testsuite>",
                encoding="utf-8",
            )

            self.assertTrue(self.known(root, started_at))
            self.assertEqual(
                ["android-compose-snapshot-observer-runtime"],
                self.issue_ids(root, started_at),
            )

    def test_current_snapshot_observer_error_is_test_system_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            started_at = time.time() - 1
            (root / "result.xml").write_text(
                "<testsuite><testcase><error>Detected multithreaded access to SnapshotStateObserver</error></testcase></testsuite>",
                encoding="utf-8",
            )

            self.assertTrue(self.known(root, started_at))

    def test_current_quality_precondition_failure_is_test_system_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            started_at = time.time() - 1
            (root / "result.xml").write_text(
                "<testsuite><testcase><failure>QUALITY_PRECONDITION app notifications are disabled</failure></testcase></testsuite>",
                encoding="utf-8",
            )

            self.assertTrue(self.known(root, started_at))
            self.assertEqual(
                ["android-quality-precondition"],
                self.issue_ids(root, started_at),
            )

    def test_stale_or_product_assertion_reports_do_not_match(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            stale = root / "stale.xml"
            stale.write_text(
                "<testsuite><testcase><failure>Detected multithreaded access to SnapshotStateObserver</failure></testcase></testsuite>",
                encoding="utf-8",
            )
            os.utime(stale, (1, 1))
            (root / "product.xml").write_text(
                "<testsuite><testcase><failure>Expected accurate channel row to exist</failure></testcase></testsuite>",
                encoding="utf-8",
            )

            self.assertFalse(self.known(root, time.time() - 1))

    def test_mixed_infrastructure_and_product_failures_do_not_hide_product_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "mixed.xml").write_text(
                "<testsuite>"
                "<testcase><failure>Detected multithreaded access to SnapshotStateObserver</failure></testcase>"
                "<testcase><failure>Expected accurate channel row to exist</failure></testcase>"
                "</testsuite>",
                encoding="utf-8",
            )

            self.assertFalse(self.known(root, time.time() - 1))

    def test_mixed_precondition_and_product_failure_does_not_hide_product_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "mixed.xml").write_text(
                "<testsuite>"
                "<testcase><failure>QUALITY_PRECONDITION notification permission unavailable</failure></testcase>"
                "<testcase><failure>Expected exact notification body</failure></testcase>"
                "</testsuite>",
                encoding="utf-8",
            )

            self.assertFalse(self.known(root, time.time() - 1))


if __name__ == "__main__":
    unittest.main()
