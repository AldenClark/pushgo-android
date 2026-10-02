from __future__ import annotations

import unittest

from scripts.verify_android_host_execution_receipt import receipt_matches


class VerifyAndroidHostExecutionReceiptTests(unittest.TestCase):
    def test_accepts_one_exact_independent_selector(self):
        self.assertTrue(
            receipt_matches(
                "status=EXECUTED_IDENTITY\nexecuted_selector=example.Journey#purpose\n",
                "example.Journey#purpose",
            )
        )

    def test_rejects_missing_wrong_or_duplicate_selector(self):
        expected = "example.Journey#purpose"
        self.assertFalse(receipt_matches("status=PASSED\n", expected))
        self.assertFalse(receipt_matches("executed_selector=example.Journey#other\n", expected))
        self.assertFalse(
            receipt_matches(
                f"executed_selector={expected}\nexecuted_selector={expected}\n",
                expected,
            )
        )


if __name__ == "__main__":
    unittest.main()
