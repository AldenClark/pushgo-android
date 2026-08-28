#!/usr/bin/env python3
"""Verify profile scope, Release packaging, and quality-control isolation."""

from __future__ import annotations

import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree


ANDROID = "{http://schemas.android.com/apk/res/android}"
PROFILE_RULE = re.compile(r"^[HSP]*Lio/ethan/pushgo/")
FORBIDDEN_PROFILE_PATHS = (
    "Lio/ethan/pushgo/automation/",
    "Lio/ethan/pushgo/testing/",
)
MAX_COMPILED_PROFILE_BYTES = 1_500_000
REQUIRED_SYMBOLS = (
    "Lio/ethan/pushgo/MainActivity;",
    "Lio/ethan/pushgo/PushGoApp;",
    "Lio/ethan/pushgo/data/MessageRepository;",
    "Lio/ethan/pushgo/ui/screens/MessageListScreen",
    "Lio/ethan/pushgo/ui/screens/MessageDetailScreen",
)


class ContractError(RuntimeError):
    pass


def one(paths: list[Path], description: str) -> Path:
    if len(paths) != 1:
        raise ContractError(f"expected one {description}, found {len(paths)}")
    return paths[0]


def read_profile(path: Path) -> set[str]:
    if not path.is_file():
        raise ContractError(f"missing profile source: {path}")
    rules = {line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()}
    if not rules:
        raise ContractError(f"profile source is empty: {path}")
    foreign = [rule for rule in rules if not PROFILE_RULE.match(rule)]
    if foreign:
        raise ContractError(f"profile contains non-PushGo rules: {foreign[0]}")
    quality_control = [
        rule for rule in rules
        if any(path in rule for path in FORBIDDEN_PROFILE_PATHS)
    ]
    if quality_control:
        raise ContractError(f"profile contains quality-control rules: {quality_control[0]}")
    return rules


def normalized_profile_rules(rules: set[str]) -> set[str]:
    """Compare rule identities without ART hot/startup/post-startup flags."""
    return {rule.lstrip("HSP") for rule in rules}


def verify(repo: Path) -> dict[str, int]:
    profile_root = repo / "app/src/release/generated/baselineProfiles"
    baseline_rules = read_profile(profile_root / "baseline-prof.txt")
    startup_rules = read_profile(profile_root / "startup-prof.txt")
    normalized_baseline = normalized_profile_rules(baseline_rules)
    normalized_startup = normalized_profile_rules(startup_rules)
    if not normalized_startup < normalized_baseline:
        raise ContractError(
            "startup rule identities must be a strict subset of baseline rule identities"
        )
    baseline_text = "\n".join(normalized_baseline)
    for symbol in REQUIRED_SYMBOLS:
        if symbol not in baseline_text:
            raise ContractError(f"baseline profile lacks critical PushGo path: {symbol}")

    apk = one(
        sorted((repo / "app/build/outputs/apk/release").glob("*universal*release*.apk")),
        "universal Release APK",
    )
    with zipfile.ZipFile(apk) as archive:
        sizes = {
            name: archive.getinfo(name).file_size
            for name in ("assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm")
            if name in archive.namelist()
        }
    if set(sizes) != {"assets/dexopt/baseline.prof", "assets/dexopt/baseline.profm"}:
        raise ContractError("Release APK lacks compiled baseline profile assets")
    if not 0 < sizes["assets/dexopt/baseline.prof"] < MAX_COMPILED_PROFILE_BYTES:
        raise ContractError("compiled baseline.prof is empty or exceeds 1.5MB")

    manifest = one(
        sorted((repo / "app/build/intermediates/packaged_manifests/release").glob(
            "processReleaseManifestForPackage/universal/AndroidManifest.xml"
        )),
        "packaged universal Release manifest",
    )
    root = ElementTree.parse(manifest).getroot()
    providers = [
        node for node in root.iter("provider")
        if node.get(ANDROID + "name") == "io.ethan.pushgo.testing.BenchmarkFixtureProvider"
    ]
    if len(providers) != 1:
        raise ContractError("Release manifest fixture-provider declaration is ambiguous")
    provider = providers[0]
    if provider.get(ANDROID + "enabled") != "false" or provider.get(ANDROID + "exported") != "false":
        raise ContractError("Release fixture provider is reachable")
    activities = [
        node for node in root.iter("activity")
        if node.get(ANDROID + "name") == "io.ethan.pushgo.testing.BenchmarkUnstopActivity"
    ]
    if len(activities) != 1:
        raise ContractError("Release unstop-control declaration is ambiguous")
    unstop_activity = activities[0]
    if (
        unstop_activity.get(ANDROID + "enabled") != "false"
        or unstop_activity.get(ANDROID + "exported") != "false"
    ):
        raise ContractError("Release unstop control is reachable")

    build_config = repo / "app/build/generated/source/buildConfig/release/io/ethan/pushgo/BuildConfig.java"
    config_text = build_config.read_text(encoding="utf-8")
    for field in ("QUALITY_RUNTIME_ENABLED", "QUALITY_SESSION_CONTROL_ENABLED"):
        if f"boolean {field} = false" not in config_text:
            raise ContractError(f"Release {field} is not false")
    mapping = (repo / "app/build/outputs/mapping/release/mapping.txt").read_text(encoding="utf-8")
    for control_class in (
        "io.ethan.pushgo.testing.BenchmarkFixtureProvider",
        "io.ethan.pushgo.testing.BenchmarkUnstopActivity",
    ):
        if control_class in mapping:
            raise ContractError(f"quality-control implementation entered Release dex: {control_class}")

    return {
        "baseline_rules": len(baseline_rules),
        "startup_rules": len(startup_rules),
        "compiled_profile_bytes": sizes["assets/dexopt/baseline.prof"],
    }


def main() -> int:
    repo = Path(__file__).resolve().parent.parent
    try:
        result = verify(repo)
    except (OSError, zipfile.BadZipFile, ElementTree.ParseError, ContractError) as error:
        print(f"status=FAILED\nreason={error}", file=sys.stderr)
        return 1
    print("status=PASSED")
    print(" ".join(f"{key}={value}" for key, value in result.items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
