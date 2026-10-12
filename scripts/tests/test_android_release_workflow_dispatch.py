import re
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github/workflows/android-release.yml"
TAG_ONLY = "github.event_name == 'push' && startsWith(github.ref, 'refs/tags/v')"


class AndroidReleaseWorkflowDispatchTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = WORKFLOW.read_text(encoding="utf-8")

    def step(self, name: str) -> str:
        match = re.search(
            rf"(?ms)^      - name: {re.escape(name)}\n(?P<body>.*?)(?=^      - name: |\Z)",
            self.source,
        )
        self.assertIsNotNone(match, f"missing workflow step: {name}")
        return match.group("body")

    def test_manual_candidate_is_version_bound_and_uses_existing_signed_path(self) -> None:
        self.assertRegex(
            self.source,
            r"(?ms)^  workflow_dispatch:\n    inputs:\n      candidate_version:.*?default: \"1\.3\.3\"",
        )
        parse = self.step("Parse SemVer tag context")
        self.assertIn('"${GITHUB_EVENT_NAME}" == "workflow_dispatch"', parse)
        self.assertIn('tag="v${candidate_version}"', parse)
        self.assertIn("refs/tags/v*", parse)
        self.assertNotIn("git tag ", self.source)

        validate = self.step("Validate manual candidate against project version")
        self.assertIn("if: github.event_name == 'workflow_dispatch'", validate)
        self.assertIn(":app:printReleaseVersionInfo", validate)
        self.assertIn('[[ "$candidate_version" != "$project_version" ]]', validate)
        self.assertLess(
            self.source.index("Validate manual candidate against project version"),
            self.source.index("Run bounded Android release smoke before signing"),
        )
        self.assertIn("Prepare release signing", self.source)
        self.assertIn(":app:assembleRelease", self.step("Build release APKs"))
        verify = self.step("Verify signed release artifacts")
        for contract in (
            "${#expected_apks[@]}",
            'variantName == "release"',
            "expected_signer_sha256",
            "Packaged manifest identity or version",
            "Packaged native ABI set",
            "Packaged native library ABI set",
            "zipalign -c",
        ):
            self.assertIn(contract, verify)

    def test_native_tools_are_prepared_before_expensive_smoke_and_permission_checks(self) -> None:
        tools = self.step("Install existing device-script tools")
        self.assertIn("command -v rg", tools)
        self.assertIn("sudo apt-get install --no-install-recommends --yes ripgrep", tools)
        self.assertIn("rg --version", tools)
        self.assertLess(
            self.source.index("- name: Install existing device-script tools"),
            self.source.index("- name: Run bounded Android release smoke before signing"),
        )

    def test_every_feed_release_and_repository_mutation_is_tag_only(self) -> None:
        protected_steps = (
            "Extract release notes from release/RELEASE_NOTES",
            "Resolve versioned update notes payload",
            "Prepare update feed signing key",
            "Validate update feed ECDSA key pair",
            "Generate update feed artifacts",
            "Generate update deploy bundle",
            "Verify update feed artifacts",
            "Prepare update deploy SSH key",
            "Install update deploy known_hosts",
            "Publish update artifacts to update host",
            "Create or update GitHub release",
            "Upload GitHub release assets",
            "Persist update feed to repository",
        )
        for name in protected_steps:
            with self.subTest(step=name):
                self.assertIn(TAG_ONLY, self.step(name))

        self.assertRegex(self.source, r"(?m)^permissions:\n  contents: read$")
        self.assertRegex(
            self.source,
            r"(?ms)^  release:\n    runs-on: ubuntu-latest\n    permissions:\n      contents: write",
        )
        for mutation in ("publish_update_artifacts.sh", "git commit -m", "git push origin"):
            self.assertIn(mutation, self.source)
            owning_step = next(name for name in protected_steps if mutation in self.step(name))
            self.assertIn(TAG_ONLY, self.step(owning_step))

    def test_manual_upload_contains_signed_candidate_and_receipts_only(self) -> None:
        upload = self.step("Upload manual signed Release acceptance evidence")
        self.assertIn("if: success() && github.event_name == 'workflow_dispatch'", upload)
        self.assertIn("android-signed-release-acceptance-", upload)
        self.assertIn("app/build/outputs/apk/release/*.apk", upload)
        self.assertIn("app/build/outputs/apk/release/output-metadata.json", upload)
        self.assertIn("dist/SHA256SUMS.txt", upload)
        self.assertIn("signed-artifact-verification.log", upload)
        self.assertIn("retention-days: 21", upload)
        self.assertLess(
            self.source.index("Upload manual signed Release acceptance evidence"),
            self.source.index("Generate update deploy bundle"),
        )
        self.assertIn('echo "tag=${{ steps.tag.outputs.tag_name }}"', self.step("Package release artifacts"))


if __name__ == "__main__":
    unittest.main()
