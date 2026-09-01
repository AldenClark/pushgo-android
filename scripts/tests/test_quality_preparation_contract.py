from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class QualityPreparationContractTest(unittest.TestCase):
    def test_provider_exposes_stable_phases_without_retrying(self):
        provider = (
            ROOT
            / "app/src/benchmark/java/io/ethan/pushgo/testing/BenchmarkFixtureProvider.kt"
        ).read_text(encoding="utf-8")
        for phase in (
            "session.decode",
            "fixture.allowlist",
            "storage.reset",
            "session.persist",
            "storage.open",
            "fixture.seed",
            "fixture.verify",
        ):
            self.assertIn(f'preparationPhase("{phase}")', provider)
        self.assertIn("QUALITY_PRECONDITION phase=$phase", provider)
        self.assertIn("rollbackFailedPreparation(app, session, error)", provider)
        self.assertIn("QualityRuntime.persistAppOwnedSession(app, null)", provider)

    def test_host_contract_has_a_10_second_negative_control_and_positive_oracle(self):
        runner = (ROOT / "scripts/run_android_preparation_contract.sh").read_text(
            encoding="utf-8"
        )
        self.assertIn("invalid_elapsed_ms >= 10000", runner)
        self.assertIn("QUALITY_PRECONDITION phase=session.decode", runner)
        self.assertIn("QUALITY_PRECONDITION phase=storage.open", runner)
        self.assertIn("rg -q 'status=ready'", runner)
        self.assertIn('resource-id="quality-runtime.ready"', runner)
        self.assertIn('resource-id="state.messages.empty"', runner)
        self.assertNotIn("retry", runner.split("invalid_payload=", 1)[1].split("valid_payload=", 1)[0])


if __name__ == "__main__":
    unittest.main()
