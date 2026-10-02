import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest


RUNNER = Path(os.environ.get("UPDATE_RUNNER_SOURCE", Path(__file__).resolve().parents[1] / "run_android_update_install_positive.sh"))


class PackageFreezeBoundaryTest(unittest.TestCase):
    def run_install_boundary(self, scenario):
        source = RUNNER.read_text()
        begin = source.find("package_manager_allows_launch() {")
        if begin < 0:
            begin = source.index("installer_observed=0\n")
        end = source.index('\nadb_with_timeout -s "$device_serial" shell am force-stop', begin)
        boundary = source[begin:end]
        with tempfile.TemporaryDirectory() as directory:
            setup = r'''
set -euo pipefail
device_serial=fixture
package_name=io.ethan.pushgo.benchmark
candidate_version_code=1030499
installed_baseline=1030399
echo 0 > "$run_dir/freeze-count"
adb_with_timeout() {
  if [[ "$*" == *"dumpsys package frozen" ]]; then
    local count
    count=$(cat "$run_dir/freeze-count")
    count=$((count + 1))
    echo "$count" > "$run_dir/freeze-count"
    if [[ "$scenario" == unknown ]]; then
      echo 'Service unavailable'
    elif [[ "$scenario" == deadline || "$count" -lt 3 ]]; then
      printf 'Frozen packages:\n  package=io.ethan.pushgo.benchmark, refCounts=1\n'
    elif [[ "$scenario" == other ]]; then
      printf 'Frozen packages:\n  package=io.ethan.pushgo.benchmark.other, refCounts=1\n'
    else
      printf 'Frozen packages:\n  (none)\n'
    fi
  else
    echo 'versionCode=1030499 minSdk=26'
  fi
}
dump_ui() { return 1; }
sleep() {
  if [[ "$scenario" == deadline ]]; then SECONDS=$((SECONDS + 100)); fi
}
failed() { echo "$*" >&2; exit 1; }
'''
            script = f"run_dir={shlex.quote(directory)}\nscenario={shlex.quote(scenario)}\n" + setup + boundary
            script += '\nprintf "freezeReads=%s\\n" "$(cat "$run_dir/freeze-count")"\n'
            return subprocess.run(["bash", "-c", script], capture_output=True, text=True, timeout=5)

    def test_guard_waits_for_unfreeze_before_accepting_new_version(self):
        result = self.run_install_boundary("normal")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("freezeReads=3", result.stdout)

    def test_unknown_os_snapshot_is_test_system_failure(self):
        result = self.run_install_boundary("unknown")
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertIn("FAILED_TEST_SYSTEM", result.stderr)

    def test_original_install_deadline_still_bounds_unfreeze(self):
        result = self.run_install_boundary("deadline")
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertIn("original install deadline", result.stderr)

    def test_other_frozen_package_does_not_block_target(self):
        result = self.run_install_boundary("other")
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
