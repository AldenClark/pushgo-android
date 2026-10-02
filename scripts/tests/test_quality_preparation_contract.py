from pathlib import Path
import json
import os
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class QualityPreparationContractTest(unittest.TestCase):
    def test_host_impact_contract_defers_device_preparation_without_claiming_it(self):
        runner = (ROOT / "scripts/quality_test.sh").read_text()
        start = runner.index("run_impact_contracts() {")
        end = runner.index("\n}\n\nrun_impact_contracts", start) + 2
        contract_function = runner[start:end]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            helper = root / "scripts/run_android_preparation_contract.sh"
            helper.write_text('#!/bin/bash\nprintf invoked > "$PREPARATION_MARKER"\n')
            helper.chmod(0o755)
            plan = root / "plan.json"
            plan.write_text(json.dumps({"required_checks": ["android-preparation-contract"]}))
            marker_path = root / "preparation-invoked.txt"
            for lane in ("pr", "planned-device"):
                with self.subTest(lane=lane):
                    marker_path.unlink(missing_ok=True)
                    command = 'selected_claims=(); claims=(); not_run=();\n' + contract_function
                    command += '\nrun_impact_contracts\nprintf "%s %s" "${#selected_claims[@]}" "${#claims[@]}"'
                    process = subprocess.run(
                        ["/bin/bash", "-euo", "pipefail", "-c", command],
                        env={**os.environ, "repo_root": str(root), "lane": lane,
                             "QUALITY_IMPACT_PLAN": str(plan), "PREPARATION_MARKER": str(marker_path)},
                        capture_output=True, text=True, check=True,
                    )
                    self.assertEqual(lane != "pr", marker_path.exists())
                    self.assertEqual("0 0" if lane == "pr" else "1 1", process.stdout)

    def test_negative_controls_preserve_exclusive_classification_without_ripgrep(self):
        runner = (ROOT / "scripts/run_android_preparation_contract.sh").read_text()
        for variable, phase in (("invalid", "session.decode"), ("storage_failure", "storage.open")):
            start = runner.index("if ! ", runner.index(f"{variable}_elapsed_ms="))
            end = runner.index(f"if (( {variable}_elapsed_ms", start)
            matcher = runner[start:end]
            with tempfile.TemporaryDirectory() as directory:
                log = Path(directory) / "preparation.log"
                for content, expected_exit in (
                    (f"QUALITY_PRECONDITION phase={phase}\n", 0),
                    (f"QUALITY_PRECONDITION phase={phase}\nstatus=ready\n", 3),
                    ("QUALITY_PRECONDITION phase=wrong.owner\n", 3),
                    ("", 3),
                ):
                    with self.subTest(phase=phase, content=content):
                        log.write_text(content)
                        process = subprocess.run(
                            ["/bin/bash", "-euo", "pipefail", "-c", matcher],
                            env={**os.environ, "PATH": "/usr/bin:/bin", f"{variable}_log": str(log)},
                            capture_output=True,
                            text=True,
                        )
                        self.assertEqual(expected_exit, process.returncode, process.stdout + process.stderr)
                        self.assertNotIn("command not found", process.stderr)

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
        self.assertIn("grep -F -q 'status=ready'", runner)
        self.assertIn('resource-id="quality-runtime.ready"', runner)
        self.assertIn('resource-id="state.messages.empty"', runner)
        self.assertNotIn("retry", runner.split("invalid_payload=", 1)[1].split("valid_payload=", 1)[0])
        self.assertIn("adb_with_timeout()", runner)
        self.assertIn('adb_timeout_seconds="${QUALITY_ADB_TIMEOUT_SECONDS:-15}"', runner)
        self.assertIn("QUALITY_ADB_TIMEOUT_SECONDS must be a positive integer", runner)
        self.assertIn("acquire_device_lock()", runner)
        self.assertIn("release_device_lock()", runner)
        self.assertIn("QUALITY_ANDROID_DEVICE_LOCK_TIMEOUT_SECONDS", runner)
        self.assertIn("selected Android device is busy", runner)
        self.assertNotIn("adb -s ", runner)


if __name__ == "__main__":
    unittest.main()
