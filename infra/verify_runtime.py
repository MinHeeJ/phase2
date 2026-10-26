"""Read-only Compose boot/menu verification; never emit response bodies or headers."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


class VerificationError(Exception):
    pass


def run(command):
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
    if result.returncode:
        raise VerificationError("runtime command failed")
    return result.stdout


def check_expectations(document, expectations):
    for expected in expectations:
        value = document
        for key in expected["path"].split("."):
            if not isinstance(value, dict) or key not in value:
                raise VerificationError("response contract mismatch")
            value = value[key]
        if expected["op"] == "equals":
            matches = type(value) is type(expected["value"]) and value == expected["value"]
        elif expected["op"] == "type" and expected["value"] == "array":
            matches = isinstance(value, list)
        else:
            raise VerificationError("unsupported response expectation")
        if not matches:
            raise VerificationError("response contract mismatch")


def verify(project=None):
    contract = json.loads((ROOT / "aiops.runtime-check.json").read_text())
    if contract["auth"]["required"]:
        raise VerificationError("authenticated verification is not supported")
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
    if not services or any(name not in states or states[name].get("State") != "running"
                           or states[name].get("Health") != "healthy" for name in services):
        raise VerificationError("APP_BOOT_NOT_READY")
    backend = contract["backend"]
    service = backend["service"]
    port = backend["container_port"]
    if service not in services or not isinstance(port, int) or not 1 <= port <= 65535:
        raise VerificationError("invalid backend contract")
    for operation in (backend["health"], contract["first_menu"]):
        path = operation["path"]
        if operation["method"] != "GET" or not re.fullmatch(r"/api/[A-Za-z0-9/_-]+", path):
            raise VerificationError("invalid read-only operation")
        # Capture and inspect only in memory. Never forward stdout/stderr from a request.
        response = run(compose + ["exec", "-T", service, "curl", "--fail", "--silent",
                                  "--max-time", "15", f"http://127.0.0.1:{port}{path}"])
        check_expectations(json.loads(response), operation["expect"])
    menu = contract["first_menu"]
    response = run(compose + ["exec", "-T", "frontend", "wget", "-q", "-T", "15", "-O", "-",
                              "http://127.0.0.1:8080" + menu["path"]])
    check_expectations(json.loads(response), menu["expect"])
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
        # Exceptions may contain response content, arguments or provider diagnostics.
        print("runtime_check=FAIL (boot, health, menu or proxy contract)")
        return 1
    print("runtime_check=PASS boot=healthy health=PASS first_menu=PASS proxy=PASS route=PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
