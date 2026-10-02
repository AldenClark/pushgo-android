import importlib.util
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).resolve().parents[1] / "verify_android_localizations.py"
SPEC = importlib.util.spec_from_file_location("verify_android_localizations", MODULE_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class AndroidLocalizationContractTests(unittest.TestCase):
    def write_tree(self, root: Path, localized_value: str = "%1$d 条消息") -> None:
        for directory, value in (
            ("values", "%1$d messages"),
            ("values-zh-rCN", localized_value),
            ("values-zh-rTW", "%1$d 則訊息"),
        ):
            target = root / directory
            target.mkdir(parents=True)
            (target / "strings.xml").write_text(
                f'<resources><string name="title">Title</string>'
                f'<plurals name="count"><item quantity="other">{value}</item></plurals></resources>',
                encoding="utf-8",
            )

    def test_complete_resources_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_tree(root)
            self.assertEqual([], MODULE.validate_resource_tree(root))

    def test_missing_resource_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_tree(root)
            path = root / "values-zh-rTW" / "strings.xml"
            path.write_text('<resources><string name="title">標題</string></resources>', encoding="utf-8")
            self.assertTrue(any("missing plurals count" in error for error in MODULE.validate_resource_tree(root)))

    def test_placeholder_loss_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_tree(root, localized_value="消息")
            self.assertTrue(any("placeholder mismatch" in error for error in MODULE.validate_resource_tree(root)))

    def test_resources_added_in_another_xml_file_are_not_silently_ignored(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_tree(root)
            (root / "values" / "feature.xml").write_text(
                '<resources><string name="new_feature">New feature</string></resources>',
                encoding="utf-8",
            )
            errors = MODULE.validate_resource_tree(root)
            self.assertTrue(any("missing string new_feature" in error for error in errors))

    def test_missing_locale_directory_is_a_failed_contract_not_a_blocked_runner(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_tree(root)
            (root / "values-zh-rTW" / "strings.xml").unlink()
            (root / "values-zh-rTW").rmdir()
            errors = MODULE.validate_resource_tree(root)
            self.assertTrue(any(error.startswith("values-zh-rTW: missing") for error in errors))


if __name__ == "__main__":
    unittest.main()
