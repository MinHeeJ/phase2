"""Regression checks for the public runtime checker; never print payloads."""
import contextlib
import copy
import io
import json
import unittest
from unittest.mock import patch

import check_runtime as runtime


class RuntimeCheckTest(unittest.TestCase):
    def setUp(self):
        self.contract = json.loads((runtime.ROOT / "aiops.runtime-check.json").read_text(encoding="utf-8"))

    def test_root_contract_is_public_and_valid(self):
        runtime.validate(self.contract)

    def test_unknown_fields_and_authentication_are_rejected(self):
        for change in ({"identity": {}}, {"auth": {"required": True}}):
            contract = copy.deepcopy(self.contract)
            contract.update(change)
            with self.assertRaises(ValueError):
                runtime.validate(contract)

    def test_unsafe_paths_are_rejected(self):
        for path in ("/../api", "/api?q=x", "/api#x", "/api/{id}", "https://example.test", "/api x"):
            with self.assertRaises(ValueError):
                runtime.safe_path(path)

    def test_menu_checks_the_actual_nested_array(self):
        expectations = self.contract["first_menu"]["expect"]
        runtime.check_expectations({"success": True, "data": {"success": True, "data": []}}, expectations)
        for payload in ({"success": False}, {"success": True, "data": []},
                        {"success": True, "data": {"success": True, "data": {}}},
                        {"success": True, "error": {}}):
            with self.assertRaises(ValueError):
                runtime.check_expectations(payload, expectations)

    def test_failures_do_not_print_exception_or_payload_details(self):
        output = io.StringIO()
        with patch("sys.argv", ["check_runtime.py"]), patch.object(runtime, "fetch", side_effect=RuntimeError("do-not-print")):
            with contextlib.redirect_stderr(output):
                self.assertEqual(runtime.main(), 1)
        self.assertEqual(output.getvalue(), "app runtime check: FAIL (health)\n")


if __name__ == "__main__":
    unittest.main()
