#!/usr/bin/env python3
"""Select the deterministic minimum quality lane for changed repository paths.

Path rules are a lower bound, not a substitute for caller/data/platform impact
analysis. Unknown product paths are blocked so a new capability cannot silently
fall outside the quality system.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import os
import re
import subprocess
import tempfile
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", default="config/quality-impact.json")
    parser.add_argument("--output", required=True)
    parser.add_argument("--base")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--changed-file", action="append", default=[])
    parser.add_argument("--audit-product-tree", action="store_true")
    parser.add_argument("--check", action="store_true")
    return parser.parse_args()


def normalize_path(raw: str) -> str:
    value = raw.strip().replace("\\", "/")
    while value.startswith("./"):
        value = value[2:]
    path = PurePosixPath(value)
    if not value or path.is_absolute() or ".." in path.parts:
        raise ValueError(f"unsupported repository path: {raw!r}")
    return str(path)


def unique(values: Iterable[str]) -> list[str]:
    return list(dict.fromkeys(values))


def matches_any(path: str, patterns: list[str]) -> bool:
    return any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)


def git_lines(repo: Path, args: list[str]) -> list[str]:
    process = subprocess.run(
        ["git", *args],
        cwd=repo,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if process.returncode != 0:
        detail = process.stderr.strip() or process.stdout.strip()
        raise RuntimeError(f"git {' '.join(args)} failed: {detail}")
    return [line for line in process.stdout.splitlines() if line.strip()]


def git_text(repo: Path, args: list[str], *, allow_missing: bool = False) -> str | None:
    process = subprocess.run(
        ["git", *args],
        cwd=repo,
        check=False,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if process.returncode == 0:
        return process.stdout
    if allow_missing:
        return None
    detail = process.stderr.strip() or process.stdout.strip()
    raise RuntimeError(f"git {' '.join(args)} failed: {detail}")


def changed_files(args: argparse.Namespace, repo: Path) -> tuple[list[str], str]:
    if args.changed_file:
        return unique(normalize_path(path) for path in args.changed_file), "explicit"
    if args.audit_product_tree:
        files = git_lines(repo, ["ls-files"])
        return unique(normalize_path(path) for path in files), "tracked-product-tree-audit"
    if args.base:
        for revision in (args.base, args.head):
            git_lines(repo, ["rev-parse", "--verify", f"{revision}^{{commit}}"])
        files = git_lines(
            repo,
            ["diff", "--name-only", "--diff-filter=ACDMRTUXB", f"{args.base}...{args.head}", "--"],
        )
        return unique(normalize_path(path) for path in files), f"git:{args.base}...{args.head}"
    tracked = git_lines(repo, ["diff", "--name-only", "--diff-filter=ACDMRTUXB", "HEAD", "--"])
    untracked = git_lines(repo, ["ls-files", "--others", "--exclude-standard"])
    return unique(normalize_path(path) for path in [*tracked, *untracked]), "working-tree"


INSTRUMENTED_TEST_PREFIX = "app/src/androidTest/"
DEVICE_EXECUTION_PROFILES = {
    "io.ethan.pushgo.testing.QualityNotificationPermissionJourneyInstrumentedTest": "notification-permission",
    "io.ethan.pushgo.testing.QualityAccessibilityLocalizationJourneyInstrumentedTest": "accessibility",
    "io.ethan.pushgo.testing.QualitySystemNotificationJourneyInstrumentedTest": "system-notification",
    "io.ethan.pushgo.testing.QualityPrivateForegroundServiceJourneyInstrumentedTest": "system-notification",
}
SYSTEM_NOTIFICATION_PROFILE_SCOPES = {
    "io.ethan.pushgo.testing.QualitySystemNotificationJourneyInstrumentedTest#duplicateInboundDeliveryPostsOneSystemNotificationAndOpensAccurateReadDetail",
    "io.ethan.pushgo.testing.QualitySystemNotificationJourneyInstrumentedTest#entityInboundNotificationsOpenExactColdEventAndWarmThingDetails",
    "io.ethan.pushgo.testing.QualityPrivateForegroundServiceJourneyInstrumentedTest#privateSelectionStartsDurableSystemServiceAndFcmSelectionStopsIt",
}
PACKAGE_PATTERN = re.compile(r"^\s*package\s+([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)\s*$", re.MULTILINE)
CLASS_PATTERN = re.compile(r"^\s*(?:public\s+|internal\s+)?(?:abstract\s+)?class\s+([A-Za-z_]\w*)\b", re.MULTILINE)
TEST_ANNOTATION_PATTERN = re.compile(r"^\s*@(?:org\.junit\.)?Test(?:\s*\([^\n]*\))?\s*$")
FUNCTION_PATTERN = re.compile(
    r"^(?P<indent>\s*)(?:(?:public|private|protected|internal|open|final|override|suspend|inline|operator|infix|tailrec)\s+)*fun\s+(?:<[^>]+>\s*)?(?P<name>[A-Za-z_]\w*)\s*\("
)
HUNK_PATTERN = re.compile(r"^@@\s+-(\d+)(?:,(\d+))?\s+\+(\d+)(?:,(\d+))?\s+@@")


def kotlin_test_class(source: str, path: str) -> str | None:
    package_match = PACKAGE_PATTERN.search(source)
    if not package_match:
        return None
    expected_class = Path(path).stem
    classes = {match.group(1) for match in CLASS_PATTERN.finditer(source)}
    if expected_class not in classes:
        return None
    return f"{package_match.group(1)}.{expected_class}"


def kotlin_runnable_test_class(source: str, path: str) -> str | None:
    class_scope = kotlin_test_class(source, path)
    methods = kotlin_test_method_ranges(source)
    return class_scope if class_scope and methods else None


def device_execution_profile(selector: str) -> str:
    class_name = selector.split("#", 1)[0]
    if class_name in DEVICE_EXECUTION_PROFILES:
        return DEVICE_EXECUTION_PROFILES[class_name]
    if class_name.startswith("io.ethan.pushgo.testing.Quality"):
        return "app-owned"
    return "generic"


def shared_test_support_consumer_impact(
    repo: Path, path: str, support_source: str
) -> dict[str, Any] | None:
    identifiers = {Path(path).stem}
    identifiers.update(match.group("name") for line in support_source.splitlines() if (match := FUNCTION_PATTERN.match(line)))
    identifiers = {identifier for identifier in identifiers if identifier}
    scopes: set[str] = set()
    test_root = repo / INSTRUMENTED_TEST_PREFIX
    for candidate in test_root.rglob("*.kt"):
        candidate_path = candidate.relative_to(repo).as_posix()
        if candidate_path == path:
            continue
        source = candidate.read_text(encoding="utf-8")
        if not any(re.search(rf"\b{re.escape(identifier)}\b", source) for identifier in identifiers):
            continue
        class_scope = kotlin_test_class(source, candidate_path)
        methods = kotlin_test_method_ranges(source)
        if not class_scope or not methods:
            continue
        scopes.update(f"{class_scope}#{name}" for name in methods)
    if not scopes:
        return None
    return {
        "selection": "changed-consumers",
        "scopes": sorted(scopes),
        "expected_test_count": len(scopes),
        "blocker": None,
    }


def kotlin_test_method_ranges(source: str) -> dict[str, tuple[int, int]] | None:
    lines = source.splitlines()
    methods: dict[str, tuple[int, int]] = {}
    index = 0
    while index < len(lines):
        if not TEST_ANNOTATION_PATTERN.match(lines[index]):
            index += 1
            continue
        annotation_line = index + 1
        function_index = index + 1
        while function_index < len(lines) and (
            not lines[function_index].strip() or lines[function_index].lstrip().startswith("@")
        ):
            function_index += 1
        if function_index >= len(lines):
            return None
        function_match = FUNCTION_PATTERN.match(lines[function_index])
        if not function_match:
            return None
        name = function_match.group("name")
        indent = len(function_match.group("indent").replace("\t", "    "))
        if name in methods:
            return None
        end_line = len(lines)
        for candidate_index in range(function_index + 1, len(lines)):
            candidate = lines[candidate_index]
            stripped = candidate.strip()
            if not stripped:
                continue
            candidate_indent = len(candidate) - len(candidate.lstrip(" \t"))
            if candidate_indent < indent or (
                candidate_indent == indent
                and (stripped.startswith("@") or FUNCTION_PATTERN.match(candidate))
            ):
                end_line = candidate_index
                break
        methods[name] = (annotation_line, end_line)
        index = function_index + 1
    return methods


def changed_hunk_ranges(patch: str) -> tuple[list[tuple[int, int]], list[tuple[int, int]]] | None:
    old_ranges: list[tuple[int, int]] = []
    new_ranges: list[tuple[int, int]] = []
    for line in patch.splitlines():
        if not line.startswith("@@"):
            continue
        match = HUNK_PATTERN.match(line)
        if not match:
            return None
        old_start, old_count, new_start, new_count = (
            int(match.group(1)),
            int(match.group(2) or "1"),
            int(match.group(3)),
            int(match.group(4) or "1"),
        )
        if old_count:
            old_ranges.append((old_start, old_start + old_count - 1))
        if new_count:
            new_ranges.append((new_start, new_start + new_count - 1))
    return (old_ranges, new_ranges) if old_ranges or new_ranges else None


def methods_covering_changes(
    methods: dict[str, tuple[int, int]], changed_ranges: list[tuple[int, int]]
) -> tuple[set[str], bool, bool]:
    selected: set[str] = set()
    has_unowned_change = False
    has_ambiguous_change = False
    for changed_start, changed_end in changed_ranges:
        covering = {
            name
            for name, (method_start, method_end) in methods.items()
            if changed_start <= method_end and changed_end >= method_start
        }
        if len(covering) > 1:
            # A zero-context diff can combine adjacent method additions or
            # edits into one hunk. Keep every covered method so the caller can
            # run the exact affected methods instead of silently retaining
            # only the first pre-existing method.
            has_ambiguous_change = True
        if not covering:
            has_unowned_change = True
        selected.update(covering)
    return selected, has_unowned_change, has_ambiguous_change


def resolve_kotlin_instrumented_test_change(
    path: str,
    old_source: str | None,
    new_source: str | None,
    patch: str | None,
) -> dict[str, Any]:
    if new_source is None:
        return {
            "selection": "blocked",
            "scopes": [],
            "expected_test_count": 0,
            "blocker": f"changed instrumented test source was deleted: {path}",
        }
    new_class = kotlin_test_class(new_source, path)
    new_methods = kotlin_test_method_ranges(new_source)
    if not new_class or not new_methods:
        return {
            "selection": "blocked",
            "scopes": [],
            "expected_test_count": 0,
            "blocker": f"unable to resolve a runnable changed instrumented test class: {path}",
        }

    def class_impact() -> dict[str, Any]:
        scopes = [f"{new_class}#{name}" for name in sorted(new_methods)]
        return {
            "selection": "changed-class",
            "scopes": scopes,
            "expected_test_count": len(scopes),
            "blocker": None,
        }

    if old_source is None:
        return class_impact()
    old_class = kotlin_test_class(old_source, path)
    old_methods = kotlin_test_method_ranges(old_source)
    ranges = changed_hunk_ranges(patch or "")
    if not old_class or old_class != new_class or not old_methods or not ranges:
        return {
            "selection": "blocked",
            "scopes": [],
            "expected_test_count": 0,
            "blocker": f"unable to attribute changed instrumented test source safely: {path}",
        }
    old_selected, old_unowned, old_ambiguous = methods_covering_changes(old_methods, ranges[0])
    new_selected, new_unowned, new_ambiguous = methods_covering_changes(new_methods, ranges[1])
    removed_methods = old_selected - set(new_methods)
    if removed_methods:
        return {
            "selection": "blocked",
            "scopes": [],
            "expected_test_count": 0,
            "blocker": (
                f"changed instrumented test method was removed or renamed in {path}: "
                f"{','.join(sorted(removed_methods))}"
            ),
        }
    added_methods_only = (
        not old_selected
        and bool(new_selected)
        and not new_unowned
        and all(name not in old_methods for name in new_selected)
    )
    same_existing_methods = (
        bool(new_selected)
        and old_selected == new_selected
        and not old_unowned
        and not new_unowned
    )
    one_sided_existing_method_change = (
        (
            bool(new_selected)
            and not old_selected
            and not new_unowned
            and all(name in old_methods for name in new_selected)
        )
        or (
            bool(old_selected)
            and not new_selected
            and not old_unowned
            and all(name in new_methods for name in old_selected)
        )
    )
    if added_methods_only or same_existing_methods or one_sided_existing_method_change:
        selected = new_selected or old_selected
        scopes = [f"{new_class}#{name}" for name in sorted(selected)]
        return {
            "selection": "exact-method",
            "scopes": scopes,
            "expected_test_count": len(scopes),
            "blocker": None,
        }
    if (
        (old_ambiguous or new_ambiguous)
        and not old_unowned
        and not new_unowned
        and new_selected
        and all(name in new_methods for name in old_selected)
    ):
        # Adjacent runnable methods may share one diff hunk. The hunk is still
        # safely attributable when every changed line is inside a runnable
        # method; execute the union, never the first method only.
        scopes = [f"{new_class}#{name}" for name in sorted(new_selected)]
        return {
            "selection": "exact-method",
            "scopes": scopes,
            "expected_test_count": len(scopes),
            "blocker": None,
        }
    return class_impact()


def exact_changed_test_scopes(path: str, old_source: str, new_source: str, patch: str) -> list[str] | None:
    impact = resolve_kotlin_instrumented_test_change(path, old_source, new_source, patch)
    return impact["scopes"] if impact["selection"] == "exact-method" else None


def instrumented_test_impacts(
    args: argparse.Namespace, repo: Path, files: list[str], source: str
) -> dict[str, dict[str, Any]] | None:
    if source == "tracked-product-tree-audit":
        return None
    impacts: dict[str, dict[str, Any]] = {}
    test_paths = [path for path in files if path.startswith(INSTRUMENTED_TEST_PREFIX) and path.endswith(".kt")]
    merge_base: str | None = None
    if source.startswith("git:") and args.base:
        merge_base_lines = git_lines(repo, ["merge-base", args.base, args.head])
        merge_base = merge_base_lines[0] if merge_base_lines else args.base
    name_status: str | None = None
    if source == "working-tree":
        name_status = git_text(repo, ["diff", "--name-status", "-M", "HEAD", "--"])
    elif merge_base:
        name_status = git_text(
            repo, ["diff", "--name-status", "-M", f"{args.base}...{args.head}", "--"]
        )
    for line in (name_status or "").splitlines():
        fields = line.split("\t")
        if len(fields) != 3 or not fields[0].startswith("R"):
            continue
        old_path, new_path = map(normalize_path, fields[1:])
        if not old_path.startswith(INSTRUMENTED_TEST_PREFIX) and not new_path.startswith(INSTRUMENTED_TEST_PREFIX):
            continue
        managed_path = new_path if new_path.startswith(INSTRUMENTED_TEST_PREFIX) else old_path
        impacts[managed_path] = {
            "selection": "blocked",
            "scopes": [],
            "expected_test_count": 0,
            "blocker": (
                "instrumented test source was renamed and requires explicit impact review: "
                f"{old_path} -> {new_path}"
            ),
        }
    for path in test_paths:
        if path in impacts:
            continue
        current_path = repo / path
        current_source = current_path.read_text(encoding="utf-8") if current_path.is_file() else None
        old_source: str | None = None
        patch: str | None = None
        if source == "working-tree" and current_source is not None:
            old_source = git_text(repo, ["show", f"HEAD:{path}"], allow_missing=True)
            patch = git_text(repo, ["diff", "--unified=0", "HEAD", "--", path])
        elif source == "working-tree":
            old_source = git_text(repo, ["show", f"HEAD:{path}"], allow_missing=True)
            patch = git_text(repo, ["diff", "--unified=0", "HEAD", "--", path])
        elif merge_base:
            old_source = git_text(repo, ["show", f"{merge_base}:{path}"], allow_missing=True)
            current_source = git_text(repo, ["show", f"{args.head}:{path}"], allow_missing=True)
            patch = git_text(repo, ["diff", "--unified=0", f"{args.base}...{args.head}", "--", path])
        impact = resolve_kotlin_instrumented_test_change(
            path, old_source, current_source, patch
        )
        if (
            impact.get("blocker")
            and "unable to resolve a runnable changed instrumented test class" in impact["blocker"]
            and (current_source or old_source)
        ):
            consumer_impact = shared_test_support_consumer_impact(
                repo, path, current_source or old_source or ""
            )
            if consumer_impact:
                impact = consumer_impact
        impacts[path] = impact
    return impacts or None


def load_manifest(path: Path) -> dict[str, Any]:
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("schema_version") != 1:
        raise ValueError("quality impact manifest schema_version must be 1")
    lane_order = manifest.get("lane_order")
    if not isinstance(lane_order, list) or not lane_order or lane_order[0] != "not-run":
        raise ValueError("lane_order must be a non-empty list beginning with not-run")
    seen: set[str] = set()
    for rule in manifest.get("rules", []):
        rule_id = rule.get("id")
        if not isinstance(rule_id, str) or not rule_id or rule_id in seen:
            raise ValueError(f"invalid or duplicate rule id: {rule_id!r}")
        seen.add(rule_id)
        if rule.get("lane") not in lane_order:
            raise ValueError(f"rule {rule_id} uses unsupported lane {rule.get('lane')!r}")
        for key in ("paths", "capabilities", "minimum_evidence"):
            if not isinstance(rule.get(key), list) or not rule[key]:
                raise ValueError(f"rule {rule_id} requires a non-empty {key} list")
        if "required_checks" in rule:
            checks = rule["required_checks"]
            if not isinstance(checks, list) or any(not isinstance(item, str) or not item for item in checks):
                raise ValueError(f"rule {rule_id} required_checks must be a list of non-empty strings")
        if "required_device_scopes" in rule:
            scopes = rule["required_device_scopes"]
            if (
                not isinstance(scopes, list)
                or not scopes
                or any(not isinstance(item, str) or "#" not in item for item in scopes)
            ):
                raise ValueError(
                    f"rule {rule_id} required_device_scopes must be a non-empty list "
                    "of class#method selectors"
                )
    return manifest


def build_plan(
    files: list[str],
    manifest: dict[str, Any],
    source: str,
    instrumented_impacts: dict[str, Any] | None = None,
) -> dict[str, Any]:
    lane_order = manifest["lane_order"]
    lane_rank = {lane: index for index, lane in enumerate(lane_order)}
    rules = manifest["rules"]
    product_patterns = manifest["product_paths"]
    path_matches: dict[str, list[str]] = {}
    changed_product_paths: list[str] = []
    ignored_paths: list[str] = []
    unmapped_product_paths: list[str] = []
    matched_rules: dict[str, dict[str, Any]] = {}

    for path in files:
        matching = [rule for rule in rules if matches_any(path, rule["paths"])]
        path_matches[path] = [rule["id"] for rule in matching]
        for rule in matching:
            matched_rules[rule["id"]] = rule
        if matches_any(path, product_patterns):
            changed_product_paths.append(path)
            if not matching:
                unmapped_product_paths.append(path)
        elif not matching:
            ignored_paths.append(path)

    selected = list(matched_rules.values())
    recommended_lane = max(
        (rule["lane"] for rule in selected),
        key=lambda lane: lane_rank[lane],
        default="not-run",
    )
    selected_lanes = {rule["lane"] for rule in selected}
    if "performance" in selected_lanes and selected_lanes.intersection({"device", "nightly"}):
        recommended_lane = "release"
    recommended_lane_rules = [rule for rule in selected if rule["lane"] == recommended_lane]
    changed_instrumented_paths = sorted(
        {
            *[
                path
                for path in files
                if path.startswith(INSTRUMENTED_TEST_PREFIX) and path.endswith(".kt")
            ],
            *(instrumented_impacts or {}).keys(),
        }
    )
    dynamic_impacts: dict[str, dict[str, Any]] = {}
    for path, raw_impact in (instrumented_impacts or {}).items():
        if isinstance(raw_impact, list):
            dynamic_impacts[path] = {
                "selection": (
                    "exact-method"
                    if raw_impact and all("#" in scope for scope in raw_impact)
                    else "changed-class"
                    if raw_impact
                    else "blocked"
                ),
                "scopes": raw_impact,
                "expected_test_count": len(raw_impact),
                "blocker": (
                    None
                    if raw_impact
                    else f"unable to resolve a runnable changed instrumented test class: {path}"
                ),
            }
        else:
            dynamic_impacts[path] = raw_impact
    dynamic_selection_requested = instrumented_impacts is not None and bool(changed_instrumented_paths)
    dynamic_selection_resolved = dynamic_selection_requested and all(
        (impact := dynamic_impacts.get(path))
        and not impact.get("blocker")
        and impact.get("scopes")
        and impact.get("expected_test_count") == len(impact["scopes"])
        for path in changed_instrumented_paths
    )
    unresolved_instrumented_test_paths = (
        [
            path
            for path in changed_instrumented_paths
            if not dynamic_impacts.get(path)
            or dynamic_impacts[path].get("blocker")
            or not dynamic_impacts[path].get("scopes")
        ]
        if dynamic_selection_requested
        else []
    )
    static_scope_rules = [
        rule
        for rule in recommended_lane_rules
        if not any(matches_any(path, rule["paths"]) for path in changed_instrumented_paths)
    ]
    if not dynamic_selection_requested:
        static_scope_rules = recommended_lane_rules
    can_narrow_device_scope = (
        recommended_lane in {"pr-ui", "device", "nightly"}
        and bool(recommended_lane_rules)
        and (not dynamic_selection_requested or dynamic_selection_resolved)
        and all(rule.get("required_device_scopes") for rule in static_scope_rules)
    )
    required_device_scopes = (
        sorted(
            {
                item
                for scopes in (
                    [rule.get("required_device_scopes", []) for rule in static_scope_rules]
                    + (
                        [dynamic_impacts[path]["scopes"] for path in changed_instrumented_paths]
                        if dynamic_selection_requested
                        else []
                    )
                )
                for item in scopes
            }
        )
        if can_narrow_device_scope
        else []
    )
    if any(device_execution_profile(scope) == "system-notification" for scope in required_device_scopes):
        required_device_scopes = sorted(
            set(required_device_scopes).union(SYSTEM_NOTIFICATION_PROFILE_SCOPES)
        )
    profile_scopes: dict[str, list[str]] = {}
    for scope in required_device_scopes:
        profile_scopes.setdefault(device_execution_profile(scope), []).append(scope)
    required_device_runs = [
        {"profile": profile, "scopes": scopes, "expected_test_count": len(scopes)}
        for profile, scopes in sorted(profile_scopes.items())
    ]
    plan_status = (
        "BLOCKED"
        if unmapped_product_paths or (dynamic_selection_requested and not dynamic_selection_resolved)
        else "NOT_RUN"
        if not selected
        else "READY"
    )
    return {
        "schema_version": 1,
        "platform": manifest["platform"],
        "plan_status": plan_status,
        "change_source": source,
        "changed_files": files,
        "changed_product_paths": changed_product_paths,
        "path_matches": path_matches,
        "selected_rule_ids": sorted(matched_rules),
        "impacted_capabilities": sorted({item for rule in selected for item in rule["capabilities"]}),
        "minimum_evidence": sorted({item for rule in selected for item in rule["minimum_evidence"]}),
        "required_checks": sorted({item for rule in selected for item in rule.get("required_checks", [])}),
        "device_scope_selection": "focused" if can_narrow_device_scope else "full-lane",
        "instrumented_test_scope_selection": (
            next(iter({impact["selection"] for impact in dynamic_impacts.values()}))
            if dynamic_selection_resolved
            and len({impact["selection"] for impact in dynamic_impacts.values()}) == 1
            else "mixed"
            if dynamic_selection_resolved
            else "full-lane"
            if dynamic_selection_requested
            else "not-applicable"
        ),
        "instrumented_test_impacts": {
            path: dynamic_impacts.get(path, {}) for path in changed_instrumented_paths
        },
        "selection_blockers": [
            dynamic_impacts.get(path, {}).get("blocker")
            or f"unable to resolve a runnable changed instrumented test class: {path}"
            for path in unresolved_instrumented_test_paths
        ],
        "required_device_scopes": required_device_scopes,
        "required_device_runs": required_device_runs,
        "recommended_lane": recommended_lane,
        "known_evidence_gaps": sorted({item for rule in selected for item in rule.get("known_evidence_gaps", [])}),
        "escalation_reasons": sorted({item for rule in selected for item in rule.get("escalation_reasons", [])}),
        "unmapped_product_paths": unmapped_product_paths,
        "ignored_paths": ignored_paths,
        "manual_impact_review_required": bool(changed_product_paths),
        "manual_impact_questions": manifest["manual_impact_questions"] if changed_product_paths else [],
        "scope_notice": (
            "This plan is a deterministic lower bound. A human or AI must still trace callers, "
            "state/data owners, errors, configuration, generated artifacts, and platform consumers. "
            "A READY plan is not evidence that a product capability passed."
        ),
    }


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
        temporary = Path(handle.name)
    os.replace(temporary, path)


def main() -> int:
    args = parse_args()
    repo = Path(__file__).resolve().parent.parent
    manifest_path = Path(args.manifest)
    if not manifest_path.is_absolute():
        manifest_path = repo / manifest_path
    manifest = load_manifest(manifest_path)
    files, source = changed_files(args, repo)
    impacts = instrumented_test_impacts(args, repo, files, source)
    plan = build_plan(files, manifest, source, impacts)
    output = Path(args.output)
    if not output.is_absolute():
        output = repo / output
    write_json(output, plan)
    print(f"impact_plan={output}")
    print(f"plan_status={plan['plan_status']}")
    print(f"recommended_lane={plan['recommended_lane']}")
    if plan["impacted_capabilities"]:
        print(f"impacted_capabilities={','.join(plan['impacted_capabilities'])}")
    if plan["known_evidence_gaps"]:
        print(f"known_evidence_gaps={'; '.join(plan['known_evidence_gaps'])}")
    if plan["unmapped_product_paths"]:
        print(f"unmapped_product_paths={','.join(plan['unmapped_product_paths'])}")
    if plan["selection_blockers"]:
        print(f"selection_blockers={'; '.join(plan['selection_blockers'])}")
    return 2 if args.check and plan["plan_status"] == "BLOCKED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
