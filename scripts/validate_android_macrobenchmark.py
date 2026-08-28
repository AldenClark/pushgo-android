#!/usr/bin/env python3
"""Validate the physical frame-budget evidence emitted by Macrobenchmark."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any


DETAIL_BENCHMARK = "openAccurateMessageDetailStaysWithinFrameAndPurposeBudget"


class ContractError(RuntimeError):
    pass


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--max-frame-ms", required=True, type=float)
    parser.add_argument("--output", required=True)
    return parser.parse_args()


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        raise ContractError("frame metric has no numeric samples")
    ordered = sorted(values)
    index = max(0, math.ceil(quantile * len(ordered)) - 1)
    return ordered[index]


def numeric_samples(value: Any) -> list[float]:
    if isinstance(value, bool):
        return []
    if isinstance(value, (int, float)):
        return [float(value)]
    if isinstance(value, list):
        return [item for child in value for item in numeric_samples(child)]
    return []


def p95_from_metric(metric: dict[str, Any]) -> float:
    for key in ("P95", "p95", "percentile95", "percentile_95"):
        value = metric.get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
    for key in ("runs", "values", "samples"):
        samples = numeric_samples(metric.get(key))
        if samples:
            return percentile(samples, 0.95)
    raise ContractError("frameDurationCpuMs lacks P95 or numeric run samples")


def validate_frame_iterations(benchmark: dict[str, Any], frame_metric: dict[str, Any]) -> int:
    repeat_iterations = benchmark.get("repeatIterations")
    if (
        not isinstance(repeat_iterations, int)
        or isinstance(repeat_iterations, bool)
        or repeat_iterations <= 0
    ):
        raise ContractError("detail benchmark has no positive repeatIterations")

    runs = frame_metric.get("runs")
    if not isinstance(runs, list) or len(runs) != repeat_iterations:
        raise ContractError(
            "frameDurationCpuMs runs do not match the declared repeatIterations"
        )
    empty_iterations = [index for index, run in enumerate(runs) if not numeric_samples(run)]
    if empty_iterations:
        raise ContractError(
            f"detail benchmark produced no frames in iterations: {empty_iterations}"
        )

    metrics = benchmark.get("metrics")
    if not isinstance(metrics, dict):
        raise ContractError("detail benchmark has no metrics object")
    frame_count = metrics.get("frameCount")
    if not isinstance(frame_count, dict):
        raise ContractError("detail benchmark has no frameCount metric")
    counts = numeric_samples(frame_count.get("runs"))
    if len(counts) != repeat_iterations or any(count <= 0 for count in counts):
        raise ContractError("every detail iteration must report a positive frameCount")
    return repeat_iterations


def validate(payload: dict[str, Any], maximum_frame_ms: float) -> dict[str, Any]:
    if not math.isfinite(maximum_frame_ms) or maximum_frame_ms <= 0:
        raise ContractError("max-frame-ms must be a positive finite number")
    benchmarks = payload.get("benchmarks")
    if not isinstance(benchmarks, list):
        raise ContractError("benchmark JSON has no benchmarks array")
    matches = [
        item for item in benchmarks
        if isinstance(item, dict) and DETAIL_BENCHMARK in str(item.get("name", ""))
    ]
    if len(matches) != 1:
        raise ContractError(
            f"expected exactly one {DETAIL_BENCHMARK} result, found {len(matches)}"
        )
    benchmark = matches[0]
    sampled_metrics = benchmark.get("sampledMetrics")
    if not isinstance(sampled_metrics, dict):
        raise ContractError("detail benchmark has no sampledMetrics object")
    frame_metric = sampled_metrics.get("frameDurationCpuMs")
    if not isinstance(frame_metric, dict):
        raise ContractError("detail benchmark has no frameDurationCpuMs metric")
    repeat_iterations = validate_frame_iterations(benchmark, frame_metric)
    measured_p95 = p95_from_metric(frame_metric)
    return {
        "schema_version": 1,
        "benchmark": DETAIL_BENCHMARK,
        "metric": "frameDurationCpuMs.P95",
        "measured_ms": measured_p95,
        "budget_ms": maximum_frame_ms,
        "repeat_iterations": repeat_iterations,
        "status": "PASSED" if measured_p95 <= maximum_frame_ms else "FAILED",
    }


def main() -> int:
    args = parse_args()
    try:
        payload = json.loads(Path(args.input).read_text(encoding="utf-8"))
        receipt = validate(payload, args.max_frame_ms)
    except (OSError, json.JSONDecodeError, ContractError) as error:
        print(f"status=BLOCKED\nreason={error}", file=sys.stderr)
        return 2
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print(f"status={receipt['status']}")
    print(
        f"frame_p95_ms={receipt['measured_ms']} budget_ms={receipt['budget_ms']}"
    )
    return 0 if receipt["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
