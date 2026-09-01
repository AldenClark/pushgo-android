#!/usr/bin/env python3
"""Verify the exact class and method reported by `am instrument -r`."""

from __future__ import annotations

import argparse
import re
import sys


CLASS_PATTERN = re.compile(r"^INSTRUMENTATION_STATUS: class=(.+)$", re.MULTILINE)
METHOD_PATTERN = re.compile(r"^INSTRUMENTATION_STATUS: test=(.+)$", re.MULTILINE)


def observed_identity(output: str) -> tuple[set[str], set[str]]:
    return (
        {value.strip() for value in CLASS_PATTERN.findall(output) if value.strip()},
        {value.strip() for value in METHOD_PATTERN.findall(output) if value.strip()},
    )


def identity_matches(output: str, expected_class: str, expected_method: str) -> bool:
    classes, methods = observed_identity(output)
    return classes == {expected_class} and methods == {expected_method}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-class", required=True)
    parser.add_argument("--expected-method", required=True)
    args = parser.parse_args()
    output = sys.stdin.read()
    classes, methods = observed_identity(output)
    if classes != {args.expected_class} or methods != {args.expected_method}:
        print("status=FAILED_TEST_SYSTEM")
        print(
            "reason=android_instrumentation_identity_mismatch:"
            f"classes={','.join(sorted(classes)) or '-'}:"
            f"methods={','.join(sorted(methods)) or '-'}"
        )
        return 1
    print("status=EXECUTED_IDENTITY")
    print(f"executed_selector={args.expected_class}#{args.expected_method}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
