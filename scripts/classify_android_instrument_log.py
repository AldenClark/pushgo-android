#!/usr/bin/env python3
"""Classify one focused raw `am instrument` failure without hiding assertions."""

from __future__ import annotations

import argparse
import json
import re
from datetime import date
from pathlib import Path

try:
    from scripts import quality_test_system_issues
except ModuleNotFoundError:
    import quality_test_system_issues


ASSERTION_MESSAGE = re.compile(
    r"(?:AssertionError|AssertionFailedError|ComparisonFailure):\s*([^\n]*)"
)


def classify(text: str, registry: dict) -> tuple[str, list[str]]:
    quality_test_system_issues.validate_registry(registry, date.today())
    assertion_messages = ASSERTION_MESSAGE.findall(text)
    if any("QUALITY_PRECONDITION" not in message for message in assertion_messages):
        return "PRODUCT_FAILED", []

    matches = quality_test_system_issues.active_matches(registry, text)
    ids = sorted(issue["id"] for issue in matches)
    if "android-quality-precondition" in ids and "QUALITY_PRECONDITION" in text:
        return "TEST_SYSTEM_BLOCKED", ["android-quality-precondition"]
    if "android-compose-snapshot-observer-runtime" in ids:
        return "TEST_SYSTEM_FLAKY", ["android-compose-snapshot-observer-runtime"]
    return "PRODUCT_FAILED", []


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", type=Path, required=True)
    parser.add_argument(
        "--registry",
        type=Path,
        default=Path(__file__).resolve().parent.parent / "config/quality-test-system-issues.json",
    )
    args = parser.parse_args()
    registry = json.loads(args.registry.read_text(encoding="utf-8"))
    status, issue_ids = classify(
        args.log.read_text(encoding="utf-8", errors="replace"), registry
    )
    print(f"classification_status={status}")
    print(f"classification_issue_ids={','.join(issue_ids)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
