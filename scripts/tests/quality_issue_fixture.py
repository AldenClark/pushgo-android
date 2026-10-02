"""Current, owned issue registry for isolated test-system branch tests.

The checked-in registry is deliberately validated by its own integration test.
Branch tests use this fixture so an expired or resolved real issue cannot mask
the classifier, receipt, or disk-preflight behavior they intend to exercise.
"""

from __future__ import annotations

import json
import shutil
from datetime import date, timedelta
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]


def synthetic_issue_registry(as_of: date | None = None) -> dict:
    today = as_of or date.today()
    shared = {
        "status": "active",
        "owner": "Android quality-test fixture",
        "rationale": "Exercise one isolated test-system branch without production issue state",
        "exit_criteria": "The temporary fixture is removed after the test",
        "opened_on": today.isoformat(),
        "last_seen_on": today.isoformat(),
        "scope": ["Synthetic unit-test diagnostic"],
        "evidence": ["Synthetic test fixture"],
        "allowed_retries": 0,
        "quarantined": False,
    }
    return {
        "schema_version": 1,
        "platform": "android",
        "issues": [
            {
                **shared,
                "id": "android-quality-precondition",
                "kind": "precondition",
                "signatures": ["QUALITY_PRECONDITION"],
            },
            {
                **shared,
                "id": "android-compose-snapshot-observer-runtime",
                "kind": "flake",
                "expires_on": (today + timedelta(days=7)).isoformat(),
                "signatures": ["Detected multithreaded access to SnapshotStateObserver"],
            },
        ],
    }


def write_synthetic_registry(path: Path) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(synthetic_issue_registry(), indent=2) + "\n", encoding="utf-8")
    return path


def isolated_quality_scripts(root: Path, *script_names: str) -> Path:
    """Copy only the production bytes needed by one CLI branch into a tiny tree."""
    scripts = root / "scripts"
    scripts.mkdir(parents=True, exist_ok=True)
    for name in script_names:
        source = REPO / "scripts" / name
        destination = scripts / name
        shutil.copy2(source, destination)
        if destination.read_bytes() != source.read_bytes():
            raise AssertionError(f"isolated script differs from production source: {name}")
    write_synthetic_registry(root / "config/quality-test-system-issues.json")
    return root
