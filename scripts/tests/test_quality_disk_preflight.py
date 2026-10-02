import subprocess
import tempfile
import unittest
from pathlib import Path

from scripts.tests.quality_issue_fixture import isolated_quality_scripts


REPO = Path(__file__).resolve().parents[2]


class QualityDiskPreflightTests(unittest.TestCase):
    def test_writable_path_with_available_capacity_is_ready(self):
        with tempfile.TemporaryDirectory() as directory:
            process = subprocess.run(
                [
                    "python3",
                    str(REPO / "scripts/quality_disk_preflight.py"),
                    "--path",
                    directory,
                    "--minimum-free-bytes",
                    "0",
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )

        self.assertEqual(0, process.returncode)
        self.assertIn("disk_preflight=READY", process.stdout)

    def test_impossible_capacity_blocks_before_test_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            process = subprocess.run(
                [
                    "python3",
                    str(REPO / "scripts/quality_disk_preflight.py"),
                    "--path",
                    directory,
                    "--minimum-free-bytes",
                    str(2**63 - 1),
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )

        self.assertEqual(2, process.returncode)
        self.assertIn("reason=insufficient_free_disk:", process.stdout)

    def test_lane_preflight_block_still_writes_a_trustworthy_receipt(self):
        canonical_receipt = REPO / "build/quality-results/android-pr-ui-summary.json"
        canonical_before = canonical_receipt.read_bytes() if canonical_receipt.exists() else None
        with tempfile.TemporaryDirectory() as directory:
            isolated_root = isolated_quality_scripts(
                Path(directory) / "isolated",
                "quality_test.sh",
                "quality_test_system_issues.py",
                "quality_disk_preflight.py",
                "quality_result.py",
            )
            isolated_results = Path(directory) / "quality-results"
            process = subprocess.run(
                [str(isolated_root / "scripts/quality_test.sh"), "pr-ui"],
                cwd=isolated_root,
                env={
                    "PATH": "/usr/bin:/bin:/usr/sbin:/sbin",
                    "QUALITY_MIN_FREE_BYTES": str(2**63 - 1),
                    "QUALITY_RESULTS_ROOT": str(isolated_results),
                },
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            isolated_receipt = isolated_results / "android-pr-ui-summary.json"
            self.assertTrue(isolated_receipt.is_file())
            receipt = isolated_receipt.read_text()

        self.assertEqual(2, process.returncode)
        self.assertIn("reason=insufficient_free_disk:", process.stdout)
        self.assertIn("quality_result=", process.stdout)
        self.assertIn('"product_capability_status": "NOT_RUN"', receipt)
        self.assertIn('"test_system_status": "BLOCKED"', receipt)
        canonical_after = canonical_receipt.read_bytes() if canonical_receipt.exists() else None
        self.assertEqual(
            canonical_before,
            canonical_after,
            "A host negative control must never overwrite retained device evidence.",
        )


if __name__ == "__main__":
    unittest.main()
