"""Compose boot and authenticated UI API checks. Print only stage results, never HTTP data."""
import argparse
from http.cookies import SimpleCookie
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


class VerificationError(Exception):
    pass


def run(command, payload=None):
    result = subprocess.run(command, input=payload, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=30)
    if result.returncode:
        raise VerificationError("runtime command failed")
    return result.stdout


def load_contract():
    return json.loads((ROOT / "aiops.runtime-check.json").read_text(encoding="utf-8"))


def check_expectations(document, expectations, test_role=None):
    if not isinstance(document, dict) or document.get("success") is False or document.get("error"):
        raise VerificationError("response contract mismatch")
    for expected in expectations:
        value = document
        for key in expected["path"].split("."):
            if not isinstance(value, dict) or key not in value:
                raise VerificationError("response contract mismatch")
            value = value[key]
        target = expected.get("value")
        if target == "$test_role":
            target = test_role
        op = expected["op"]
        if op == "exists":
            matches = True
        elif op == "equals":
            matches = type(value) is type(target) and value == target
        elif op == "contains":
            matches = isinstance(value, list) and any(type(v) is type(target) and v == target for v in value)
        elif op == "type":
            kinds = {"string": str, "boolean": bool, "object": dict, "array": list}
            if target == "array_of_string":
                matches = isinstance(value, list) and all(isinstance(v, str) for v in value)
            elif target == "number":
                matches = type(value) in (int, float)
            else:
                matches = target in kinds and type(value) is kinds[target]
        else:
            raise VerificationError("unsupported response expectation")
        if not matches:
            raise VerificationError("response contract mismatch")


def request(compose, service, port, operation, cookies, payload=None, csrf=None):
    path = operation["path"]
    method = operation["method"]
    if method not in ("GET", "POST") or not re.fullmatch(r"/[A-Za-z0-9/_-]+", path):
        raise VerificationError("invalid operation")
    # Headers and bodies (including credentials) travel on stdin, never argv or logs.
    config = ['silent', 'show-error', 'include', 'max-time = 15',
              'request = "' + method + '"',
              'url = "http://' + ('frontend' if service == 'frontend' else '127.0.0.1') + ':' + str(port) + path + '"']
    if cookies:
        config.append('cookie = "' + '; '.join(k + '=' + v for k, v in cookies.items()) + '"')
    if csrf:
        if csrf[0] != "X-CSRF-TOKEN" or not re.fullmatch(r"[A-Za-z0-9_-]+", csrf[1]):
            raise VerificationError("invalid csrf response")
        config.append('header = "' + csrf[0] + ': ' + csrf[1] + '"')
    if payload is not None:
        # JSON-encode again for curl's quoted config-string grammar.
        config += ['header = "Content-Type: application/json"',
                   'data-binary = ' + json.dumps(json.dumps(payload))]
    raw = run(compose + ["exec", "-T", 'backend' if service == 'frontend' else service, "curl", "--config", "-"],
              ('\n'.join(config) + '\n').encode())
    header, separator, body = raw.partition(b"\r\n\r\n")
    if not separator:
        raise VerificationError("invalid HTTP response")
    lines = header.decode("iso-8859-1").split("\r\n")
    if not re.fullmatch(r"HTTP/\S+ 2\d\d(?: .*)?", lines[0]):
        raise VerificationError("HTTP status mismatch")
    content_type = ""
    for line in lines[1:]:
        name, _, value = line.partition(":")
        if name.lower() == "content-type":
            content_type = value.strip().split(";")[0]
        if name.lower() == "set-cookie":
            jar = SimpleCookie()
            jar.load(value.strip())
            for key, morsel in jar.items():
                cookies[key] = morsel.value
    if content_type != "application/json":
        raise VerificationError("JSON response required")
    document = json.loads(body)
    if not isinstance(document, dict) or document.get("success") is False or document.get("error"):
        raise VerificationError("response contract mismatch")
    return document


def verify(project=None):
    contract = load_contract()
    compose = ["docker", "compose", "-f", str(ROOT / "infra/docker-compose.yml")]
    if project:
        compose += ["--project-name", project]
    services = json.loads(run(compose + ["config", "--format", "json"]))["services"]
    raw = run(compose + ["ps", "--all", "--format", "json"]).decode()
    try:
        rows = json.loads(raw)
        rows = rows if isinstance(rows, list) else [rows]
    except json.JSONDecodeError:
        rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
    states = {row["Service"]: row for row in rows}
    # Optional model download is a one-shot job, not a web-app health dependency.
    for name in ("db", contract["backend"]["service"], "frontend"):
        if name not in states or states[name].get("State") != "running" or states[name].get("Health") != "healthy":
            raise VerificationError("APP_BOOT_NOT_READY")
    backend = contract["backend"]
    service, port = backend["service"], backend["container_port"]
    if service not in services or type(port) is not int or not 1 <= port <= 65535:
        raise VerificationError("invalid backend contract")
    cookies = {}
    health = backend["health"]
    check_expectations(request(compose, service, port, health, cookies), health["expect"])
    auth = contract["auth"]
    role = auth.get("test_role")
    if auth["required"]:
        login_id, password = os.environ.get("AIOPS_SMOKE_LOGIN_ID"), os.environ.get("AIOPS_SMOKE_LOGIN_PASSWORD")
        if not login_id or not password:
            raise VerificationError("verification credentials required")
        login = auth["login"]
        fields = login["body_fields"]
        # Exercise the declared login operation directly, as the Quality Agent does.
        # A browser-only CSRF preflight must not hide a broken runtime contract.
        document = request(compose, service, port, login, cookies,
                           {fields["login_id"]: login_id, fields["password"]: password})
        check_expectations(document, login["expect"], role)
        if login["session"] != "cookie" or not cookies.get("JSESSIONID"):
            raise VerificationError("session cookie required")
        identity = contract["identity"]
        check_expectations(request(compose, service, port, identity, cookies), identity["expect"], role)
    menu = contract["first_menu"]
    check_expectations(request(compose, service, port, menu, cookies), menu["expect"], role)
    check_expectations(request(compose, "frontend", 8080, menu, cookies), menu["expect"], role)
    route = menu["route"]
    if not re.fullmatch(r"/UI-[0-9]+", route):
        raise VerificationError("invalid menu route")
    run(compose + ["exec", "-T", "frontend", "wget", "-q", "-T", "15", "-O", "/dev/null",
                   "http://127.0.0.1:8080" + route])


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", help="Compose project to verify")
    args = parser.parse_args(argv)
    try:
        verify(args.project)
    except (VerificationError, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
        print("runtime_check=FAIL (boot, health, authentication, identity, menu or proxy contract)")
        return 1
    print("runtime_check=PASS boot=healthy health=PASS authentication=PASS identity=PASS first_menu=PASS proxy=PASS route=PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
