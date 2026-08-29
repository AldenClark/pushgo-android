#!/usr/bin/env python3
"""Reject Android device-test runs that produced no fresh executed tests."""

from __future__ import annotations

import argparse
from pathlib import Path
import xml.etree.ElementTree as ET


def executed_test_count(report_root: Path, started_at_epoch: float) -> tuple[int, list[Path]]:
    reports = sorted(
        path
        for path in report_root.rglob("TEST-*.xml")
        if path.is_file() and path.stat().st_mtime >= started_at_epoch
    )
    total = 0
    for report in reports:
        root = ET.parse(report).getroot()
        tests = int(root.attrib.get("tests", "0"))
        skipped = int(root.attrib.get("skipped", root.attrib.get("disabled", "0")))
        total += max(0, tests - skipped)
    return total, reports


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report-root", required=True, type=Path)
    parser.add_argument("--started-at-epoch", required=True, type=float)
    args = parser.parse_args()

    try:
        total, reports = executed_test_count(args.report_root, args.started_at_epoch)
    except (OSError, ValueError, ET.ParseError) as error:
        print("status=FAILED_TEST_SYSTEM")
        print(f"reason=invalid_fresh_android_test_report:{error}")
        return 1

    if not reports:
        print("status=FAILED_TEST_SYSTEM")
        print("reason=no_fresh_android_test_report")
        return 1
    if total <= 0:
        print("status=FAILED_TEST_SYSTEM")
        print("reason=selected_android_tests_executed_zero_tests")
        return 1

    print("status=EXECUTED")
    print(f"executed_test_count={total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
