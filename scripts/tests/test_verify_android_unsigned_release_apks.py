import importlib.util
import contextlib
import io
import json
import struct
import tempfile
import time
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch


REPO = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "verify_android_unsigned_release_apks",
    REPO / "scripts/verify_android_unsigned_release_apks.py",
)
assert SPEC and SPEC.loader
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class UnsignedReleaseApkTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.repo = Path(self.temporary.name)
        self.output = self.repo / "app/build/outputs/apk/release"
        self.output.mkdir(parents=True)
        lock = self.repo / "native/quinn-jni/Cargo.lock"
        lock.parent.mkdir(parents=True)
        lock.write_text("isolated native lock\n", encoding="utf-8")
        self.signer = self.repo / "apksigner"
        self.signer.write_text(
            "#!/bin/sh\necho 'DOES NOT VERIFY'\necho 'ERROR: Missing META-INF/MANIFEST.MF'\nexit 1\n",
            encoding="utf-8",
        )
        self.signer.chmod(0o755)
        self.aapt2 = self.repo / "aapt2"
        self.aapt2.write_text(
            "#!/bin/sh\nprintf \"package: name='io.ethan.pushgo' versionCode='1030399' versionName='v1.3.3'\\n\"\n"
            "case \"$3\" in\n"
            " *universal*) echo \"native-code: 'arm64-v8a' 'armeabi-v7a' 'x86_64'\";;\n"
            " *arm64-v8a*) echo \"native-code: 'arm64-v8a'\";;\n"
            " *armeabi-v7a*) echo \"native-code: 'armeabi-v7a'\";;\n"
            " *x86_64*) echo \"native-code: 'x86_64'\";;\n"
            "esac\n",
            encoding="utf-8",
        )
        self.aapt2.chmod(0o755)
        elements = []
        for abi, name in VERIFIER.EXPECTED_FILES.items():
            with zipfile.ZipFile(self.output / name, "w") as archive:
                for included_abi in VERIFIER.EXPECTED_ABIS if abi == "universal" else (abi,):
                    elf_class, machine = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62)}[included_abi]
                    header = bytearray(64 if elf_class == 2 else 52)
                    header[:7] = b"\x7fELF" + bytes((elf_class, 1, 1))
                    struct.pack_into("<HH", header, 16, 3, machine)
                    archive.writestr(f"lib/{included_abi}/{VERIFIER.JNI_LIBRARY}", header)
            elements.append({
                "outputFile": name,
                "filters": [] if abi == "universal" else [{"filterType": "ABI", "value": abi}],
                "versionName": "v1.3.3",
                "versionCode": 1030399,
            })
        self.metadata = {
            "applicationId": "io.ethan.pushgo",
            "variantName": "release",
            "elements": elements,
        }
        self.save_metadata()

    def save_metadata(self) -> None:
        (self.output / "output-metadata.json").write_text(
            json.dumps(self.metadata), encoding="utf-8"
        )

    def verify(self, not_before: float = 0) -> dict:
        actual_check_output = VERIFIER.subprocess.check_output
        def check_output(command, **kwargs):
            if command[:2] == ["git", "status"]:
                return ""
            if command[0] == "git":
                return "a" * 40
            return actual_check_output(command, **kwargs)
        with patch.object(VERIFIER.subprocess, "check_output", side_effect=check_output):
            return VERIFIER.verify(self.repo, self.signer, not_before)

    def test_exact_unsigned_four_abi_matrix_is_inventoried(self) -> None:
        receipt = self.verify()
        self.assertEqual("PASSED", receipt["status"])
        self.assertEqual(set(VERIFIER.EXPECTED_FILES), set(receipt["packages"]))
        self.assertEqual(set(VERIFIER.PRODUCTION_PATHS), set(receipt["production_paths"]))
        self.assertTrue(all(len(item["sha256"]) == 64 for item in receipt["packages"].values()))

    def test_stale_or_extra_apk_cannot_pass_as_current_build(self) -> None:
        with self.assertRaisesRegex(VERIFIER.ContractError, "predates this build"):
            self.verify(time.time() + 1)
        (self.output / "app-extra-release-unsigned.apk").write_bytes(b"extra")
        with self.assertRaisesRegex(VERIFIER.ContractError, "exactly the four"):
            self.verify()

    def test_wrong_version_or_missing_jni_abi_is_rejected(self) -> None:
        self.metadata["elements"][0]["versionCode"] = 1030299
        self.save_metadata()
        with self.assertRaisesRegex(VERIFIER.ContractError, "Wrong v1.3.3 version"):
            self.verify()
        self.metadata["elements"][0]["versionCode"] = 1030399
        self.save_metadata()
        with zipfile.ZipFile(self.output / VERIFIER.EXPECTED_FILES["universal"], "w") as archive:
            archive.writestr("lib/arm64-v8a/libpushgo_quinn_jni.so", b"native")
        with self.assertRaisesRegex(VERIFIER.ContractError, "Wrong packaged JNI ABI"):
            self.verify()

    def test_signed_apk_is_rejected(self) -> None:
        self.signer.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
        with self.assertRaisesRegex(VERIFIER.ContractError, "signed or signature state is unknown"):
            self.verify()

    def test_forged_metadata_cannot_hide_wrong_packaged_version(self) -> None:
        self.aapt2.write_text(
            "#!/bin/sh\nprintf \"package: name='io.ethan.pushgo' versionCode='1030299' versionName='v1.3.2'\\n\"\n",
            encoding="utf-8",
        )
        with self.assertRaisesRegex(VERIFIER.ContractError, "Packaged manifest"):
            self.verify()

    def test_debuggable_package_is_rejected(self) -> None:
        with self.aapt2.open("a", encoding="utf-8") as script:
            script.write("echo application-debuggable\n")
        with self.assertRaisesRegex(VERIFIER.ContractError, "debuggable"):
            self.verify()

    def test_dirty_worktree_cannot_be_attested_as_head(self) -> None:
        with patch.object(VERIFIER.subprocess, "check_output", return_value=" M app/src/main/example.kt\n"):
            with self.assertRaisesRegex(VERIFIER.ContractError, "not clean"):
                VERIFIER.verify(self.repo, self.signer, 0)

    def test_transitive_x86_library_without_transport_is_rejected(self) -> None:
        with zipfile.ZipFile(self.output / VERIFIER.EXPECTED_FILES["universal"], "a") as archive:
            archive.writestr("lib/x86/libandroidx_dependency.so", b"native")
        with self.assertRaisesRegex(VERIFIER.ContractError, "unsupported ABI"):
            self.verify()

    def test_badging_abi_disagreement_is_rejected(self) -> None:
        script = self.aapt2.read_text().replace("native-code: 'arm64-v8a' 'armeabi-v7a' 'x86_64'", "native-code: 'x86'")
        self.aapt2.write_text(script)
        with self.assertRaisesRegex(VERIFIER.ContractError, "advertises wrong native ABI"):
            self.verify()

    def test_library_path_cannot_hide_wrong_elf_architecture(self) -> None:
        path = self.output / VERIFIER.EXPECTED_FILES["arm64-v8a"]
        header = bytearray(64)
        header[:7] = b"\x7fELF\x02\x01\x01"
        struct.pack_into("<HH", header, 16, 3, 62)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(f"lib/arm64-v8a/{VERIFIER.JNI_LIBRARY}", header)
        with self.assertRaisesRegex(VERIFIER.ContractError, "ELF does not match"):
            self.verify()

    def test_missing_sdk_is_blocked_before_any_product_claim(self) -> None:
        with patch.object(VERIFIER.os, "environ", {}), patch.object(
            VERIFIER.sys, "argv", ["verifier", "--output", str(self.repo / "receipt.json"), "--not-before", "0"]
        ), contextlib.redirect_stderr(io.StringIO()) as stderr:
            self.assertEqual(2, VERIFIER.main())
        self.assertIn("status=BLOCKED", stderr.getvalue())
        self.assertFalse((self.repo / "receipt.json").exists())


if __name__ == "__main__":
    unittest.main()
