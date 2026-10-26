import contextlib
import io
import json
import subprocess
import unittest
from unittest.mock import patch

import verify_runtime


class RuntimeVerificationTest(unittest.TestCase):
    def outputs(self, states=None, menu=None):
        services = {name: {} for name in ("db", "backend", "frontend")}
        if states is None:
            states = [{"Service": name, "State": "running", "Health": "healthy"} for name in services]
        if menu is None:
            menu = {"success": True, "data": {"items": [{"private": "DO_NOT_EMIT"}]}}
        return [json.dumps({"services": services}).encode(), json.dumps(states).encode(),
                b'{"success":true}', json.dumps(menu).encode(), json.dumps(menu).encode(), b""]

    def invoke(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = verify_runtime.main(["--project", "isolated-check"])
        self.assertNotIn("DO_NOT_EMIT", output.getvalue())
        return code, output.getvalue()

    @patch("verify_runtime.run")
    def test_checks_boot_health_menu_proxy_and_route_without_printing_content(self, run):
        run.side_effect = self.outputs()
        code, output = self.invoke()
        self.assertEqual(code, 0)
        self.assertIn("runtime_check=PASS", output)
        calls = [call.args[0] for call in run.call_args_list]
        self.assertEqual(len(calls), 6)
        self.assertTrue(all("--project-name" in call and "isolated-check" in call for call in calls))
        self.assertTrue(calls[-1][-1].endswith("/UI-27"))
        self.assertTrue(calls[-2][-1].endswith("/api/admin/code-groups"))

    @patch("verify_runtime.run")
    def test_missing_containers_fail_before_requests(self, run):
        run.side_effect = self.outputs(states=[])
        self.assertEqual(self.invoke()[0], 1)
        self.assertEqual(run.call_count, 2)

    @patch("verify_runtime.run")
    def test_unhealthy_dependency_fails(self, run):
        run.side_effect = self.outputs(states=[{"Service": "db", "State": "running", "Health": "unhealthy"}])
        self.assertEqual(self.invoke()[0], 1)
        self.assertEqual(run.call_count, 2)

    @patch("verify_runtime.run")
    def test_bad_menu_contract_is_not_replaced_with_a_success(self, run):
        run.side_effect = self.outputs(menu={"success": True, "data": {"items": "DO_NOT_EMIT"}})
        self.assertEqual(self.invoke()[0], 1)

    @patch("verify_runtime.run")
    def test_malformed_response_never_prints_parse_exception_content(self, run):
        outputs = self.outputs()
        outputs[2] = b"DO_NOT_EMIT"
        run.side_effect = outputs
        self.assertEqual(self.invoke()[0], 1)

    @patch("verify_runtime.run")
    def test_command_exception_never_prints_command_or_output(self, run):
        run.side_effect = subprocess.TimeoutExpired(["DO_NOT_EMIT"], 30, output=b"DO_NOT_EMIT")
        self.assertEqual(self.invoke()[0], 1)

    def test_boolean_success_is_not_interchangeable_with_integer(self):
        with self.assertRaises(verify_runtime.VerificationError):
            verify_runtime.check_expectations({"success": 1}, [{"path": "success", "op": "equals", "value": True}])


if __name__ == "__main__":
    unittest.main()
