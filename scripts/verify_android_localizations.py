#!/usr/bin/env python3
"""Fail closed when production Android resources fall back from supported locales."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

REQUIRED_LOCALES = ("values-zh-rCN", "values-zh-rTW")
RESOURCE_TAGS = {"string", "plurals", "string-array"}
PLACEHOLDER_RE = re.compile(r"%(?!%)(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?[a-zA-Z@]")


def placeholder_signature(value: str) -> tuple[str, ...]:
    return tuple(sorted(PLACEHOLDER_RE.findall(value)))


def element_text(element: ET.Element) -> str:
    return "".join(element.itertext()).strip()


def load_resources(path: Path) -> dict[tuple[str, str], ET.Element]:
    root = ET.parse(path).getroot()
    resources: dict[tuple[str, str], ET.Element] = {}
    for element in root:
        name = element.attrib.get("name")
        if element.tag not in RESOURCE_TAGS or not name:
            continue
        if element.attrib.get("translatable") == "false":
            continue
        key = (element.tag, name)
        if key in resources:
            raise ValueError(f"{path}: duplicate {element.tag} {name}")
        resources[key] = element
    return resources


def load_resource_directory(
    path: Path,
    *,
    require_files: bool = True,
) -> dict[tuple[str, str], ET.Element]:
    resources: dict[tuple[str, str], ET.Element] = {}
    files = sorted(path.glob("*.xml"))
    if require_files and not files:
        raise FileNotFoundError(f"no Android resource XML files found in {path}")
    for file in files:
        for key, element in load_resources(file).items():
            if key in resources:
                kind, name = key
                raise ValueError(f"{path}: duplicate {kind} {name} across resource files")
            resources[key] = element
    return resources


def signatures(element: ET.Element) -> list[tuple[str, ...]]:
    if element.tag == "string":
        return [placeholder_signature(element_text(element))]
    return [placeholder_signature(element_text(item)) for item in element.findall("item")]


def validate_resource_tree(res_root: Path) -> list[str]:
    base = load_resource_directory(res_root / "values")
    errors: list[str] = []
    for locale in REQUIRED_LOCALES:
        localized = load_resource_directory(res_root / locale, require_files=False)
        for key, base_element in sorted(base.items()):
            kind, name = key
            translated = localized.get(key)
            if translated is None:
                errors.append(f"{locale}: missing {kind} {name}")
                continue
            if not element_text(translated):
                errors.append(f"{locale}: blank {kind} {name}")
                continue
            base_signatures = signatures(base_element)
            translated_signatures = signatures(translated)
            if kind == "string-array" and len(base_signatures) != len(translated_signatures):
                errors.append(
                    f"{locale}: {name} item count {len(translated_signatures)} != {len(base_signatures)}"
                )
                continue
            expected = set(base_signatures)
            actual = set(translated_signatures)
            if not actual.issubset(expected) or any(signature not in actual for signature in expected if signature):
                errors.append(
                    f"{locale}: placeholder mismatch for {kind} {name}: "
                    f"expected {base_signatures}, got {translated_signatures}"
                )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--res-root",
        type=Path,
        default=Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "res",
    )
    args = parser.parse_args()
    try:
        errors = validate_resource_tree(args.res_root)
    except (ET.ParseError, ValueError, FileNotFoundError) as error:
        print("status=FAILED")
        print(f"localization_error={error}")
        return 1
    except OSError as error:
        print(f"status=BLOCKED\nreason={error}", file=sys.stderr)
        return 2
    if errors:
        print("status=FAILED")
        for error in errors:
            print(f"localization_error={error}")
        return 1
    print("status=PASSED")
    print("claim=all translatable production Android resources exist in zh-CN and zh-TW with compatible placeholders")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
