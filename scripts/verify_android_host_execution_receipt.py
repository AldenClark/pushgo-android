#!/usr/bin/env python3
"""Require exactly one independently verified selector in a host-runner receipt."""

from __future__ import annotations

import argparse
import re
import sys


SELECTOR_PATTERN = re.compile(r"^executed_selector=(.+)$", re.MULTILINE)


def receipt_matches(output: str, expected: str) -> bool:
    return SELECTOR_PATTERN.findall(output) == [expected]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--expected-selector", required=True)
    args = parser.parse_args()
    output = sys.stdin.read()
    selectors = SELECTOR_PATTERN.findall(output)
    if selectors != [args.expected_selector]:
        print("status=FAILED_TEST_SYSTEM")
        print(
            "reason=android_host_execution_receipt_mismatch:"
            f"observed={','.join(selectors) or '-'}"
        )
        return 1
    print("status=EXECUTED_RECEIPT")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
