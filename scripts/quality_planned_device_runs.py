#!/usr/bin/env python3
"""Validate and render structured Android impact-selected device runs."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


ALLOWED_PROFILES = {
    "generic",
    "app-owned",
    "accessibility",
    "notification-permission",
    "system-notification",
}


def validated_runs(plan: dict[str, Any]) -> list[tuple[str, list[str], int]]:
    if plan.get("plan_status") == "BLOCKED" or plan.get("selection_blockers"):
        raise ValueError("impact plan contains unresolved selection blockers")
    runs = plan.get("required_device_runs")
    flat = plan.get("required_device_scopes")
    if not isinstance(runs, list) or not runs or not isinstance(flat, list):
        raise ValueError("impact plan has no structured required device runs")
    validated: list[tuple[str, list[str], int]] = []
    seen: list[str] = []
    for run in runs:
        if not isinstance(run, dict):
            raise ValueError("required device run must be an object")
        profile = run.get("profile")
        scopes = run.get("scopes")
        expected = run.get("expected_test_count")
        if profile not in ALLOWED_PROFILES:
            raise ValueError(f"unsupported device execution profile: {profile}")
        if (
            not isinstance(scopes, list)
            or not scopes
            or any(
                not isinstance(scope, str)
                or scope.count("#") != 1
                or any(character.isspace() for character in scope)
                or "," in scope
                for scope in scopes
            )
            or isinstance(expected, bool)
            or expected != len(scopes)
        ):
            raise ValueError(f"invalid scopes for device execution profile: {profile}")
        seen.extend(scopes)
        validated.append((profile, scopes, expected))
    if len(seen) != len(set(seen)) or sorted(seen) != sorted(flat):
        raise ValueError("structured device runs do not exactly partition required scopes")
    return validated


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--plan", required=True, type=Path)
    args = parser.parse_args()
    try:
        plan = json.loads(args.plan.read_text(encoding="utf-8"))
        runs = validated_runs(plan)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"error={error}")
        return 2
    for profile, scopes, expected in runs:
        print(f"{profile}\t{','.join(scopes)}\t{expected}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
