import os
import tempfile
import time
import unittest
from pathlib import Path

from scripts.classify_android_test_failure import (
    classified_test_system_issue_ids,
    has_known_test_system_failure,
)


class AndroidTestFailureClassificationTests(unittest.TestCase):
    def test_current_snapshot_observer_failure_is_test_system_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            started_at = time.time() - 1
            (root / "result.xml").write_text(
                "<testsuite><testcase><failure>Detected multithreaded access to SnapshotStateObserver</failure></testcase></testsuite>",
                encoding="utf-8",
            )

            self.assertTrue(has_known_test_system_failure(root, started_at))
            self.assertEqual(
                ["android-compose-snapshot-observer-runtime"],
                classified_test_system_issue_ids(root, started_at),
            )

    def test_current_snapshot_observer_error_is_test_system_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            started_at = time.time() - 1
            (root / "result.xml").write_text(
                "<testsuite><testcase><error>Detected multithreaded access to SnapshotStateObserver</error></testcase></testsuite>",
                encoding="utf-8",
            )

            self.assertTrue(has_known_test_system_failure(root, started_at))

    def test_current_quality_precondition_failure_is_test_system_failure(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            started_at = time.time() - 1
            (root / "result.xml").write_text(
                "<testsuite><testcase><failure>QUALITY_PRECONDITION app notifications are disabled</failure></testcase></testsuite>",
                encoding="utf-8",
            )

            self.assertTrue(has_known_test_system_failure(root, started_at))
            self.assertEqual(
                ["android-quality-precondition"],
                classified_test_system_issue_ids(root, started_at),
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

            self.assertFalse(has_known_test_system_failure(root, time.time() - 1))

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

            self.assertFalse(has_known_test_system_failure(root, time.time() - 1))

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

            self.assertFalse(has_known_test_system_failure(root, time.time() - 1))


if __name__ == "__main__":
    unittest.main()
