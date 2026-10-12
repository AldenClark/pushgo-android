from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest

REPO = Path(__file__).resolve().parents[2]


class PermissionNativeClockTests(unittest.TestCase):
    def source(self) -> str:
        path = Path(os.environ.get("PERMISSION_RUNNER_SOURCE", REPO / "scripts/run_android_notification_permission_positive.sh"))
        return path.read_text()

    def run_helper(self, *, native: bool, guard: str = "45", verify: bool = False):
        source = self.source()
        first = "adb_command_with_timeout() {" if "adb_command_with_timeout() {" in source else "adb_with_timeout() {"
        helpers = first + source.split(first, 1)[1].split("\nblocked() {", 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            stub = Path(directory) / "adb"
            stub.write_text("""#!/bin/bash
printf 'INSTRUMENTATION_STATUS: class=example.Permission\nINSTRUMENTATION_STATUS: test=enabled\nINSTRUMENTATION_STATUS_CODE: 1\n'
sleep 1.25
printf 'NATIVE_COMPLETE\nOK (1 test)\n'
""")
            stub.chmod(0o755)
            command = "adb_native_with_timeout" if native and "adb_native_with_timeout() {" in source else "adb_with_timeout"
            body = f"{command} shell am instrument -w"
            if verify:
                start = "instrumentation_status=0" if "instrumentation_status=0" in source else 'instrumentation_output="$(adb_with_timeout'
                body = start + source.split(start, 1)[1].split('\necho "status=PASSED"', 1)[0]
            script = """adb_timeout_seconds=1
permission_instrumentation_timeout_seconds="$NATIVE_GUARD"
adb_binary="$ADB_STUB"
device_serial=emulator-test
test_class=example.Permission
test_method=enabled
test_selector=example.Permission#enabled
test_runner=example.runner
repo_root="$REPO_ROOT"
run_dir="$RUN_DIR"
test_system_failed() { echo status=FAILED_TEST_SYSTEM; exit 3; }
failed() { echo status=FAILED; exit 1; }
""" + helpers + "\n" + body
            return subprocess.run(
                ["/bin/bash", "-c", script], text=True, capture_output=True, timeout=5,
                env={**os.environ, "ADB_STUB": str(stub), "NATIVE_GUARD": guard,
                     "REPO_ROOT": str(REPO), "RUN_DIR": directory},
            )

    def test_native_completion_is_not_cut_off_by_the_ordinary_adb_clock(self):
        result = self.run_helper(native=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("NATIVE_COMPLETE", result.stdout)

    def test_ordinary_adb_commands_still_timeout(self):
        result = self.run_helper(native=False)
        self.assertEqual(124, result.returncode)
        self.assertNotIn("NATIVE_COMPLETE", result.stdout)

    def test_interrupted_native_start_is_a_test_system_failure(self):
        result = self.run_helper(native=True, guard="1", verify=True)
        self.assertEqual(3, result.returncode, result.stdout + result.stderr)
        self.assertIn("status=FAILED_TEST_SYSTEM", result.stdout)
        self.assertNotIn("status=FAILED\n", result.stdout)
