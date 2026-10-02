#!/usr/bin/env python3
"""Resolve Android instrumentation class scopes to exact @Test selectors.

Gradle accepts a class-level instrumentation filter, but a class-level filter
does not prove that every method in the class ran.  This small source-backed
resolver lets the quality runner pass the exact expected selector set to the
fresh-report verifier without turning the product test itself into metadata.
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path


PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z_][\w.]*)\s*$")
CLASS_RE = re.compile(r"^\s*(?:public|private|internal|protected|sealed|abstract|open|final|data|enum|annotation|inner|value|expect|actual|\s)*class\s+([A-Za-z_][\w]*)\b")
TEST_RE = re.compile(r"^\s*@Test\b")
FUNCTION_RE = re.compile(r"^\s*(?:public|private|internal|protected|suspend|operator|infix|inline|tailrec|override|final|open|\s)*fun\s+([A-Za-z_][\w]*)\b")


def test_methods(source: str) -> list[str]:
    lines = source.splitlines()
    methods: list[str] = []
    index = 0
    while index < len(lines):
        if not TEST_RE.match(lines[index]):
            index += 1
            continue
        function_index = index + 1
        while function_index < len(lines) and (
            not lines[function_index].strip()
            or lines[function_index].lstrip().startswith("@")
        ):
            function_index += 1
        if function_index >= len(lines):
            raise ValueError("@Test annotation is not followed by a function")
        match = FUNCTION_RE.match(lines[function_index])
        if not match:
            raise ValueError(
                "@Test annotation is not followed by a Kotlin function: "
                + lines[function_index].strip()
            )
        name = match.group(1)
        if name in methods:
            raise ValueError(f"duplicate @Test method: {name}")
        methods.append(name)
        index = function_index + 1
    return methods


def discover_classes(source_root: Path) -> dict[str, tuple[Path, list[str]]]:
    discovered: dict[str, tuple[Path, list[str]]] = {}
    for path in sorted(source_root.rglob("*.kt")):
        source = path.read_text(encoding="utf-8")
        package = ""
        for line in source.splitlines():
            package_match = PACKAGE_RE.match(line)
            if package_match:
                package = package_match.group(1)
                break
        lines = source.splitlines()
        class_match = None
        for run_with_index, line in enumerate(lines):
            if "@RunWith" not in line:
                continue
            class_match = next(
                (
                    match
                    for candidate in lines[run_with_index + 1 :]
                    if (match := CLASS_RE.match(candidate))
                ),
                None,
            )
            if class_match:
                break
        if class_match is None:
            class_match = next(
                (match for line in lines if (match := CLASS_RE.match(line))),
                None,
            )
        if not class_match:
            continue
        class_name = class_match.group(1)
        qualified_name = f"{package}.{class_name}" if package else class_name
        methods = test_methods(source)
        if methods:
            if qualified_name in discovered:
                raise ValueError(f"duplicate instrumentation class: {qualified_name}")
            discovered[qualified_name] = (path, methods)
    return discovered


def resolve(
    source_root: Path,
    scopes: list[str],
    excluded: set[str],
) -> list[str]:
    classes = discover_classes(source_root)
    selectors: list[str] = []
    for raw_scope in scopes:
        scope = raw_scope.strip()
        if not scope:
            raise ValueError("empty Android instrumentation scope")
        if "#" in scope:
            class_name, method = scope.split("#", 1)
            class_info = classes.get(class_name)
            if class_info is None:
                raise ValueError(f"unknown Android instrumentation class: {class_name}")
            if method not in class_info[1]:
                raise ValueError(f"unknown @Test method: {scope}")
            candidates = [scope]
        else:
            class_info = classes.get(scope)
            if class_info is None:
                raise ValueError(f"unknown Android instrumentation class: {scope}")
            candidates = [f"{scope}#{method}" for method in class_info[1]]
        selectors.extend(selector for selector in candidates if selector not in excluded)
    if not selectors:
        raise ValueError("Android instrumentation scope resolves to zero @Test methods")
    if len(set(selectors)) != len(selectors):
        raise ValueError("Android instrumentation scope resolves duplicate selectors")
    return selectors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--scopes", required=True)
    parser.add_argument("--exclude-selector", action="append", default=[])
    args = parser.parse_args()
    if not args.source_root.is_dir():
        raise SystemExit(f"Android instrumentation source root is not a directory: {args.source_root}")
    try:
        selectors = resolve(
            args.source_root,
            args.scopes.split(","),
            {item.strip() for item in args.exclude_selector if item.strip()},
        )
    except (OSError, ValueError) as error:
        print(f"status=BLOCKED")
        print(f"reason=android_expected_selector_resolution:{error}")
        return 2
    print(",".join(selectors))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
