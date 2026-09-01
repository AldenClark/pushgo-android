import os
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]


class QualityDoctorInteractiveTests(unittest.TestCase):
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
