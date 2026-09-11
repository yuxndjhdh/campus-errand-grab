"""Run the Compose business smoke flow and write a redacted JSON report."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "reports" / "runtime" / "smoke-result.json"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class SmokeFailure(RuntimeError):
    pass


class SmokeRunner:
    def __init__(self, base: str, password: str, admin_username: str, admin_password: str) -> None:
        self.base = base.rstrip("/")
        self.password = password
        self.admin_username = admin_username
        self.admin_password = admin_password
        self.session = requests.Session()
        self.steps: list[dict[str, Any]] = []

    def request(self, method: str, path: str, body: dict[str, Any] | None = None,
                token: str | None = None) -> tuple[int, dict[str, Any], float]:
        headers = {"Content-Type": "application/json"}
        if token:
            headers["Authorization"] = f"Bearer {token}"
        started = time.perf_counter()
        response = self.session.request(
            method, self.base + path, json=body, headers=headers, timeout=15
        )
        elapsed_ms = (time.perf_counter() - started) * 1000
        try:
            payload = response.json()
        except ValueError:
            payload = {"code": "INVALID_JSON"}
        return response.status_code, payload, elapsed_ms

    def record(self, name: str, status: int, elapsed_ms: float, **details: Any) -> None:
        self.steps.append({
            "name": name,
            "httpStatus": status,
            "passed": status == 200,
            "elapsedMs": round(elapsed_ms, 3),
            **details,
        })

    def require(self, name: str, response: tuple[int, dict[str, Any], float]) -> dict[str, Any]:
        status, payload, elapsed_ms = response
        self.record(name, status, elapsed_ms)
        if status != 200:
            raise SmokeFailure(f"{name} failed with HTTP {status}: {payload}")
        return payload

    def register_login(self, prefix: str) -> tuple[int, str]:
        username = f"{prefix}-{time.time_ns()}"
        register = self.require(
            f"register:{prefix}",
            self.request("POST", "/api/auth/register", {
                "username": username,
                "nickname": prefix,
                "password": self.password,
            }),
        )
        user_id = int(register["data"]["id"])
        login = self.require(
            f"login:{prefix}",
            self.request("POST", "/api/auth/login", {
                "username": username,
                "password": self.password,
            }),
        )
        return user_id, str(login["data"]["accessToken"])

    def run(self) -> dict[str, Any]:
        _, publisher_token = self.register_login("smoke-publisher")
        taker_id, taker_token = self.register_login("smoke-taker")

        recharge = self.require(
            "recharge", self.request(
                "POST", "/api/users/me/recharge",
                {"amountCents": 100_000, "idemKey": f"smoke-recharge-{time.time_ns()}"},
                publisher_token,
            ),
        )
        if int(recharge["data"]["userId"]) <= 0:
            raise SmokeFailure("recharge response did not contain a valid user")

        order = self.require(
            "publish-order", self.request(
                "POST", "/api/orders",
                {"title": "smoke coffee", "detail": "library", "rewardCents": 560,
                 "claimTtlSeconds": 120},
                publisher_token,
            ),
        )
        order_id = int(order["data"]["id"])

        grab = self.require(
            "grab-order", self.request("POST", f"/api/orders/{order_id}/grab", token=taker_token)
        )
        if not grab["data"].get("won"):
            raise SmokeFailure(f"grab did not produce a winner: {grab}")

        delivered = self.require(
            "confirm-delivery",
            self.request("POST", f"/api/orders/{order_id}/deliver", token=taker_token),
        )
        final_status = delivered["data"].get("status")
        if final_status != "SETTLED":
            raise SmokeFailure(f"delivery did not settle order: {delivered}")

        admin_login = self.require(
            "login:admin",
            self.request("POST", "/api/auth/login", {
                "username": self.admin_username,
                "password": self.admin_password,
            }),
        )
        admin_token = str(admin_login["data"]["accessToken"])
        reconciliation = self.require(
            "reconciliation",
            self.request("POST", "/api/admin/recon/run", {}, admin_token),
        )
        stats = self.require(
            "admin-stats", self.request("GET", "/api/admin/stats", token=admin_token)
        )

        recon_data = reconciliation["data"]
        invariants = recon_data.get("invariants", {})
        if recon_data.get("passed") is not True or len(invariants) != 5 or not all(invariants.values()):
            raise SmokeFailure(f"reconciliation invariants failed: {recon_data}")

        return {
            "status": "PASS",
            "startedAt": self.started_at,
            "finishedAt": utc_now(),
            "baseUrl": self.base,
            "command": "python scripts/smoke_validation.py",
            "steps": self.steps,
            "order": {"status": final_status, "winnerUserId": taker_id},
            "reconciliation": recon_data,
            "adminStats": stats["data"],
            "sensitiveValuesExcluded": True,
        }

    def execute(self) -> dict[str, Any]:
        self.started_at = utc_now()
        try:
            return self.run()
        except (SmokeFailure, requests.RequestException, KeyError, TypeError, ValueError) as exc:
            return {
                "status": "FAIL",
                "startedAt": self.started_at,
                "finishedAt": utc_now(),
                "baseUrl": self.base,
                "command": "python scripts/smoke_validation.py",
                "steps": self.steps,
                "error": str(exc),
                "sensitiveValuesExcluded": True,
            }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.getenv("BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    parser.add_argument("--password", default=os.getenv("SMOKE_PASSWORD", "smoke-password-123"))
    parser.add_argument("--admin-username", default=os.getenv("ADMIN_USERNAME", "admin"))
    parser.add_argument("--admin-password", default=os.getenv("ADMIN_PASSWORD", "change-me-admin"))
    args = parser.parse_args()

    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)
    report = SmokeRunner(args.base, args.password, args.admin_username, args.admin_password).execute()
    output.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(f"smoke_report={output}")
    print(f"status={report['status']}")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
