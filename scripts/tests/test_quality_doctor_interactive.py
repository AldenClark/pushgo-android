import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]


class QualityDoctorInteractiveTests(unittest.TestCase):
    def test_host_allow_no_device_does_not_query_an_unselected_physical_phone(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            calls = root / "calls"
            adb = root / "adb"
            adb.write_text(
                "#!/bin/sh\n"
                "printf '%s\\n' \"$*\" >> \"$FAKE_ADB_CALLS\"\n"
                "if [ \"$1\" = devices ]; then\n"
                "  printf 'List of devices attached\\nphysical-phone device product:personal model:Phone\\n'\n"
                "  exit 0\n"
                "fi\n"
                "exit 64\n"
            )
            adb.chmod(adb.stat().st_mode | stat.S_IXUSR)
            process = subprocess.run(
                [str(REPO / "scripts/quality_doctor.sh"), "--allow-no-device"],
                cwd=REPO,
                env={
                    "PATH": f"{root}:/usr/bin:/bin:/usr/sbin:/sbin",
                    "FAKE_ADB_CALLS": str(calls),
                },
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            self.assertEqual(0, process.returncode, process.stdout)
            self.assertIn("device=NOT_RUN", process.stdout)
            self.assertEqual(["devices -l"], calls.read_text().splitlines())

    def test_doctor_bounds_all_adb_queries(self):
        doctor = (REPO / "scripts/quality_doctor.sh").read_text(encoding="utf-8")

        self.assertIn("adb_with_timeout()", doctor)
        self.assertIn('adb_timeout_seconds="${QUALITY_ADB_TIMEOUT_SECONDS:-8}"', doctor)
        self.assertIn("QUALITY_ADB_TIMEOUT_SECONDS must be a positive integer", doctor)
        self.assertIn("adb_devices_query_failed_or_timed_out", doctor)
        self.assertIn('<<< "$power_dump"', doctor)
        self.assertIn('<<< "$window_policy_dump"', doctor)
        self.assertNotIn("adb -s ", doctor)

    def run_doctor(self, *, qemu: bool) -> tuple[subprocess.CompletedProcess[str], str]:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            state = root / "awake"
            adb = root / "adb"
            adb.write_text(
                """#!/bin/sh
set -eu
state=$FAKE_ADB_STATE
if [ \"$1\" = devices ]; then
  printf 'List of devices attached\\nquality-device device product:quality model:Quality\\n'
  exit 0
fi
[ \"$1\" = -s ]
shift 2
[ \"$1\" = shell ]
shift
case \"$*\" in
  'getprop ro.build.version.sdk') printf '37\\n' ;;
  'getprop ro.kernel.qemu') printf '%s\\n' \"$FAKE_ADB_QEMU\" ;;
  'dumpsys power')
    if [ -f \"$state\" ]; then printf '  mWakefulness=Awake\\n'; else printf '  mWakefulness=Asleep\\n'; fi
    ;;
  'input keyevent KEYCODE_WAKEUP') : > \"$state\" ;;
  'wm dismiss-keyguard') : ;;
  'dumpsys window policy') printf '      showing=false\\n' ;;
  *) printf 'unexpected fake adb call: %s\\n' \"$*\" >&2; exit 64 ;;
esac
"""
            )
            adb.chmod(adb.stat().st_mode | stat.S_IXUSR)
            env = {
                "PATH": f"{root}:/usr/bin:/bin:/usr/sbin:/sbin",
                "ANDROID_SERIAL": "quality-device",
                "FAKE_ADB_STATE": str(state),
                "FAKE_ADB_QEMU": "1" if qemu else "0",
            }
            process = subprocess.run(
                [str(REPO / "scripts/quality_doctor.sh")],
                cwd=REPO,
                env=env,
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                check=False,
            )
            return process, process.stdout

    def test_controlled_emulator_is_woken_before_ready(self):
        process, output = self.run_doctor(qemu=True)

        self.assertEqual(0, process.returncode, output)
        self.assertIn("status=READY", output)
        self.assertIn("device_interactive=awake_unlocked", output)

    def test_sleeping_physical_device_is_blocked_without_unlock_attempt(self):
        process, output = self.run_doctor(qemu=False)

        self.assertEqual(2, process.returncode, output)
        self.assertIn(
            "reason=physical_android_device_must_be_awake_and_unlocked:quality-device",
            output,
        )
        self.assertNotIn("device_interactive=awake_unlocked", output)


if __name__ == "__main__":
    unittest.main()
