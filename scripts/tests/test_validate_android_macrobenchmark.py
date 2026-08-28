import importlib.util
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "validate_android_macrobenchmark",
    REPO / "scripts/validate_android_macrobenchmark.py",
)
assert SPEC and SPEC.loader
VALIDATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VALIDATOR)


def payload(frame_runs, frame_counts):
    return {
        "benchmarks": [
            {
                "name": VALIDATOR.DETAIL_BENCHMARK,
                "repeatIterations": len(frame_runs),
                "metrics": {"frameCount": {"runs": frame_counts}},
                "sampledMetrics": {
                    "frameDurationCpuMs": {
                        "P95": 15.5,
                        "runs": frame_runs,
                    }
                },
            }
        ]
    }


class MacrobenchmarkEvidenceContractTests(unittest.TestCase):
    def test_complete_iterations_within_budget_pass(self):
        receipt = VALIDATOR.validate(payload([[10.0], [15.5]], [1.0, 1.0]), 16.0)

        self.assertEqual("PASSED", receipt["status"])
        self.assertEqual(2, receipt["repeat_iterations"])

    def test_complete_iterations_over_budget_fail(self):
        receipt = VALIDATOR.validate(payload([[10.0], [15.5]], [1.0, 1.0]), 15.0)

        self.assertEqual("FAILED", receipt["status"])

    def test_empty_iteration_is_blocked_even_with_reported_p95(self):
        with self.assertRaisesRegex(VALIDATOR.ContractError, "produced no frames"):
            VALIDATOR.validate(payload([[10.0], []], [1.0, 0.0]), 1000.0)

    def test_missing_sampled_metric_is_blocked(self):
        malformed = payload([[10.0]], [1.0])
        malformed["benchmarks"][0]["sampledMetrics"] = {}

        with self.assertRaisesRegex(VALIDATOR.ContractError, "frameDurationCpuMs"):
            VALIDATOR.validate(malformed, 16.0)


if __name__ == "__main__":
    unittest.main()
