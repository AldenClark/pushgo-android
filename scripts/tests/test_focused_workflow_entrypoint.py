import os
from pathlib import Path
import re
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[2]


class FocusedWorkflowEntrypointTest(unittest.TestCase):
    def test_dispatch_selector_satisfies_the_actual_runner_precondition(self):
        workflow = (ROOT / ".github/workflows/android-quality.yml").read_text()
        binding = re.search(r"^\s+([A-Z_]+):[^\n]*inputs\.device_selector", workflow, re.MULTILINE)
        self.assertIsNotNone(binding, "Focused dispatch must forward the explicit selector")
        runner = (ROOT / "scripts/quality_test.sh").read_text()
        start = runner.index("  focused)\n") + len("  focused)\n")
        end = runner.index('    if [[ -n "${ANDROID_TEST_CLASS:-}" ]]; then', start)
        precondition = runner[start:end]
        result = subprocess.run(
            ["bash", "-c", "set -euo pipefail\n" + precondition],
            env={"PATH": os.environ["PATH"], binding.group(1): "fixture.ExampleJourney#savesData"},
            capture_output=True,
            text=True,
            timeout=5,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
