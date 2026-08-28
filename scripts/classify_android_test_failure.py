#!/usr/bin/env python3
"""Classify narrowly known Android test-runtime failures from current XML reports."""

from __future__ import annotations

import argparse
from pathlib import Path
from xml.etree import ElementTree


KNOWN_TEST_SYSTEM_SIGNATURES = (
    "Detected multithreaded access to SnapshotStateObserver",
)


def has_known_test_system_failure(report_root: Path, started_at_epoch: float) -> bool:
    if not report_root.is_dir():
        return False
    failures: list[str] = []
    for report in report_root.rglob("*.xml"):
        if report.stat().st_mtime < started_at_epoch:
            continue
        try:
            root = ElementTree.parse(report).getroot()
        except (ElementTree.ParseError, OSError):
            continue
        failures.extend(
            (failure.text or "")
            for element_name in ("failure", "error")
            for failure in root.iter(element_name)
        )
    return bool(failures) and all(
        any(signature in failure for signature in KNOWN_TEST_SYSTEM_SIGNATURES)
        for failure in failures
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report-root", type=Path, required=True)
    parser.add_argument("--started-at-epoch", type=float, required=True)
    args = parser.parse_args()
    return 0 if has_known_test_system_failure(args.report_root, args.started_at_epoch) else 1


if __name__ == "__main__":
    raise SystemExit(main())
