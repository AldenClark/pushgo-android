import json
import re
import unittest
from pathlib import Path


REPO = Path(__file__).resolve().parents[2]
DATABASE_SOURCE = REPO / "app/src/main/java/io/ethan/pushgo/data/db/PushGoDatabase.kt"
MIGRATION_TEST = (
    REPO
    / "app/src/androidTest/java/io/ethan/pushgo/data/db/PushGoDatabaseMigrationDeviceTest.kt"
)
SCHEMA_DIR = REPO / "app/schemas/io.ethan.pushgo.data.db.PushGoDatabase"


class RoomMigrationCoverageTests(unittest.TestCase):
    def test_production_registry_is_contiguous_and_every_step_is_registered(self) -> None:
        source = DATABASE_SOURCE.read_text()
        current = int(re.search(r"\bversion\s*=\s*(\d+)", source).group(1))
        # The declaration regex includes both name and constructor pairs. Compare them
        # separately so a renamed or miswired migration cannot satisfy the chain.
        declarations = re.findall(
            r"MIGRATION_(\d+)_(\d+)\s*=\s*object\s*:\s*Migration\((\d+),\s*(\d+)\)",
            source,
        )
        self.assertTrue(declarations)
        self.assertTrue(all((a, b) == (c, d) for a, b, c, d in declarations))
        name_pairs = {(int(a), int(b)) for a, b, _, _ in declarations}
        expected = {(version, version + 1) for version in range(21, current)}
        self.assertEqual(expected, name_pairs)

        registration = source.split(".addMigrations(", 1)[1].split(")", 1)[0]
        for start, end in sorted(expected):
            self.assertEqual(1, registration.count(f"MIGRATION_{start}_{end}"))

    def test_exported_schemas_reach_current_and_are_packaged_as_test_inputs(self) -> None:
        source = DATABASE_SOURCE.read_text()
        build = (REPO / "app/build.gradle.kts").read_text()
        current = int(re.search(r"\bversion\s*=\s*(\d+)", source).group(1))
        exported = sorted(int(path.stem) for path in SCHEMA_DIR.glob("*.json"))

        self.assertEqual(list(range(exported[0], current + 1)), exported)
        for version in exported:
            payload = json.loads((SCHEMA_DIR / f"{version}.json").read_text())
            self.assertEqual(version, payload["database"]["version"])
        self.assertIn(
            'assets.directories.add("schemas/io.ethan.pushgo.data.db.PushGoDatabase")',
            build,
        )

    def test_stateful_late_schema_evidence_uses_exports_and_asserts_after_reopen(self) -> None:
        test = MIGRATION_TEST.read_text()
        self.assertIn(
            "productionDatabase_migratesV27LegacyIngressWithoutLosingQueuedPayloadAndReopens",
            test,
        )
        self.assertIn(
            "productionDatabase_migratesV28PendingDeletionWithoutLosingIntentAndReopens",
            test,
        )
        self.assertIn("seedDatabaseFromExportedSchema(27)", test)
        self.assertIn("seedDatabaseFromExportedSchema(28)", test)
        self.assertGreaterEqual(test.count("assertLegacyIngressBusinessState("), 3)
        self.assertGreaterEqual(test.count("assertPendingDeletionBusinessState("), 3)
        self.assertIn("SELECT gateway_url, device_key, delivery_id, payload_json, enqueued_at", test)
        self.assertIn("expected_gateway_url, expected_updated_at, expected_use_provider", test)


if __name__ == "__main__":
    unittest.main()
