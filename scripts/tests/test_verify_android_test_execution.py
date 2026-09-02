from __future__ import annotations

import os
from pathlib import Path
import contextlib
import io
import sys
import tempfile
import unittest

from scripts.verify_android_test_execution import (
    executed_test_count,
    skipped_test_selectors,
    main,
    selectors_match_selection,
    test_count_matches_selection,
)


class VerifyAndroidTestExecutionTests(unittest.TestCase):
    def test_exact_selection_requires_every_selected_method_to_execute(self) -> None:
        self.assertTrue(test_count_matches_selection(3, 3))
        self.assertFalse(test_count_matches_selection(2, 3))
        self.assertTrue(test_count_matches_selection(7, None))

    def test_exact_selection_rejects_same_count_wrong_or_duplicate_methods(self) -> None:
        expected = ["example.Journey#one", "example.Journey#two"]
        self.assertTrue(selectors_match_selection(expected, expected))
        self.assertFalse(
            selectors_match_selection(
                ["example.Journey#one", "example.Other#two"], expected
            )
        )
        self.assertFalse(
            selectors_match_selection(
                ["example.Journey#one", "example.Journey#one"], expected
            )
        )

    def write_report(
        self,
        root: Path,
        *,
        tests: int,
        modified_at: float,
        skipped: int = 0,
        failed: int = 0,
    ) -> Path:
        report = root / "debug" / "TEST-device.xml"
        report.parent.mkdir(parents=True)
        cases = []
        for index in range(tests):
            skipped_node = "<skipped />" if index < skipped else ""
            failure_node = "<failure message=\"intentional failure\" />" if index < failed else ""
            cases.append(
                f'<testcase classname="example.Journey" name="test{index}">{skipped_node}{failure_node}</testcase>'
            )
        report.write_text(
            f'<testsuites tests="{tests}" failures="{failed}" errors="0" skipped="{skipped}">'
            + "".join(cases)
            + "</testsuites>",
            encoding="utf-8",
        )
        os.utime(report, (modified_at, modified_at))
        return report

    def test_counts_fresh_executed_tests(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_report(root, tests=3, modified_at=20.0)
            count, reports = executed_test_count(root, started_at_epoch=10.0)
            self.assertEqual(count, 3)
            self.assertEqual(len(reports), 1)

    def test_zero_test_report_remains_zero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_report(root, tests=0, modified_at=20.0)
            count, reports = executed_test_count(root, started_at_epoch=10.0)
            self.assertEqual(count, 0)
            self.assertEqual(len(reports), 1)

    def test_all_skipped_report_remains_zero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_report(root, tests=3, skipped=3, modified_at=20.0)
            count, reports = executed_test_count(root, started_at_epoch=10.0)
            self.assertEqual(count, 0)
            self.assertEqual(len(reports), 1)

    def test_skipped_selectors_are_reported_separately_for_lane_rejection(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_report(root, tests=3, skipped=1, modified_at=20.0)
            selectors, reports = skipped_test_selectors(root, started_at_epoch=10.0)
            self.assertEqual(selectors, ["example.Journey#test0"])
            self.assertEqual(len(reports), 1)

    def test_main_rejects_any_fresh_skipped_test(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_report(root, tests=2, skipped=1, modified_at=20.0)
            previous_argv = sys.argv
            output = io.StringIO()
            try:
                sys.argv = [
                    "verify_android_test_execution.py",
                    "--report-root",
                    str(root),
                    "--started-at-epoch",
                    "10",
                ]
                with contextlib.redirect_stdout(output):
                    status = main()
            finally:
                sys.argv = previous_argv
            self.assertEqual(status, 1)
            self.assertIn("fresh_android_test_report_contains_skipped_tests", output.getvalue())

    def test_failed_testcase_cannot_be_treated_as_executed_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_report(root, tests=2, failed=1, modified_at=20.0)
            with self.assertRaisesRegex(ValueError, "contains a failed testcase"):
                executed_test_count(root, started_at_epoch=10.0)

    def test_ignores_stale_reports(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_report(root, tests=9, modified_at=5.0)
            count, reports = executed_test_count(root, started_at_epoch=10.0)
            self.assertEqual(count, 0)
            self.assertEqual(reports, [])


if __name__ == "__main__":
    unittest.main()
