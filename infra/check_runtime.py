"""Exercise this public app's runtime contract without recording response bodies.

Only loopback requests are made. Redirects, cookies, error envelopes, non-JSON
API responses and oversized responses are rejected. No request/response data or
exception details are printed, including on failure.
"""
import argparse
import json
from pathlib import Path
import re
import sys
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

ROOT = Path(__file__).resolve().parents[1]


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def require(condition):
    if not condition:
        raise ValueError("Runtime check failed")


def safe_path(value):
    require(isinstance(value, str) and len(value) <= 200)
    require(re.fullmatch(r"/[A-Za-z0-9/_-]*", value) is not None)
    return value


def validate(contract):
    require(set(contract) == {"schema_version", "backend", "auth", "first_menu"})
    require(contract["schema_version"] == 1)
    require(contract["auth"] == {"required": False})
    backend = contract["backend"]
    require(set(backend) == {"service", "container_port", "health"})
    require(backend["service"] == "backend" and backend["container_port"] == 8080)
    for operation, extra in [(backend["health"], set()), (contract["first_menu"], {"route"})]:
        require(set(operation) == {"operation_id", "method", "path", "expect"} | extra)
        require(operation["method"] == "GET")
        safe_path(operation["path"])
        require(isinstance(operation["expect"], list) and 1 <= len(operation["expect"]) <= 6)
        for assertion in operation["expect"]:
            require(set(assertion) <= {"path", "op", "value"})
            require(re.fullmatch(r"[A-Za-z_]\w*(\.[A-Za-z_]\w*){0,7}", assertion["path"]) is not None)
            require(assertion["op"] in {"exists", "equals", "type", "contains"})
            require(("value" in assertion) == (assertion["op"] != "exists"))
    safe_path(contract["first_menu"]["route"])


def check_expectations(payload, expectations):
    require(isinstance(payload, dict) and payload.get("success") is True)
    require(not any(key in payload for key in ("error", "errors", "code")))
    for assertion in expectations:
        value = payload
        for part in assertion["path"].split("."):
            require(isinstance(value, dict) and part in value)
            value = value[part]
        op = assertion["op"]
        wanted = assertion.get("value")
        if op == "equals":
            require(type(value) is type(wanted) and value == wanted)
        elif op == "contains":
            require(isinstance(value, list) and any(type(item) is type(wanted) and item == wanted for item in value))
        elif op == "type":
            types = {"string": str, "number": (int, float), "boolean": bool, "object": dict, "array": list}
            if wanted == "array_of_string":
                require(isinstance(value, list) and all(isinstance(item, str) for item in value))
            else:
                require(wanted in types and isinstance(value, types[wanted]))
                require(wanted != "number" or not isinstance(value, bool))


def fetch(port, path, json_response=True):
    opener = build_opener(ProxyHandler({}), NoRedirect())
    request = Request(f"http://127.0.0.1:{port}{safe_path(path)}", method="GET")
    with opener.open(request, timeout=15) as response:
        require(200 <= response.status < 300)
        require(response.headers.get("Set-Cookie") is None)
        if json_response:
            require(response.headers.get_content_type() == "application/json")
        else:
            require(response.headers.get_content_type() == "text/html")
        body = response.read(1024 * 1024 + 1)
        require(0 < len(body) <= 1024 * 1024)
        return json.loads(body) if json_response else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend-port", type=int, default=8080)
    parser.add_argument("--frontend-port", type=int, default=3000)
    args = parser.parse_args()
    stage = "contract"
    try:
        require(all(1 <= port <= 65535 for port in (args.backend_port, args.frontend_port)))
        raw = (ROOT / "aiops.runtime-check.json").read_bytes()
        require(len(raw) <= 65536)
        contract = json.loads(raw.decode("utf-8"))
        validate(contract)
        for stage, operation in [("health", contract["backend"]["health"]), ("first_menu", contract["first_menu"])]:
            check_expectations(fetch(args.backend_port, operation["path"]), operation["expect"])
            # Check the browser's same-origin API proxy as well as the backend.
            check_expectations(fetch(args.frontend_port, operation["path"]), operation["expect"])
            print(f"{stage}: PASS")
        stage = "route"
        fetch(args.frontend_port, contract["first_menu"]["route"], json_response=False)
        print("route: PASS")
        print("app runtime check: PASS")
        return 0
    except Exception:
        print(f"app runtime check: FAIL ({stage})", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
