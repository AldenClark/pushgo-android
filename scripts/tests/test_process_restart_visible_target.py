from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class VisibleTargetTests(unittest.TestCase):
    def center(self, row_bounds: str):
        source = (ROOT / "scripts/run_android_process_restart_positive.sh").read_text()
        helper = "node_center() {" + source.split("node_center() {", 1)[1].split("\nui_root_package() {", 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / "window.xml"
            xml.write_text(f'''<hierarchy><node bounds="[0,0][1080,1920]">
              <node resource-id="screen.settings.content" scrollable="true" bounds="[0,234][1080,1794]">
                <node resource-id="row.settings.docs.getting_started" clickable="true" bounds="{row_bounds}"/>
              </node></node></hierarchy>''')
            return subprocess.run(["bash", "-c", helper + '\nui_dump="$1"\nnode_center resource row.settings.docs.getting_started', "test", str(xml)], capture_output=True, text=True, check=False)

    def test_recorded_ci_row_uses_visible_part_above_navigation(self):
        result = self.center("[32,1733][1048,1859]")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "540,1763")

    def test_offscreen_row_cannot_satisfy_reachability(self):
        self.assertEqual(self.center("[32,1800][1048,1926]").returncode, 1)

    def test_fully_visible_row_keeps_its_real_center(self):
        result = self.center("[32,1000][1048,1126]")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "540,1063")


class ChromePreparationTests(unittest.TestCase):
    def onboarding(self, package, title, resource=""):
        source = (ROOT / "scripts/run_android_process_restart_positive.sh").read_text()
        center = "node_center() {" + source.split("node_center() {", 1)[1].split("\nui_root_package() {", 1)[0]
        owner = "ui_root_package() {" + source.split("ui_root_package() {", 1)[1].split("\nchrome_notification_onboarding() {", 1)[0]
        preparation = "chrome_notification_onboarding() {" + source.split("chrome_notification_onboarding() {", 1)[1].split("\nrecord_phase() {", 1)[0]
        with tempfile.TemporaryDirectory() as directory:
            xml = Path(directory) / "window.xml"
            xml.write_text(f'<hierarchy><node package="{package}" bounds="[0,0][1080,1920]"><node text="{title}" resource-id="{resource}" bounds="[50,200][1000,300]"/><node resource-id="negative_button" bounds="[50,1500][400,1600]"/></node></hierarchy>')
            return subprocess.run(["bash", "-c", center + owner + preparation + '\nui_dump="$1"\nchrome_notification_onboarding', "test", str(xml)], capture_output=True, text=True).returncode

    def test_recorded_new_chrome_title_is_a_precondition(self):
        self.assertEqual(self.onboarding("com.android.chrome", "Chrome notifications make things easier"), 0)

    def test_legacy_chrome_title_id_remains_supported(self):
        self.assertEqual(self.onboarding("com.android.chrome", "", "com.android.chrome:id/notification_permission_rationale_title"), 0)

    def test_generic_negative_button_and_other_app_are_not_onboarding(self):
        self.assertNotEqual(self.onboarding("com.android.chrome", "Unrelated confirmation"), 0)
        self.assertNotEqual(self.onboarding("another.app", "Chrome notifications make things easier"), 0)
