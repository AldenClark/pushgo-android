#!/usr/bin/env python3
"""Collect the registered Compose flake's 50-class exit evidence on one emulator.

This diagnostic is intentionally separate from product-quality classification. Any
failed, skipped, missing, stale, or unexpected native result stops the campaign;
there is no retry path or option to reduce the required scope.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from collections import deque
from pathlib import Path
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build/quality-results/android-compose-flake-exit"
RESULTS = ROOT / "app/build/outputs/androidTest-results/connected/debug"
ROUND_COUNT = 13
EXPECTED_CLASS_TESTS = {
    "io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest": 15,
    "io.ethan.pushgo.testing.QualityEntityJourneyInstrumentedTest": 4,
    "io.ethan.pushgo.testing.QualityChannelJourneyInstrumentedTest": 4,
    "io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest": 11,
}
EXPECTED_TESTS = sum(EXPECTED_CLASS_TESTS.values())
SIGNATURE = "Detected multithreaded access to SnapshotStateObserver"
PRODUCTION_PATHS = (
    "app/src/main",
    "app/src/androidTest",
    "app/build.gradle.kts",
    "native/quinn-jni",
    "gradle.properties",
)
APP_CRASH = re.compile(r"FATAL EXCEPTION:[^\n]*\n(?:[^\n]*\n){0,3}[^\n]*Process: io\.ethan\.pushgo(?:\.test)?(?:,|:|\s)")
NATIVE_FAILURE_MARKERS = ("INSTRUMENTATION_FAILED", "Process crashed", "FAILURES!!!")


def output(*command: str) -> str:
    return subprocess.check_output(command, cwd=ROOT, text=True, timeout=30).strip()


def source_identity() -> dict[str, object]:
    paths = {path: output("git", "rev-parse", f"HEAD:{path}") for path in PRODUCTION_PATHS}
    digest_input = json.dumps(paths, sort_keys=True, separators=(",", ":")).encode()
    return {
        "commit": output("git", "rev-parse", "HEAD"),
        "source_tree": output("git", "rev-parse", "HEAD^{tree}"),
        "production_paths": paths,
        "production_sha256": hashlib.sha256(digest_input).hexdigest(),
    }


def source_is_frozen(identity: dict[str, object]) -> bool:
    return not output("git", "status", "--porcelain") and source_identity() == identity


def selected_emulator() -> dict[str, str]:
    devices = output("adb", "devices").splitlines()[1:]
    serials = [line.split()[0] for line in devices if len(line.split()) >= 2 and line.split()[1] == "device"]
    if len(serials) != 1 or not serials[0].startswith("emulator-"):
        raise ValueError(f"expected one controlled emulator, found {serials}")
    serial = serials[0]
    qemu = output("adb", "-s", serial, "shell", "getprop", "ro.kernel.qemu")
    api = output("adb", "-s", serial, "shell", "getprop", "ro.build.version.sdk")
    abi = output("adb", "-s", serial, "shell", "getprop", "ro.product.cpu.abi")
    if (qemu, api, abi) != ("1", "35", "x86_64"):
        raise ValueError(f"unexpected diagnostic emulator: qemu={qemu} api={api} abi={abi}")
    return {"serial": serial, "api_level": api, "abi": abi}


class LogcatRecorder:
    """Keep one live log stream across all rounds, including the gaps between them."""

    def __init__(self, serial: str):
        self.serial = serial
        self.path = OUT / "campaign-logcat.txt"
        self.cursor = 0
        subprocess.run(["adb", "-s", serial, "logcat", "-c"], check=True, timeout=30)
        self.file = self.path.open("wb")
        try:
            self.process = subprocess.Popen(
                ["adb", "-s", serial, "logcat", "-v", "threadtime"],
                stdout=self.file, stderr=subprocess.STDOUT,
            )
        except OSError:
            self.file.close()
            raise
        try:
            self.marker("CAMPAIGN_START")
        except (OSError, ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired):
            self.close()
            raise

    def marker(self, label: str) -> int:
        if self.process.poll() is not None:
            raise ValueError(f"logcat stream exited before {label}")
        token = f"PUSHGO_COMPOSE_{label}_{time.time_ns()}"
        subprocess.run(
            ["adb", "-s", self.serial, "shell", "log", "-t", "PushGoComposeQA", token],
            check=True, timeout=30,
        )
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if self.process.poll() is not None:
                raise ValueError(f"logcat stream exited while awaiting {label}")
            with self.path.open("rb") as evidence:
                evidence.seek(self.cursor)
                content = evidence.read()
            position = content.find(token.encode())
            if position >= 0:
                line_end = content.find(b"\n", position)
                if line_end >= 0:
                    return self.cursor + line_end + 1
            time.sleep(0.1)
        raise ValueError(f"logcat stream did not record {label} marker")

    def save_through(self, end_offset: int, destination: Path) -> None:
        with self.path.open("rb") as evidence, destination.open("wb") as segment:
            evidence.seek(self.cursor)
            segment.write(evidence.read(end_offset - self.cursor))
        self.cursor = end_offset

    def close(self) -> None:
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=10)
        self.file.close()


def native_result(round_dir: Path, started_epoch: float, gradle_exit: int,
                  logcat_stream_complete: bool) -> dict[str, object]:
    raw = round_dir / "raw"
    xml_paths = sorted(raw.rglob("TEST-*.xml")) if raw.is_dir() else []
    xml_fresh = len(xml_paths) == 1 and xml_paths[0].stat().st_mtime >= started_epoch
    counts = {key: 0 for key in ("tests", "failures", "errors", "skipped")}
    class_tests: dict[str, int] = {}
    class_cases: dict[str, int] = {}
    unique_cases: set[tuple[str, str]] = set()
    case_failures = 0
    parsed = False
    if xml_fresh:
        try:
            root = ElementTree.parse(xml_paths[0]).getroot()
            suites = root.findall("testsuite") if root.tag == "testsuites" else [root]
            for suite in suites:
                name = suite.get("name", "")
                if name in class_tests:
                    raise ValueError(f"duplicate suite: {name}")
                class_tests[name] = int(suite.get("tests", "0"))
                for key in counts:
                    counts[key] += int(suite.get(key, "0"))
                cases = suite.findall("testcase")
                class_cases[name] = len(cases)
                for case in cases:
                    if case.get("classname") != name or not case.get("name"):
                        raise ValueError(f"case outside suite or unnamed: {name}")
                    case_failures += sum(len(case.findall(tag)) for tag in ("failure", "error", "skipped"))
                    unique_cases.add((case.get("classname", ""), case.get("name", "")))
            parsed = True
        except (ElementTree.ParseError, OSError, ValueError):
            parsed = False

    evidence_files = [round_dir / "gradle.log", round_dir / "device-logcat.txt"]
    if raw.is_dir():
        evidence_files.extend(raw.rglob("*.txt"))
        evidence_files.extend(xml_paths)
    evidence_text = "\n".join(
        path.read_text(encoding="utf-8", errors="replace")
        for path in evidence_files
        if path.is_file()
    )
    signature_matches = evidence_text.count(SIGNATURE)
    app_crash = APP_CRASH.search(evidence_text) is not None
    incomplete_logcat = "Failed to retrieve logcat" in evidence_text
    gradle_text = (round_dir / "gradle.log").read_text(encoding="utf-8", errors="replace")
    native_failure_marker = any(marker in gradle_text for marker in NATIVE_FAILURE_MARKERS)
    logcat_captured = (round_dir / "device-logcat.txt").is_file()
    passed = (
        gradle_exit == 0
        and parsed
        and counts == {"tests": EXPECTED_TESTS, "failures": 0, "errors": 0, "skipped": 0}
        and class_tests == EXPECTED_CLASS_TESTS
        and class_cases == EXPECTED_CLASS_TESTS
        and len(unique_cases) == EXPECTED_TESTS
        and case_failures == 0
        and logcat_captured
        and logcat_stream_complete
        and signature_matches == 0
        and not app_crash
        and not incomplete_logcat
        and not native_failure_marker
    )
    return {
        "gradle_exit_code": gradle_exit,
        "xml_fresh": xml_fresh,
        "xml_parsed": parsed,
        "native_counts": counts,
        "executed_class_tests": class_tests,
        "executed_class_cases": class_cases,
        "unique_testcases": len(unique_cases),
        "case_failures_or_skips": case_failures,
        "signature_matches_in_evidence": signature_matches,
        "app_crash_in_logs": app_crash,
        "incomplete_logcat": incomplete_logcat,
        "native_failure_marker_in_gradle": native_failure_marker,
        "device_logcat_captured": logcat_captured,
        "logcat_stream_complete": logcat_stream_complete,
        "passed": passed,
    }


def file_digest(path: Path) -> str:
    sha = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            sha.update(chunk)
    return sha.hexdigest()


def scan_campaign_log(path: Path) -> tuple[int, bool]:
    signatures = 0
    app_crash = False
    recent_lines: deque[str] = deque(maxlen=6)
    with path.open(encoding="utf-8", errors="replace") as evidence:
        for line in evidence:
            signatures += line.count(SIGNATURE)
            recent_lines.append(line)
            if APP_CRASH.search("".join(recent_lines)):
                app_crash = True
    return signatures, app_crash


def write_json(path: Path, data: dict[str, object]) -> None:
    path.write_text(json.dumps(data, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def run_round(number: int, identity: dict[str, object], device: dict[str, str],
              recorder: LogcatRecorder) -> dict[str, object]:
    free = shutil.disk_usage(ROOT).free
    if free < 3 * 1024**3:
        raise ValueError(f"disk below 3 GiB before round {number}: {free}")
    if not source_is_frozen(identity) or selected_emulator() != device:
        raise ValueError(f"source or emulator changed before round {number}")
    round_dir = OUT / f"round-{number:02d}"
    round_dir.mkdir(exist_ok=False)
    # The prior round is already copied into its own immutable receipt directory.
    # Removing only Gradle's generated current-result directory prevents stale XML
    # from standing in for a round that never reached instrumentation.
    if RESULTS.is_dir():
        shutil.rmtree(RESULTS)
    recorder.marker(f"ROUND_{number:02d}_START")
    started = time.time()
    command = [
        str(ROOT / "gradlew"),
        ":app:connectedDebugAndroidTest",
        "-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(EXPECTED_CLASS_TESTS),
        "--max-workers=2",
        "--console=plain",
    ]
    with (round_dir / "gradle.log").open("w", encoding="utf-8") as log:
        try:
            gradle_exit = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=900).returncode
        except subprocess.TimeoutExpired:
            log.write("\nDIAGNOSTIC: Gradle timed out after 900 seconds\n")
            gradle_exit = 124
    end_offset = recorder.marker(f"ROUND_{number:02d}_END")
    recorder.save_through(end_offset, round_dir / "device-logcat.txt")
    if RESULTS.is_dir():
        shutil.copytree(RESULTS, round_dir / "raw")
    result = native_result(round_dir, started, gradle_exit, logcat_stream_complete=True)
    result.update({
        "round": number,
        "started_epoch": started,
        "finished_epoch": time.time(),
        "source_identity": identity,
        "source_frozen_after": source_is_frozen(identity),
        "device": device,
        "campaign_logcat_byte_offset_end": end_offset,
        "disk_free_gib_before": round(free / 1024**3, 2),
        "artifact_sha256": {
            str(path.relative_to(round_dir)): file_digest(path)
            for path in round_dir.rglob("*") if path.is_file()
        },
    })
    result["passed"] = bool(result["passed"] and result["source_frozen_after"])
    write_json(round_dir / "receipt.json", result)
    return result


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=False)
    summary: dict[str, object] = {
        "status": "BLOCKED",
        "required_consecutive_classes": 50,
        "scheduled_rounds": ROUND_COUNT,
        "classes_per_round": len(EXPECTED_CLASS_TESTS),
        "required_native_tests_per_round": EXPECTED_TESTS,
        "allowed_retries": 0,
        "github_run_id": os.environ.get("GITHUB_RUN_ID"),
        "github_run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
        "completed_rounds": 0,
        "consecutive_passed_classes": 0,
    }
    try:
        if os.environ.get("GITHUB_RUN_ATTEMPT", "1") != "1":
            raise ValueError("GitHub rerun attempts cannot replace the first diagnostic result")
        identity = source_identity()
        if not source_is_frozen(identity):
            raise ValueError("source is not clean and frozen")
        device = selected_emulator()
        summary.update({"source_identity": identity, "device": device})
        recorder = LogcatRecorder(device["serial"])
        try:
            for number in range(1, ROUND_COUNT + 1):
                print(f"START round={number:02d} source={identity['commit']}", flush=True)
                result = run_round(number, identity, device, recorder)
                summary["completed_rounds"] = number
                print(f"END round={number:02d} passed={result['passed']} "
                      f"tests={result['native_counts']['tests']} "
                      f"signature={result['signature_matches_in_evidence']}", flush=True)
                if not result["passed"]:
                    summary.update({"status": "FAILED", "first_failed_round": number})
                    break
                summary["consecutive_passed_classes"] = number * len(EXPECTED_CLASS_TESTS)
        finally:
            recorder.close()
        campaign_signatures, campaign_crash = scan_campaign_log(recorder.path)
        summary.update({
            "campaign_logcat_sha256": file_digest(recorder.path),
            "campaign_logcat_bytes": recorder.path.stat().st_size,
            "campaign_signature_matches": campaign_signatures,
            "campaign_app_crash": campaign_crash,
        })
        if campaign_signatures or campaign_crash:
            summary.update({"status": "FAILED", "reason": "full campaign logcat contains a target signature or app crash"})
            return 1
        if summary["status"] == "FAILED":
            return 1
        summary["status"] = "PASSED"
        summary["exit_criteria_met"] = summary["consecutive_passed_classes"] >= 50
        return 0
    except (OSError, ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        summary.update({"status": "BLOCKED", "reason": str(error)})
        print(f"BLOCKED: {error}", file=sys.stderr, flush=True)
        return 2
    finally:
        campaign_log = OUT / "campaign-logcat.txt"
        if campaign_log.is_file() and "campaign_logcat_sha256" not in summary:
            summary["campaign_logcat_sha256"] = file_digest(campaign_log)
            summary["campaign_logcat_bytes"] = campaign_log.stat().st_size
            campaign_signatures, campaign_crash = scan_campaign_log(campaign_log)
            summary["campaign_signature_matches"] = campaign_signatures
            summary["campaign_app_crash"] = campaign_crash
        summary.setdefault("exit_criteria_met", False)
        write_json(OUT / "summary.json", summary)


if __name__ == "__main__":
    raise SystemExit(main())
