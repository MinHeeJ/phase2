import contextlib
import io
import json
import os
import secrets
import subprocess
import unittest
from unittest.mock import patch

import verify_runtime


class RuntimeVerificationTest(unittest.TestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ, dict(zip(verify_runtime.SLOTS,
            [secrets.token_hex(12), secrets.token_urlsafe(32), "재무 담당"])))
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def response(self, document):
        return b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n" + json.dumps(document).encode()

    def outputs(self, states=None, menu=None):
        services = {name: {} for name in ("db", "backend", "frontend")}
        if states is None:
            states = [{"Service": name, "State": "running", "Health": "healthy"} for name in services]
        if menu is None:
            menu = {"success": True, "data": {"items": [{"private": "DO_NOT_EMIT"}]}}
        return [json.dumps({"services": services}).encode(), json.dumps(states).encode(),
                self.response({"success": True}),
                self.response({"data": {"headerName": "X-CSRF-TOKEN", "csrfToken": secrets.token_urlsafe(16)}}),
                self.response({"data": {"state": "AUTHENTICATED"}}),
                self.response({"data": {"roles": ["재무 담당"]}}),
                self.response(menu), self.response(menu),
                b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html></html>"]

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
        self.assertEqual(len(calls), 9)
        self.assertTrue(all("--project-name" in call and "isolated-check" in call for call in calls))
        configs = [call.kwargs.get("input", b"") for call in run.call_args_list]
        self.assertIn(b"/UI-13", configs[-1])
        self.assertIn(b"/api/billings", configs[-2])
        self.assertTrue(all(not any(os.environ[name] in str(call) for name in verify_runtime.SLOTS[:2]) for call in calls))

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

    def test_slots_are_generated_only_for_explicit_start_and_never_defaulted(self):
        for environment in ({}, {verify_runtime.SLOTS[0]: secrets.token_hex(12)}):
            with self.assertRaises(verify_runtime.VerificationError):
                verify_runtime.credentials(environment.copy(), "재무 담당")
        partial = {verify_runtime.SLOTS[0]: secrets.token_hex(12)}
        with self.assertRaises(verify_runtime.VerificationError):
            verify_runtime.credentials(partial, "재무 담당", create=True)
        first, second = {}, {}
        verify_runtime.credentials(first, "재무 담당", create=True)
        verify_runtime.credentials(second, "재무 담당", create=True)
        self.assertTrue(all(first[name] != second[name] for name in verify_runtime.SLOTS[:2]))
        self.assertEqual(first[verify_runtime.SLOTS[2]], "재무 담당")

    @patch("verify_runtime.run")
    def test_wrong_identity_role_fails(self, run):
        outputs = self.outputs()
        outputs[5] = self.response({"data": {"roles": ["일반 사용자"]}})
        run.side_effect = outputs
        self.assertEqual(self.invoke()[0], 1)

    @patch("verify_runtime.run")
    def test_html_empty_error_envelope_and_non_2xx_are_rejected(self, run):
        for raw in (b"HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n<html/>",
                    b"HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n",
                    self.response({"success": False}),
                    b"HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\n\r\n{}"):
            outputs = self.outputs(); outputs[2] = raw; run.side_effect = outputs
            self.assertEqual(self.invoke()[0], 1)

    @patch("verify_runtime.run")
    def test_cookie_is_reused_through_stdin_and_never_command_arguments(self, run):
        outputs = self.outputs()
        cookie = secrets.token_urlsafe(32)
        outputs[4] = outputs[4].replace(b"Content-Type:", ("Set-Cookie: JSESSIONID=" + cookie + "; HttpOnly; Path=/\r\nContent-Type:").encode())
        run.side_effect = outputs
        self.assertEqual(self.invoke()[0], 0)
        self.assertTrue(cookie.encode() in run.call_args_list[5].kwargs["input"])
        self.assertTrue(all(cookie not in str(call.args) for call in run.call_args_list))

    @patch("verify_runtime.run")
    def test_start_injects_only_at_boot_and_cleans_up_on_failure(self, run):
        with patch.dict(os.environ, {}, clear=True):
            with patch("verify_runtime.verify_running", side_effect=verify_runtime.VerificationError("failure")):
                # Only ps, volume inspection, up and down run; verification is stubbed.
                run.side_effect = [b"", b"", b"", b""]
                with self.assertRaises(verify_runtime.VerificationError):
                    verify_runtime.verify("isolated-check", start=True)
            self.assertNotIn("environment", run.call_args_list[0].kwargs)
            self.assertTrue(all(run.call_args_list[2].kwargs["environment"].get(name) for name in verify_runtime.SLOTS))
            self.assertIn("down", run.call_args_list[-1].args[0])
            self.assertIn("--volumes", run.call_args_list[-1].args[0])
            self.assertFalse(any(name in run.call_args_list[-1].kwargs["environment"] for name in verify_runtime.SLOTS))

    @patch("verify_runtime.subprocess.run")
    def test_non_start_commands_strip_slots_from_the_subprocess_environment(self, execute):
        execute.return_value = subprocess.CompletedProcess([], 0, stdout=b"", stderr=b"")
        verify_runtime.run(["docker", "compose", "config", "--format", "json"])
        self.assertFalse(any(name in execute.call_args.kwargs["env"] for name in verify_runtime.SLOTS))
        self.assertTrue(all(os.environ.get(name) for name in verify_runtime.SLOTS))

    @patch("verify_runtime.subprocess.run")
    def test_explicit_start_environment_reaches_the_subprocess_unchanged(self, execute):
        execute.return_value = subprocess.CompletedProcess([], 0, stdout=b"", stderr=b"")
        environment = os.environ.copy()
        verify_runtime.run(["docker", "compose", "up", "--wait"], environment=environment)
        self.assertIs(execute.call_args.kwargs["env"], environment)

    @patch("verify_runtime.verify")
    def test_no_target_defaults_to_a_new_isolated_start_each_time(self, verify):
        with patch.dict(os.environ, {}, clear=True), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(verify_runtime.main([]), 0)
            self.assertEqual(verify_runtime.main([]), 0)
        first, second = verify.call_args_list
        self.assertTrue(first.args[0].startswith("sicost-smoke-"))
        self.assertNotEqual(first.args[0], second.args[0])
        self.assertIs(first.args[1], True)
        self.assertIs(second.args[1], True)

    @patch("verify_runtime.run")
    def test_named_fresh_project_without_slots_bootstraps_matching_credentials(self, run):
        with patch.dict(os.environ, {}, clear=True), patch("verify_runtime.verify_running") as running:
            run.return_value = b""
            code, output = self.invoke()
            self.assertEqual(code, 0)
        boot = run.call_args_list[2]
        self.assertIn("up", boot.args[0])
        self.assertIn("isolated-check", boot.args[0])
        environment = boot.kwargs["environment"]
        self.assertTrue(all(environment.get(name) for name in verify_runtime.SLOTS))
        self.assertFalse(any(environment[name] in output for name in verify_runtime.SLOTS[:2]))
        self.assertTrue(running.call_args.args[2] == tuple(environment[name] for name in verify_runtime.SLOTS[:2]))
        self.assertFalse(any(name in run.call_args_list[-1].kwargs["environment"] for name in verify_runtime.SLOTS))

    @patch("verify_runtime.run")
    def test_named_existing_project_without_slots_is_never_started_or_removed(self, run):
        for outputs in ([b"existing-container"], [b"", b"existing-volume"]):
            run.reset_mock()
            run.side_effect = outputs
            with patch.dict(os.environ, {}, clear=True):
                self.assertEqual(self.invoke()[0], 1)
            self.assertFalse(any("up" in call.args[0] or "down" in call.args[0] for call in run.call_args_list))

    @patch("verify_runtime.run")
    def test_named_project_with_partial_slots_still_fails_closed(self, run):
        with patch.dict(os.environ, {verify_runtime.SLOTS[0]: secrets.token_hex(12)}, clear=True):
            self.assertEqual(self.invoke()[0], 1)
        run.assert_not_called()

    @patch("verify_runtime.run")
    def test_existing_project_is_never_reseeded_or_removed(self, run):
        run.return_value = b"existing-container"
        with self.assertRaises(verify_runtime.VerificationError):
            verify_runtime.verify("isolated-check", start=True)
        self.assertEqual(run.call_count, 1)
        self.assertIn("ps", run.call_args.args[0])

    @patch("verify_runtime.run")
    def test_existing_volumes_are_never_reseeded_or_removed(self, run):
        run.side_effect = [b"", b"existing-volume"]
        with self.assertRaises(verify_runtime.VerificationError):
            verify_runtime.verify("isolated-check", start=True)
        self.assertEqual(run.call_count, 2)

    @patch("verify_runtime.run")
    def test_fresh_start_uses_same_in_memory_credentials_for_login(self, run):
        with patch.dict(os.environ, {}, clear=True), patch("verify_runtime.verify_running") as running:
            run.return_value = b""
            verify_runtime.verify("isolated-check", start=True)
            self.assertFalse(any(os.environ.get(name) for name in verify_runtime.SLOTS))
        boot = run.call_args_list[2].kwargs["environment"]
        self.assertTrue(running.call_args.args[2] == tuple(boot[name] for name in verify_runtime.SLOTS[:2]))
        self.assertEqual(boot[verify_runtime.SLOTS[2]], "재무 담당")


if __name__ == "__main__":
    unittest.main()
