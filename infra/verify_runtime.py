"""Compose application smoke check; credentials and HTTP content stay in memory."""
import argparse
import json
from http.cookies import SimpleCookie
import os
from pathlib import Path
import re
import secrets
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


class VerificationError(Exception):
    pass


def run(command, *, input=None, environment=None, timeout=30):
    # Only the explicit startup call may forward the ephemeral account slots.
    # In particular, `compose config` must not resolve them into its output.
    if environment is None:
        environment = {key: value for key, value in os.environ.items() if key not in SLOTS}
    result = subprocess.run(command, input=input, env=environment,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
    if result.returncode:
        raise VerificationError("runtime command failed")
    return result.stdout


def check_expectations(document, expectations, role=None):
    if not isinstance(document, (dict, list)) or (isinstance(document, dict) and document.get("success") is False):
        raise VerificationError("response contract mismatch")
    for expected in expectations:
        value = document
        for key in expected["path"].split("."):
            if not isinstance(value, dict) or key not in value:
                raise VerificationError("response contract mismatch")
            value = value[key]
        wanted = expected.get("value")
        if wanted == "$test_role":
            wanted = role
        if expected["op"] == "exists":
            matches = True
        elif expected["op"] == "equals":
            matches = type(value) is type(wanted) and value == wanted
        elif expected["op"] == "contains":
            matches = isinstance(value, list) and any(type(item) is type(wanted) and item == wanted for item in value)
        elif expected["op"] == "type":
            types = {"string": str, "number": (int, float), "boolean": bool, "object": dict, "array": list}
            matches = (isinstance(value, list) and all(isinstance(item, str) for item in value)
                       if wanted == "array_of_string" else
                       wanted in types and isinstance(value, types[wanted]) and not (wanted == "number" and isinstance(value, bool)))
        else:
            raise VerificationError("unsupported response expectation")
        if not matches:
            raise VerificationError("response contract mismatch")


SLOTS = ("AIOPS_SMOKE_LOGIN_ID", "AIOPS_SMOKE_LOGIN_PASSWORD", "AIOPS_SMOKE_ROLE_CODES")


def credentials(environment, role, create=False):
    values = [environment.get(name) for name in SLOTS]
    if create and not any(values):
        values = ["smoke-" + secrets.token_hex(12), secrets.token_urlsafe(32) + "Aa9!", role]
        environment.update(zip(SLOTS, values))
    if not all(values) or role not in [item.strip() for item in values[2].split(",")]:
        raise VerificationError("APP_SMOKE_CREDENTIALS_UNAVAILABLE")
    return values[0], values[1]


def request(compose, service, url, method="GET", body=None, cookies=None, headers=None, json_response=True):
    # curl configuration travels on stdin, never in process arguments or a file.
    options = {"url": url, "request": method}
    if body is not None:
        options["data"] = json.dumps(body, ensure_ascii=False)
    config = "".join(key + " = " + json.dumps(value, ensure_ascii=False) + "\n" for key, value in options.items())
    request_headers = {"Content-Type": "application/json", **(headers or {})}
    if cookies:
        request_headers["Cookie"] = "; ".join(key + "=" + value for key, value in cookies.items())
    config += "".join("header = " + json.dumps(key + ": " + value) + "\n" for key, value in request_headers.items())
    raw = run(compose + ["exec", "-T", service, "curl", "--silent", "--show-error", "--max-time", "15",
                         "--dump-header", "-", "--config", "-"], input=config.encode("utf-8"))
    head, separator, payload = raw.partition(b"\r\n\r\n")
    lines = head.decode("iso-8859-1").splitlines()
    if not separator or not lines or not 200 <= int(lines[0].split()[1]) < 300:
        raise VerificationError("HTTP contract mismatch")
    response_headers = {}
    for line in lines[1:]:
        key, _, value = line.partition(":")
        response_headers[key.lower()] = value.strip()
        if key.lower() == "set-cookie" and cookies is not None:
            parsed = SimpleCookie(); parsed.load(value.strip())
            for name, item in parsed.items():
                if item["max-age"] == "0":
                    cookies.pop(name, None)
                else:
                    cookies[name] = item.value
    if not json_response:
        if not payload:
            raise VerificationError("empty route")
        return None
    if response_headers.get("content-type", "").split(";")[0].strip() != "application/json":
        raise VerificationError("JSON required")
    document = json.loads(payload)
    if isinstance(document, dict) and document.get("success") is False:
        raise VerificationError("error envelope")
    return document


def verify(project=None, start=False):
    contract = json.loads((ROOT / "aiops.runtime-check.json").read_text())
    compose = ["docker", "compose", "-f", str(ROOT / "infra/docker-compose.yml")]
    if project:
        compose += ["--project-name", project]
    environment = os.environ.copy()
    auth = contract["auth"]
    login = credentials(environment, auth["test_role"], create=start) if auth["required"] else None
    if start:
        if not project or run(compose + ["ps", "--all", "--quiet"]).strip():
            raise VerificationError("start requires a new isolated project")
        if run(["docker", "volume", "ls", "--quiet", "--filter", "label=com.docker.compose.project=" + project]).strip():
            raise VerificationError("start requires empty isolated volumes")
        environment.update(FRONTEND_PORT="0", BACKEND_PORT="0", AI_OLLAMA_ENABLED="false")
        try:
            run(compose + ["up", "--no-build", "--wait", "--wait-timeout", "180", "db", "backend", "frontend"],
                environment=environment, timeout=240)
            verify_running(compose, contract, login)
        finally:
            cleanup_environment = {key: value for key, value in environment.items() if key not in SLOTS}
            run(compose + ["down", "--volumes"], environment=cleanup_environment, timeout=90)
        return
    verify_running(compose, contract, login)


def verify_running(compose, contract, login):
    operations = [(contract["backend"]["health"], "GET"), (contract["first_menu"], "GET")]
    if contract["auth"]["required"]:
        operations += [(contract["auth"]["login"], "POST"), (contract["identity"], "GET")]
    for operation, method in operations:
        if (operation["method"] != method or len(operation["path"]) > 200
                or not re.fullmatch(r"/api/[A-Za-z0-9/_-]+", operation["path"])):
            raise VerificationError("invalid operation contract")
    services = json.loads(run(compose + ["config", "--format", "json"]))["services"]
    raw = run(compose + ["ps", "--all", "--format", "json"]).decode()
    try:
        rows = json.loads(raw)
        rows = rows if isinstance(rows, list) else [rows]
    except json.JSONDecodeError:
        rows = [json.loads(line) for line in raw.splitlines() if line.strip()]
    states = {row["Service"]: row for row in rows}
    if not services or any(name not in states or states[name].get("State") != "running"
                           or states[name].get("Health") != "healthy" for name in ("db", "backend", "frontend")):
        raise VerificationError("APP_BOOT_NOT_READY")
    backend = contract["backend"]
    service = backend["service"]
    port = backend["container_port"]
    if service not in services or not isinstance(port, int) or not 1 <= port <= 65535:
        raise VerificationError("invalid backend contract")
    base = f"http://127.0.0.1:{port}"
    health = backend["health"]
    check_expectations(request(compose, service, base + health["path"]), health["expect"])
    cookies = {}
    auth = contract["auth"]
    headers = {}
    if auth["required"]:
        operation = auth["login"]
        # Preserve the application's CSRF protection, including on login.
        csrf = request(compose, service, base + "/api/auth/csrf", cookies=cookies)["data"]
        fields = operation["body_fields"]
        document = request(compose, service, base + operation["path"], method="POST",
                           body={fields["login_id"]: login[0], fields["password"]: login[1]},
                           cookies=cookies, headers={csrf["headerName"]: csrf["csrfToken"]})
        check_expectations(document, operation["expect"], auth["test_role"])
        if operation["session"] == "bearer":
            token = document
            for key in operation["bearer_json_path"].split("."):
                token = token[key]
            headers["Authorization"] = "Bearer " + token
        identity = contract["identity"]
        check_expectations(request(compose, service, base + identity["path"], cookies=cookies, headers=headers),
                           identity["expect"], auth["test_role"])
    menu = contract["first_menu"]
    for origin in (base, "http://frontend:8080"):
        check_expectations(request(compose, service, origin + menu["path"], cookies=cookies, headers=headers),
                           menu["expect"], auth.get("test_role"))
    route = menu["route"]
    if not re.fullmatch(r"/UI-[0-9]+", route):
        raise VerificationError("invalid menu route")
    request(compose, service, "http://frontend:8080" + route, json_response=False)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", help="Compose project to verify")
    parser.add_argument("--start", action="store_true", help="Start and remove a new isolated project with ephemeral smoke credentials")
    args = parser.parse_args(argv)
    # A project name is also used for fresh QA runs. With no platform slots,
    # bootstrap that isolated project instead of failing before startup. verify()
    # rejects existing containers AND volumes before any startup or cleanup, so
    # this never reseeds an existing deployment or substitutes guide accounts.
    slots_absent = not any(os.environ.get(name) for name in SLOTS)
    start = args.start or args.project is None or slots_absent
    project = args.project or "sicost-smoke-" + secrets.token_hex(8)
    try:
        verify(project, start)
    except (VerificationError, OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired):
        # Exceptions may contain response content, arguments or provider diagnostics.
        print("runtime_check=FAIL (boot, health, menu or proxy contract)")
        return 1
    print("runtime_check=PASS boot=healthy health=PASS identity=PASS first_menu=PASS proxy=PASS route=PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
