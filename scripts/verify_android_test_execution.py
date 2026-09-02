#!/usr/bin/env python3
"""Reject Android device-test runs that produced no fresh executed tests."""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET


def executed_test_selectors(report_root: Path, started_at_epoch: float) -> tuple[list[str], list[Path]]:
    reports = sorted(
        path
        for path in report_root.rglob("TEST-*.xml")
        if path.is_file() and path.stat().st_mtime >= started_at_epoch
    )
    selectors: list[str] = []
    for report in reports:
        root = ET.parse(report).getroot()
        for testcase in root.iter("testcase"):
            if testcase.find("skipped") is not None:
                continue
            classname = testcase.attrib.get("classname", "").strip()
            name = testcase.attrib.get("name", "").strip()
            if not classname or not name:
                raise ValueError(f"testcase missing classname or name in {report}")
            if testcase.find("failure") is not None or testcase.find("error") is not None:
                raise ValueError(
                    "fresh Android report contains a failed testcase despite the "
                    f"successful runner path: {classname}#{name}"
                )
            selectors.append(f"{classname}#{name}")
    return selectors, reports


def executed_test_count(report_root: Path, started_at_epoch: float) -> tuple[int, list[Path]]:
    selectors, reports = executed_test_selectors(report_root, started_at_epoch)
    return len(selectors), reports


def skipped_test_selectors(report_root: Path, started_at_epoch: float) -> tuple[list[str], list[Path]]:
    reports = sorted(
        path
        for path in report_root.rglob("TEST-*.xml")
        if path.is_file() and path.stat().st_mtime >= started_at_epoch
    )
    selectors: list[str] = []
    for report in reports:
        root = ET.parse(report).getroot()
        for testcase in root.iter("testcase"):
            if testcase.find("skipped") is None:
                continue
            classname = testcase.attrib.get("classname", "").strip()
            name = testcase.attrib.get("name", "").strip()
            if not classname or not name:
                raise ValueError(f"testcase missing classname or name in {report}")
            selectors.append(f"{classname}#{name}")
    return selectors, reports


def test_count_matches_selection(count: int, expected: int | None) -> bool:
    return expected is None or count == expected


def selectors_match_selection(actual: list[str], expected: list[str] | None) -> bool:
    return expected is None or Counter(actual) == Counter(expected)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report-root", required=True, type=Path)
    parser.add_argument("--started-at-epoch", required=True, type=float)
    parser.add_argument("--expected-test-count", type=int)
    parser.add_argument("--expected-selectors")
    args = parser.parse_args()

    try:
        selectors, reports = executed_test_selectors(args.report_root, args.started_at_epoch)
        skipped, _ = skipped_test_selectors(args.report_root, args.started_at_epoch)
    except (OSError, ValueError, ET.ParseError) as error:
        print("status=FAILED_TEST_SYSTEM")
        print(f"reason=invalid_fresh_android_test_report:{error}")
        return 1

    if not reports:
        print("status=FAILED_TEST_SYSTEM")
        print("reason=no_fresh_android_test_report")
        return 1
    if skipped:
        print("status=FAILED_TEST_SYSTEM")
        print(
            "reason=fresh_android_test_report_contains_skipped_tests:"
            + ",".join(sorted(skipped))
        )
        return 1
    total = len(selectors)
    if total <= 0:
        print("status=FAILED_TEST_SYSTEM")
        print("reason=selected_android_tests_executed_zero_tests")
        return 1
    expected_selectors = (
        [item for item in (args.expected_selectors or "").split(",") if item]
        if args.expected_selectors is not None
        else None
    )
    if not selectors_match_selection(selectors, expected_selectors):
        missing = sorted((Counter(expected_selectors) - Counter(selectors)).elements())
        unexpected = sorted((Counter(selectors) - Counter(expected_selectors)).elements())
        print("status=FAILED_TEST_SYSTEM")
        print(
            "reason=selected_android_test_identity_mismatch:"
            f"missing={','.join(missing) or '-'}:unexpected={','.join(unexpected) or '-'}"
        )
        return 1
    if not test_count_matches_selection(total, args.expected_test_count):
        print("status=FAILED_TEST_SYSTEM")
        print(
            "reason=selected_android_test_count_mismatch:"
            f"expected={args.expected_test_count}:executed={total}"
        )
        return 1

    print("status=EXECUTED")
    print(f"executed_test_count={total}")
    print(f"executed_test_selectors={','.join(sorted(selectors))}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
