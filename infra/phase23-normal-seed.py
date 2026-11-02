"""Create a legitimate October CLOSE and leave November OPEN in an isolated preview."""

import json
import sys
from urllib.error import HTTPError
from urllib.request import Request, urlopen


def call(base: str, method: str, path: str, body: dict | None = None) -> dict:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    request = Request(
        base + path,
        data=payload,
        headers={"Content-Type": "application/json"},
        method=method,
    )
    try:
        with urlopen(request, timeout=30) as response:
            result = json.load(response)
    except HTTPError as error:
        raise RuntimeError(f"{method} {path}: HTTP {error.code}: {error.read().decode()}") from error
    if not result.get("success"):
        raise RuntimeError(f"{method} {path}: {result.get('code')}: {result.get('message')}")
    return result["data"]


def main(base: str) -> None:
    october = call(base, "GET", "/api/closing?month=2026-10")
    if october["status"] != "OPEN":
        raise RuntimeError("The isolated database must begin with October OPEN")
    call(base, "PUT", "/api/finance/tax/plan?year=2026", {
        "annual_amount": 1200000, "reason": "phase-23 isolated normal preview",
    })
    call(base, "PUT", "/api/finance/cashflow/opening?month=2026-10", {
        "month": "2026-10", "amount": 1000000,
    })
    call(base, "POST", "/api/finance/entries", {
        "entry_ym": "2026-10", "fin_se_cd": "SGA", "item_nm": "10월 운영비 예시",
        "entry_amt": 20000, "vat_amt": 0, "inout_dt": "2026-10-15",
        "inout_cmpl_yn": "Y",
    })
    checked = call(base, "POST", "/api/closing/check?month=2026-10")
    if len(checked["checks"]) != 9 or any(row["status"] == "FAIL" for row in checked["checks"]):
        raise RuntimeError("October has a failed or incomplete closing check")
    call(base, "GET", "/api/closing/preview?month=2026-10")
    confirmed = call(base, "POST", "/api/closing/confirm", {
        "month": "2026-10", "ackWarnings": True,
    })
    if confirmed["status"] != "CLOSED":
        raise RuntimeError("October was not closed")
    if call(base, "GET", "/api/closing?month=2026-10")["status"] != "CLOSED":
        raise RuntimeError("October CLOSE snapshot could not be read")
    history = call(base, "GET", "/api/closing/history?year=2026&page=0&size=20")
    if history["total"] != 1 or history["items"][0]["action"] != "CLOSE":
        raise RuntimeError("October CLOSE history is incomplete")
    call(base, "POST", "/api/finance/entries", {
        "entry_ym": "2026-11", "fin_se_cd": "NOI", "item_nm": "11월 수입 예시",
        "entry_amt": 30000, "vat_amt": 0, "inout_dt": "2026-11-01",
        "inout_cmpl_yn": "Y",
    })
    november = call(base, "POST", "/api/closing/check?month=2026-11")
    if len(november["checks"]) != 9 or any(row["status"] == "FAIL" for row in november["checks"]):
        raise RuntimeError("November has a failed or incomplete closing check")
    call(base, "GET", "/api/closing/preview?month=2026-11")
    print("Ready: 2026-10 CLOSED with snapshots/history; 2026-11 OPEN with 9 checks and close preview")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: phase23-normal-seed.py <isolated-preview-base-url>")
    main(sys.argv[1].rstrip("/"))
