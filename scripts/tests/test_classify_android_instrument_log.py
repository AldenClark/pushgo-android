import importlib.util
import unittest
from pathlib import Path

from scripts.tests.quality_issue_fixture import synthetic_issue_registry


REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "classify_android_instrument_log", REPO / "scripts/classify_android_instrument_log.py"
)
assert SPEC and SPEC.loader
CLASSIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CLASSIFIER)


class AndroidInstrumentLogClassificationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.registry = synthetic_issue_registry()

    def test_exact_snapshot_runtime_failure_is_flaky_test_system(self):
        status, ids = CLASSIFIER.classify(
            "java.lang.IllegalStateException: Detected multithreaded access to SnapshotStateObserver",
            self.registry,
        )

        self.assertEqual("TEST_SYSTEM_FLAKY", status)
        self.assertEqual(["android-compose-snapshot-observer-runtime"], ids)

    def test_product_assertion_wins_over_snapshot_signature(self):
        status, ids = CLASSIFIER.classify(
            "java.lang.IllegalStateException: Detected multithreaded access to SnapshotStateObserver\n"
            "java.lang.AssertionError: Expected accurate channel row to exist\n",
            self.registry,
        )

        self.assertEqual("PRODUCT_FAILED", status)
        self.assertEqual([], ids)

    def test_exact_quality_precondition_is_blocked(self):
        status, ids = CLASSIFIER.classify(
            "java.lang.AssertionError: QUALITY_PRECONDITION: fixture was not ready",
            self.registry,
        )

        self.assertEqual("TEST_SYSTEM_BLOCKED", status)
        self.assertEqual(["android-quality-precondition"], ids)

    def test_unknown_failure_is_product_failure(self):
        status, ids = CLASSIFIER.classify("Process crashed without a registered signature", self.registry)

        self.assertEqual("PRODUCT_FAILED", status)
        self.assertEqual([], ids)


if __name__ == "__main__":
    unittest.main()
