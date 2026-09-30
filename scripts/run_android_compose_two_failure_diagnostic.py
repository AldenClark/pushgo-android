#!/usr/bin/env python3
"""Run selected core Compose regressions once and retain their native evidence.

This is a bounded core-flow diagnostic, not part of the 50-class flake exit campaign or
the formal product-quality lane. A valid failed assertion remains a product
failure; missing or stale native execution evidence is a test-system failure.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path
from xml.etree import ElementTree

from run_android_compose_flake_exit import (
    ROOT, RESULTS, file_digest, scan_campaign_log, selected_emulator,
    source_identity, source_is_frozen,
)


OUT = ROOT / "build/quality-results/android-compose-two-failure-diagnostic"
SELECTORS = (
    "io.ethan.pushgo.testing.QualityMessageJourneyInstrumentedTest"
    "#standardFixtureShowsAccurateContentAndSurvivesActivityRelaunch",
    "io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest"
    "#serverConfigurationRejectsInvalidInputAndScopesDataAfterRelaunch",
    "io.ethan.pushgo.testing.QualitySettingsJourneyInstrumentedTest"
    "#savingGatewayKeepsEditorOpenUntilPreparedSwitchCommitsAndPersists",
    "io.ethan.pushgo.ui.accessibility.SharedAccessibilitySemanticsTest"
    "#modalBottomSheet_exposesPaneTitle",
)


def write_json(path: Path, value: dict[str, object]) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def classify_observed_result(failures: int, test_system_defect: bool) -> dict[str, object]:
    if test_system_defect:
        return {
            "status": "FAILED_TEST_SYSTEM",
            "test_system_status": "FAILED",
            "product_status": "FAILED" if failures else "NOT_RUN",
            "mixed_evidence": failures > 0,
        }
    if failures:
        return {
            "status": "FAILED_PRODUCT_ORACLE",
            "test_system_status": "PASSED",
            "product_status": "FAILED",
            "mixed_evidence": False,
        }
    return {
        "status": "PASSED",
        "test_system_status": "PASSED",
        "product_status": "PASSED",
        "mixed_evidence": False,
    }


def native_cases(started_epoch: float) -> tuple[dict[str, object], set[str]]:
    raw = OUT / "raw"
    paths = sorted(raw.rglob("TEST-*.xml")) if raw.is_dir() else []
    if len(paths) != 1 or paths[0].stat().st_mtime < started_epoch:
        raise ValueError("expected exactly one fresh native XML report")
    try:
        root = ElementTree.parse(paths[0]).getroot()
        suites = root.findall("testsuite") if root.tag == "testsuites" else [root]
        if not suites:
            raise ValueError("native XML has no suites")
        cases: dict[str, object] = {}
        for suite in suites:
            for case in suite.findall("testcase"):
                selector = f"{case.get('classname')}#{case.get('name')}"
                if selector in cases:
                    raise ValueError(f"duplicate native test: {selector}")
                cases[selector] = {
                    "failures": len(case.findall("failure")),
                    "errors": len(case.findall("error")),
                    "skipped": len(case.findall("skipped")),
                }
        observed = set(cases)
        if observed != set(SELECTORS):
            raise ValueError(f"native selectors differ: {sorted(observed)}")
        declared = sum(int(suite.get("tests", "0")) for suite in suites)
        if declared != len(SELECTORS):
            raise ValueError(f"native declared test count differs: {declared}")
        return cases, observed
    except ElementTree.ParseError as error:
        raise ValueError("native XML could not be parsed") from error


def mark_logcat(serial: str, marker: str, path: Path, process: subprocess.Popen[bytes]) -> None:
    if process.poll() is not None:
        raise ValueError("device logcat stream exited early")
    subprocess.run(
        ["adb", "-s", serial, "shell", "log", "-t", "PushGoComposeTwoFailure", marker],
        check=True, timeout=30,
    )
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise ValueError("device logcat stream exited before marker")
        if marker.encode() in path.read_bytes():
            return
        time.sleep(0.1)
    raise ValueError("device logcat marker was not captured")


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=False)
    summary: dict[str, object] = {
        "diagnostic_only": True,
        "run_count": 1,
        "allowed_retries": 0,
        "selectors": SELECTORS,
        "scope_claim": "Only the two named Android Compose journeys on one API 35 emulator",
        "quality_gate_status": "NOT_RUN",
        "status": "FAILED_TEST_SYSTEM",
        "test_system_status": "FAILED",
        "product_status": "NOT_RUN",
        "native_oracle_status": "NOT_RUN",
        "mixed_evidence": False,
        "github_run_id": os.environ.get("GITHUB_RUN_ID"),
        "github_run_attempt": os.environ.get("GITHUB_RUN_ATTEMPT"),
    }
    gradle_started = False
    try:
        if os.environ.get("GITHUB_RUN_ATTEMPT", "1") != "1":
            raise ValueError("GitHub rerun cannot replace the first diagnostic result")
        identity = source_identity()
        if not source_is_frozen(identity):
            raise ValueError("source is not clean and frozen")
        device = selected_emulator()
        summary.update({"source_identity": identity, "device": device})
        if RESULTS.is_dir():
            shutil.rmtree(RESULTS)

        logcat_path = OUT / "device-logcat.txt"
        subprocess.run(["adb", "-s", device["serial"], "logcat", "-c"], check=True, timeout=30)
        with logcat_path.open("wb") as logcat:
            process = subprocess.Popen(
                ["adb", "-s", device["serial"], "logcat", "-v", "threadtime"],
                stdout=logcat, stderr=subprocess.STDOUT,
            )
            try:
                mark_logcat(device["serial"], "PUSHGO_TWO_FAILURE_START", logcat_path, process)
                started = time.time()
                command = [
                    str(ROOT / "gradlew"), ":app:connectedDebugAndroidTest",
                    "-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(SELECTORS),
                    "--max-workers=2", "--console=plain",
                ]
                with (OUT / "gradle.log").open("w", encoding="utf-8") as gradle_log:
                    try:
                        gradle_started = True
                        gradle_exit = subprocess.run(
                            command, cwd=ROOT, stdout=gradle_log,
                            stderr=subprocess.STDOUT, timeout=900,
                        ).returncode
                    except subprocess.TimeoutExpired:
                        gradle_log.write("\nDIAGNOSTIC: Gradle timed out after 900 seconds\n")
                        gradle_exit = 124
                mark_logcat(device["serial"], "PUSHGO_TWO_FAILURE_END", logcat_path, process)
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=10)

        summary.update({"gradle_exit_code": gradle_exit, "started_epoch": started})
        if RESULTS.is_dir():
            shutil.copytree(RESULTS, OUT / "raw")
        signature_matches, app_crash = scan_campaign_log(logcat_path)
        summary["registered_signature_matches_in_full_logcat"] = signature_matches
        summary["app_crash_in_full_logcat"] = app_crash
        cases, _ = native_cases(started)
        summary["native_cases"] = cases
        failures = sum(case["failures"] for case in cases.values())
        incomplete = any(case["skipped"] or case["errors"] for case in cases.values())
        summary["native_oracle_failures"] = failures
        summary["native_oracle_status"] = "INCOMPLETE" if incomplete else "FAILED" if failures else "PASSED"
        if failures:
            summary["product_status"] = "FAILED"
        summary["source_frozen_after"] = source_is_frozen(identity)
        summary["device_same_after"] = selected_emulator() == device
        gradle_text = (OUT / "gradle.log").read_text(encoding="utf-8", errors="replace")
        if not summary["source_frozen_after"] or not summary["device_same_after"]:
            raise ValueError("source or emulator changed during diagnostic")
        if gradle_exit == 124 or "INSTRUMENTATION_FAILED" in gradle_text or "Failed to retrieve logcat" in gradle_text:
            raise ValueError("instrumentation or native logcat was incomplete")
        if incomplete:
            raise ValueError("native test skipped or errored")
        if signature_matches or app_crash:
            summary.update(classify_observed_result(failures, test_system_defect=True))
            raise ValueError("registered Compose signature or app crash in continuous device logcat")
        if failures and gradle_exit not in (0, 1):
            summary.update(classify_observed_result(failures, test_system_defect=True))
            raise ValueError("unexpected Gradle exit despite native assertion evidence")
        if not failures and gradle_exit != 0:
            summary.update(classify_observed_result(failures, test_system_defect=True))
            raise ValueError("Gradle failed despite passing native testcases")
        summary.update(classify_observed_result(failures, test_system_defect=False))
        return 1 if failures else 0
    except (OSError, ValueError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as error:
        summary["reason"] = str(error)
        if summary["native_oracle_status"] == "FAILED":
            summary["mixed_evidence"] = True
        print(f"FAILED_TEST_SYSTEM: {error}", file=sys.stderr, flush=True)
        return 2
    finally:
        # An END-marker or logcat failure must not discard XML produced by a
        # Gradle invocation that already ran. It remains test-system failure.
        if gradle_started and RESULTS.is_dir() and not (OUT / "raw").exists():
            try:
                shutil.copytree(RESULTS, OUT / "raw")
            except OSError as error:
                summary["native_result_copy_error"] = str(error)
        summary["finished_epoch"] = time.time()
        summary["artifact_sha256"] = {
            str(path.relative_to(OUT)): file_digest(path)
            for path in OUT.rglob("*") if path.is_file() and path.name != "summary.json"
        }
        write_json(OUT / "summary.json", summary)


if __name__ == "__main__":
    raise SystemExit(main())
