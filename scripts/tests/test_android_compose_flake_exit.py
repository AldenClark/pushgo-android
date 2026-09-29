import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree

from scripts.run_android_compose_flake_exit import (
    EXPECTED_CLASS_TESTS,
    LogcatRecorder,
    SIGNATURE,
    native_result,
    scan_campaign_log,
)


class AndroidComposeFlakeExitTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.round_dir = Path(self.temporary.name)
        (self.round_dir / "raw").mkdir()
        (self.round_dir / "gradle.log").write_text("BUILD SUCCESSFUL\n")
        (self.round_dir / "device-logcat.txt").write_text("controlled emulator log\n")
        self.xml_path = self.round_dir / "raw/TEST-controlled.xml"
        self.write_xml()
        self.started = time.time() - 1

    def write_xml(self, *, skipped=0, rename_class=None, duplicate_case=False):
        root = ElementTree.Element("testsuites")
        for classname, count in EXPECTED_CLASS_TESTS.items():
            name = rename_class if rename_class and classname.endswith("SettingsJourneyInstrumentedTest") else classname
            suite = ElementTree.SubElement(
                root,
                "testsuite",
                name=name,
                tests=str(count),
                failures="0",
                errors="0",
                skipped=str(skipped if classname.endswith("SettingsJourneyInstrumentedTest") else 0),
            )
            for index in range(count):
                case_name = "same" if duplicate_case else f"method{index}"
                ElementTree.SubElement(suite, "testcase", classname=name, name=case_name)
        ElementTree.ElementTree(root).write(self.xml_path, encoding="unicode")

    def test_exact_four_classes_and_34_native_cases_pass(self):
        result = native_result(self.round_dir, self.started, 0, True)
        self.assertTrue(result["passed"])
        self.assertEqual(34, result["native_counts"]["tests"])
        self.assertEqual(4, len(result["executed_class_tests"]))

    def test_registered_signature_in_native_log_fails_even_if_xml_is_green(self):
        (self.round_dir / "device-logcat.txt").write_text(SIGNATURE)
        result = native_result(self.round_dir, self.started, 0, True)
        self.assertFalse(result["passed"])
        self.assertGreater(result["signature_matches_in_evidence"], 0)

    def test_skip_or_unknown_class_cannot_count_toward_50(self):
        self.write_xml(skipped=1)
        self.assertFalse(native_result(self.round_dir, self.started, 0, True)["passed"])
        self.write_xml(rename_class="unexpected.SettingsJourney")
        self.assertFalse(native_result(self.round_dir, self.started, 0, True)["passed"])

    def test_duplicate_or_stale_native_cases_fail(self):
        self.write_xml(duplicate_case=True)
        self.assertFalse(native_result(self.round_dir, self.started, 0, True)["passed"])
        self.write_xml()
        self.assertFalse(native_result(self.round_dir, time.time() + 30, 0, True)["passed"])

    def test_suite_counts_cannot_hide_missing_or_misassigned_cases(self):
        root = ElementTree.parse(self.xml_path).getroot()
        suites = root.findall("testsuite")
        suites[0].remove(suites[0].findall("testcase")[0])
        ElementTree.ElementTree(root).write(self.xml_path, encoding="unicode")
        self.assertFalse(native_result(self.round_dir, self.started, 0, True)["passed"])
        self.write_xml()
        root = ElementTree.parse(self.xml_path).getroot()
        root.find("testsuite/testcase").set("classname", "unexpected.Class")
        ElementTree.ElementTree(root).write(self.xml_path, encoding="unicode")
        self.assertFalse(native_result(self.round_dir, self.started, 0, True)["passed"])

    def test_suite_zero_failure_count_cannot_hide_failed_case(self):
        root = ElementTree.parse(self.xml_path).getroot()
        ElementTree.SubElement(root.find("testsuite/testcase"), "failure", message="hidden failure")
        ElementTree.ElementTree(root).write(self.xml_path, encoding="unicode")
        self.assertFalse(native_result(self.round_dir, self.started, 0, True)["passed"])

    def test_crash_or_missing_logcat_fails_even_if_gradle_is_green(self):
        (self.round_dir / "device-logcat.txt").write_text(
            "FATAL EXCEPTION: main\nProcess: io.ethan.pushgo, PID: 42\n"
        )
        self.assertFalse(native_result(self.round_dir, self.started, 0, True)["passed"])
        (self.round_dir / "device-logcat.txt").write_text(
            "FATAL EXCEPTION: main\nProcess: io.ethan.pushgo.test, PID: 43\n"
        )
        self.assertFalse(native_result(self.round_dir, self.started, 0, True)["passed"])
        (self.round_dir / "device-logcat.txt").unlink()
        self.assertFalse(native_result(self.round_dir, self.started, 0, True)["passed"])

    def test_incomplete_live_logcat_stream_cannot_count(self):
        self.assertFalse(native_result(self.round_dir, self.started, 0, False)["passed"])

    def test_full_campaign_scan_catches_between_round_failure(self):
        log = self.round_dir / "campaign-logcat.txt"
        log.write_text("ROUND_END\n" + SIGNATURE + "\nROUND_START\n")
        self.assertEqual((1, False), scan_campaign_log(log))
        log.write_text("ROUND_END\nFATAL EXCEPTION: main\nProcess: io.ethan.pushgo, PID: 42\n")
        self.assertEqual((0, True), scan_campaign_log(log))

    def test_live_logcat_markers_preserve_the_inter_round_gap(self):
        recorder = object.__new__(LogcatRecorder)
        recorder.serial = "emulator-5554"
        recorder.path = self.round_dir / "campaign-logcat.txt"
        recorder.path.write_bytes(b"")
        recorder.cursor = 0
        recorder.process = type("LiveProcess", (), {"poll": lambda self: None})()

        def emit_marker(command, **_kwargs):
            with recorder.path.open("ab") as stream:
                stream.write(f"PushGoComposeQA: {command[-1]}\n".encode())

        with patch("scripts.run_android_compose_flake_exit.subprocess.run", side_effect=emit_marker):
            recorder.marker("ROUND_01_START")
            with recorder.path.open("ab") as stream:
                stream.write(b"test output\n")
            first_end = recorder.marker("ROUND_01_END")
            recorder.save_through(first_end, self.round_dir / "round-one-logcat.txt")
            with recorder.path.open("ab") as stream:
                stream.write(b"between-round exception\n")
            recorder.marker("ROUND_02_START")
            second_end = recorder.marker("ROUND_02_END")
            recorder.save_through(second_end, self.round_dir / "round-two-logcat.txt")

        self.assertIn("test output", (self.round_dir / "round-one-logcat.txt").read_text())
        self.assertIn("between-round exception", (self.round_dir / "round-two-logcat.txt").read_text())


if __name__ == "__main__":
    unittest.main()
