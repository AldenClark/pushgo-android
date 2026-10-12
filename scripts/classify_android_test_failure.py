#!/usr/bin/env python3
"""Classify narrowly known Android test-runtime failures from current XML reports."""

from __future__ import annotations

import argparse
import json
from datetime import date
from pathlib import Path
from xml.etree import ElementTree

try:
    from scripts import quality_test_system_issues
except ModuleNotFoundError:
    import quality_test_system_issues


def classified_test_system_issue_ids(
    report_root: Path,
    started_at_epoch: float,
    registry_path: Path | None = None,
    additional_report_roots: tuple[Path, ...] = (),
) -> list[str]:
    if registry_path is None:
        registry_path = Path(__file__).resolve().parent.parent / "config/quality-test-system-issues.json"
    registry = json.loads(registry_path.read_text(encoding="utf-8"))
    quality_test_system_issues.validate_registry(registry, date.today())
    failures: list[str] = []
    for directory in (report_root, *additional_report_roots):
        if not directory.is_dir():
            continue
        for report in directory.rglob("*.xml"):
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
            if is_pixel_launcher_anr_dialog(root):
                failures.append(
                    "Pixel Launcher isn't responding android:id/alertTitle "
                    "android:id/aerr_close Close app android:id/aerr_wait Wait"
                )
    if not failures:
        return []
    matched_ids: set[str] = set()
    for failure in failures:
        matches = quality_test_system_issues.active_matches(registry, failure)
        if not matches:
            return []
        matched_ids.update(issue["id"] for issue in matches)
    return sorted(matched_ids)


def is_pixel_launcher_anr_dialog(root: ElementTree.Element) -> bool:
    """Match the observed Android-owned launcher ANR alert without guessing from text alone."""
    title = any(
        node.get("package") == "android"
        and node.get("resource-id") == "android:id/alertTitle"
        and node.get("text") == "Pixel Launcher isn't responding"
        for node in root.iter("node")
    )
    actions = {
        node.get("resource-id"): node.get("text")
        for node in root.iter("node")
        if node.get("package") == "android"
    }
    return (
        title
        and actions.get("android:id/aerr_close") == "Close app"
        and actions.get("android:id/aerr_wait") == "Wait"
    )


def has_known_test_system_failure(report_root: Path, started_at_epoch: float) -> bool:
    return bool(classified_test_system_issue_ids(report_root, started_at_epoch))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--report-root", type=Path, required=True)
    parser.add_argument("--additional-report-root", type=Path, action="append", default=[])
    parser.add_argument("--started-at-epoch", type=float, required=True)
    args = parser.parse_args()
    issue_ids = classified_test_system_issue_ids(
        args.report_root,
        args.started_at_epoch,
        additional_report_roots=tuple(args.additional_report_root),
    )
    if not issue_ids:
        return 1
    print(f"classification_issue_ids={','.join(issue_ids)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
