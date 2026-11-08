import contextlib
import io
import json
import os
import subprocess
import unittest
from unittest.mock import patch

import verify_runtime


class RuntimeVerificationTest(unittest.TestCase):
    def setUp(self):
        contract = verify_runtime.load_contract()
        contract['auth'] = {'required': False}
        self.contract_patch = patch('verify_runtime.load_contract', return_value=contract)
        self.contract_patch.start()
        self.addCleanup(self.contract_patch.stop)

    @staticmethod
    def http(document):
        return b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n' + json.dumps(document).encode()

    def outputs(self, states=None, menu=None):
        services = {name: {} for name in ("db", "backend", "frontend")}
        if states is None:
            states = [{"Service": name, "State": "running", "Health": "healthy"} for name in services]
        if menu is None:
            menu = {"success": True, "data": {"items": [{"private": "DO_NOT_EMIT"}]}}
        return [json.dumps({"services": services}).encode(), json.dumps(states).encode(),
                self.http({'success': True}), self.http(menu), self.http(menu), b""]

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
        self.assertTrue(calls[-1][-1].endswith("/UI-01"))
        self.assertIn('frontend:8080/api/projects', run.call_args_list[-2].args[1].decode())

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

    def test_role_must_be_a_string_array_containing_declared_role(self):
        expected = [{'path': 'data.roles', 'op': 'type', 'value': 'array_of_string'},
                    {'path': 'data.roles', 'op': 'contains', 'value': '$test_role'}]
        verify_runtime.check_expectations({'data': {'roles': ['경영진']}}, expected, '경영진')
        for roles in ([1], ['일반 사용자(PM)'], '경영진'):
            with self.assertRaises(verify_runtime.VerificationError):
                verify_runtime.check_expectations({'data': {'roles': roles}}, expected, '경영진')

    @patch('verify_runtime.run')
    def test_authenticated_contract_uses_direct_login_without_preflight_or_secret_output(self, run):
        self.contract_patch.stop()
        contract = verify_runtime.load_contract()
        outputs = self.outputs()
        login = b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nSet-Cookie: JSESSIONID=DO_NOT_EMIT; HttpOnly; Secure\r\n\r\n' + json.dumps({'success': True, 'data': {'status': 'AUTHENTICATED'}}).encode()
        identity = self.http({'success': True, 'data': {'roles': [contract['auth']['test_role']]}})
        run.side_effect = outputs[:3] + [login, identity] + outputs[3:]
        with patch.dict(os.environ, {'AIOPS_SMOKE_LOGIN_ID': 'DO_NOT_EMIT', 'AIOPS_SMOKE_LOGIN_PASSWORD': 'DO_NOT_EMIT'}):
            self.assertEqual(self.invoke()[0], 0)
        self.assertEqual(run.call_count, 8)
        login_call = run.call_args_list[3]
        self.assertIn('/api/auth/login', login_call.args[1].decode())
        self.assertNotIn('X-CSRF-TOKEN', login_call.args[1].decode())
        self.assertTrue(all('DO_NOT_EMIT' not in str(call.args[0]) for call in run.call_args_list))

    @patch('verify_runtime.run')
    def test_html_and_error_envelopes_fail(self, run):
        for raw in (b'HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\nDO_NOT_EMIT',
                    self.http({'success': False}), self.http({'error': 'DO_NOT_EMIT'})):
            run.return_value = raw
            with self.assertRaises(verify_runtime.VerificationError):
                verify_runtime.request([], 'backend', 8080, {'method': 'GET', 'path': '/api/health'}, {})


if __name__ == "__main__":
    unittest.main()
