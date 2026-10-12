#!/usr/bin/env python3
"""Verify and inventory the four unsigned APKs from a Release-isolation build."""

import argparse
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import zipfile
from pathlib import Path


EXPECTED_ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64")
EXPECTED_FILES = {
    "universal": "app-universal-release-unsigned.apk",
    **{abi: f"app-{abi}-release-unsigned.apk" for abi in EXPECTED_ABIS},
}
JNI_LIBRARY = "libpushgo_quinn_jni.so"
PRODUCTION_PATHS = (
    "app/src/main",
    "app/src/androidTest",
    "app/build.gradle.kts",
    "native/quinn-jni",
    "gradle.properties",
)


class ContractError(Exception):
    pass


class PreconditionError(ContractError):
    """Verification has not reached an APK contract oracle."""


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify(repo: Path, apksigner: Path, not_before: float) -> dict:
    # A HEAD identifier cannot describe APKs built from local edits.
    if subprocess.check_output(
        ["git", "status", "--porcelain", "--untracked-files=normal"], cwd=repo, text=True
    ).strip():
        raise PreconditionError("Release source worktree is not clean")
    output_dir = repo / "app/build/outputs/apk/release"
    metadata_file = output_dir / "output-metadata.json"
    metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
    if metadata_file.stat().st_mtime < not_before:
        raise ContractError("Release output metadata predates this build invocation")
    if metadata.get("applicationId") != "io.ethan.pushgo" or metadata.get("variantName") != "release":
        raise ContractError("Release output metadata has the wrong application or variant")

    elements = metadata.get("elements")
    if not isinstance(elements, list) or len(elements) != 4:
        raise ContractError("Release output metadata must contain exactly four APK elements")
    by_name = {element.get("outputFile"): element for element in elements}
    if set(by_name) != set(EXPECTED_FILES.values()):
        raise ContractError("Release output metadata does not contain the exact unsigned APK matrix")
    if {path.name for path in output_dir.glob("*.apk")} != set(EXPECTED_FILES.values()):
        raise ContractError("Release output directory does not contain exactly the four expected APKs")
    if not apksigner.is_file():
        raise PreconditionError(f"Android apksigner is unavailable: {apksigner}")
    aapt2 = apksigner.with_name("aapt2")
    if not aapt2.is_file():
        raise PreconditionError(f"Android aapt2 is unavailable: {aapt2}")

    packages = {}
    for abi, name in EXPECTED_FILES.items():
        element = by_name[name]
        expected_filters = [] if abi == "universal" else [{"filterType": "ABI", "value": abi}]
        if element.get("filters") != expected_filters:
            raise ContractError(f"Wrong ABI metadata for {name}")
        if element.get("versionName") != "v1.3.3" or element.get("versionCode") != 1030399:
            raise ContractError(f"Wrong v1.3.3 version metadata for {name}")
        path = output_dir / name
        if path.stat().st_mtime < not_before:
            raise ContractError(f"APK predates this build invocation: {name}")
        badging = subprocess.check_output([str(aapt2), "dump", "badging", str(path)], text=True)
        package = re.search(
            r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
            badging, re.MULTILINE,
        )
        if package is None or package.groups() != ("io.ethan.pushgo", "1030399", "v1.3.3"):
            raise ContractError(f"Packaged manifest does not match v1.3.3 metadata: {name}")
        if re.search(r"^application-debuggable(?:\s|$)", badging, re.MULTILINE):
            raise ContractError(f"Release APK is debuggable: {name}")
        with zipfile.ZipFile(path) as archive:
            if archive.testzip() is not None:
                raise ContractError(f"Invalid ZIP member in {name}")
            jni_abis = {
                entry.split("/")[1]
                for entry in archive.namelist()
                if entry.startswith("lib/") and entry.endswith("/" + JNI_LIBRARY)
            }
            native_abis = {
                entry.split("/")[1]
                for entry in archive.namelist()
                if entry.startswith("lib/") and entry.endswith(".so")
            }
        expected_jni_abis = set(EXPECTED_ABIS) if abi == "universal" else {abi}
        if jni_abis != expected_jni_abis:
            raise ContractError(f"Wrong packaged JNI ABI set for {name}: {sorted(jni_abis)}")
        if native_abis != expected_jni_abis:
            raise ContractError(f"Native dependencies advertise unsupported ABI for {name}: {sorted(native_abis)}")
        native_code = re.search(r"^native-code:\s*(.*)$", badging, re.MULTILINE)
        advertised_abis = set(re.findall(r"'([^']+)'", native_code.group(1))) if native_code else set()
        if advertised_abis != expected_jni_abis:
            raise ContractError(f"Packaged manifest advertises wrong native ABI set for {name}: {sorted(advertised_abis)}")
        with zipfile.ZipFile(path) as archive:
            for entry in archive.namelist():
                if not entry.startswith("lib/") or not entry.endswith(".so"):
                    continue
                library_abi = entry.split("/")[1]
                elf_class, machine = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62)}[library_abi]
                with archive.open(entry) as library:
                    header = library.read(64)
                if (
                    len(header) < (64 if elf_class == 2 else 52)
                    or header[:7] != b"\x7fELF" + bytes((elf_class, 1, 1))
                    or struct.unpack_from("<HH", header, 16) != (3, machine)
                ):
                    raise ContractError(f"Native library ELF does not match its ABI in {name}: {entry}")
        signature = subprocess.run(
            [str(apksigner), "verify", "--verbose", str(path)],
            check=False,
            capture_output=True,
            text=True,
        )
        if signature.returncode == 0 or "Missing META-INF/MANIFEST.MF" not in signature.stdout + signature.stderr:
            raise ContractError(f"APK is signed or signature state is unknown: {name}")
        packages[abi] = {"file": name, "bytes": path.stat().st_size, "sha256": sha256(path)}

    source_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    production_paths = {
        item: subprocess.check_output(
            ["git", "rev-parse", f"HEAD:{item}"], cwd=repo, text=True
        ).strip()
        for item in PRODUCTION_PATHS
    }
    production_sha256 = hashlib.sha256(
        json.dumps(production_paths, sort_keys=True, separators=(",", ":")).encode()
    ).hexdigest()
    return {
        "status": "PASSED",
        "source_sha": source_sha,
        "production_paths": production_paths,
        "production_sha256": production_sha256,
        "native_lock_sha256": sha256(repo / "native/quinn-jni/Cargo.lock"),
        "application_id": "io.ethan.pushgo",
        "version_name": "v1.3.3",
        "version_code": 1030399,
        "signing": "unsigned",
        "packages": packages,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--not-before", type=float, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        print("status=BLOCKED\nreason=ANDROID_SDK_ROOT is unset", file=sys.stderr)
        return 2
    try:
        result = verify(repo, Path(sdk) / "build-tools/36.0.0/apksigner", args.not_before)
    except PreconditionError as error:
        print(f"status=BLOCKED\nreason={error}", file=sys.stderr)
        return 2
    except (OSError, ValueError, zipfile.BadZipFile, ContractError, subprocess.CalledProcessError) as error:
        print(f"status=FAILED\nreason={error}", file=sys.stderr)
        return 1
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"status=PASSED\nreceipt={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
