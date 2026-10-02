from __future__ import annotations

import os
from pathlib import Path
import contextlib
import io
import json
import shutil
from unittest.mock import patch
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

    def test_verified_evidence_survives_the_next_native_report_overwrite(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "reports"
            report = self.write_report(root, tests=1, modified_at=20.0)
            log = report.parent / "logcat-example.Journey-test0.txt"
            log.write_text("actual first phase diagnostic")
            os.utime(log, (21.0, 21.0))
            old_log = report.parent / "logcat-old.txt"
            old_log.write_text("stale phase")
            os.utime(old_log, (1.0, 1.0))
            archive = Path(directory) / "phase-one"
            with patch.object(sys, "argv", ["verify", "--report-root", str(root),
                    "--started-at-epoch", "10", "--expected-test-count", "1",
                    "--expected-selectors", "example.Journey#test0", "--archive-dir", str(archive)]):
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(0, main())
            shutil.rmtree(root)
            receipt = json.loads((archive / "verified-native-evidence.json").read_text())
            self.assertEqual(["example.Journey#test0"], receipt["executed_selectors"])
            self.assertEqual("actual first phase diagnostic", (archive / "debug" / log.name).read_text())
            self.assertTrue((archive / "debug" / report.name).exists())
            self.assertFalse((archive / "debug" / old_log.name).exists())

    def test_invalid_native_selection_does_not_create_a_verified_archive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "reports"
            self.write_report(root, tests=1, modified_at=20.0)
            archive = Path(directory) / "phase-one"
            with patch.object(sys, "argv", ["verify", "--report-root", str(root),
                    "--started-at-epoch", "10", "--expected-selectors", "example.Other#wrong",
                    "--archive-dir", str(archive)]):
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(1, main())
            self.assertFalse(archive.exists())

    def test_a_later_phase_cannot_replace_an_existing_verified_archive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "reports"
            self.write_report(root, tests=1, modified_at=20.0)
            archive = Path(directory) / "phase-one"
            archive.mkdir()
            sentinel = archive / "original.txt"
            sentinel.write_text("original native evidence")
            with patch.object(sys, "argv", ["verify", "--report-root", str(root),
                    "--started-at-epoch", "10", "--archive-dir", str(archive)]):
                output = io.StringIO()
                with contextlib.redirect_stdout(output):
                    self.assertEqual(1, main())
                self.assertIn("status=FAILED_TEST_SYSTEM", output.getvalue())
            self.assertEqual("original native evidence", sentinel.read_text())

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
