from __future__ import annotations

import os
from pathlib import Path
import tempfile
import unittest

from scripts.verify_android_test_execution import executed_test_count


class VerifyAndroidTestExecutionTests(unittest.TestCase):
    def write_report(
        self,
        root: Path,
        *,
        tests: int,
        modified_at: float,
        skipped: int = 0,
    ) -> Path:
        report = root / "debug" / "TEST-device.xml"
        report.parent.mkdir(parents=True)
        report.write_text(
            f'<testsuites tests="{tests}" failures="0" errors="0" skipped="{skipped}" />',
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

    def test_ignores_stale_reports(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_report(root, tests=9, modified_at=5.0)
            count, reports = executed_test_count(root, started_at_epoch=10.0)
            self.assertEqual(count, 0)
            self.assertEqual(reports, [])


if __name__ == "__main__":
    unittest.main()
