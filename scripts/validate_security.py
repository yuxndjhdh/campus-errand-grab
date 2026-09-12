"""Exercise the local security surface and emit a redacted acceptance report."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests


ROOT = Path(__file__).resolve().parents[1]


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def call(session: requests.Session, method: str, url: str, **kwargs: Any) -> tuple[int, dict[str, str], Any]:
    response = session.request(method, url, timeout=15, **kwargs)
    try:
        body = response.json()
    except ValueError:
        body = None
    return response.status_code, dict(response.headers), body


def login(session: requests.Session, base: str, username: str, password: str) -> str:
    status, _, body = call(session, "POST", base + "/api/auth/login", json={"username": username, "password": password})
    if status != 200:
        raise RuntimeError(f"login failed for test identity: HTTP {status}")
    token = (body or {}).get("data", {}).get("accessToken")
    if not token:
        raise RuntimeError("login response did not contain a token")
    return str(token)


def register(session: requests.Session, base: str, username: str, password: str) -> None:
    status, _, _ = call(session, "POST", base + "/api/auth/register", json={
        "username": username, "nickname": "security-validation", "password": password,
    })
    if status != 200:
        raise RuntimeError(f"registration failed: HTTP {status}")


def cleanup(container: str, username: str) -> None:
    sql = (
        "SET FOREIGN_KEY_CHECKS=0; "
        "DELETE FROM t_ledger_entry WHERE user_id IN (SELECT id FROM t_user WHERE username='" + username + "'); "
        "DELETE FROM t_idempotent_op WHERE op_key LIKE 'RECHARGE:security-validation-%'; "
        "DELETE FROM t_account WHERE user_id IN (SELECT id FROM t_user WHERE username='" + username + "'); "
        "DELETE FROM t_user WHERE username='" + username + "'; SET FOREIGN_KEY_CHECKS=1;"
    )
    subprocess.run([
        "docker", "exec", "-e", "MYSQL_PWD=change-me", container,
        "mysql", "-uroot", "-N", "-B", "campus_errand", "-e", sql,
    ], cwd=ROOT, capture_output=True, text=True, check=False)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.getenv("BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--mysql-container", default="campus-errand-grab-mysql-1")
    parser.add_argument("--output", default="reports/security/security-validation.json")
    args = parser.parse_args()
    base = args.base.rstrip("/")
    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)
    suffix = str(time.time_ns())
    user_name = f"security-validation-user-{suffix}"
    password = "security-validation-password"
    session = requests.Session()
    result: dict[str, Any] = {
        "status": "PASS",
        "startedAt": now(),
        "sensitiveValuesExcluded": True,
        "baseUrl": base,
        "productionStaticChecks": {
            "managementPortNotPublished": "ports: []" in (ROOT / "compose.production.yml").read_text(encoding="utf-8"),
            "productionManagementExposure": ["health", "info", "prometheus"],
            "productionProfile": "production" in (ROOT / "compose.production.yml").read_text(encoding="utf-8"),
        },
        "permissionMatrix": [],
        "loginAttack": {},
        "limitations": [
            "This report exercises the local Compose application and static production overlay; it is not a remote ingress or cloud IAM test.",
            "Dependency and image vulnerability status is emitted by generate_security_reports.py and enforced in CI by Trivy.",
        ],
    }
    try:
        register(session, base, user_name, password)
        user_token = login(session, base, user_name, password)
        admin_name = os.getenv("ADMIN_USERNAME", "admin")
        admin_password = os.getenv("ADMIN_PASSWORD", "change-me-admin")
        admin_token = login(session, base, admin_name, admin_password)
        headers = {
            "anonymous": {},
            "user": {"Authorization": f"Bearer {user_token}"},
            "admin": {"Authorization": f"Bearer {admin_token}"},
        }
        for method, path in [("GET", "/api/admin/stats"), ("GET", "/api/admin/recon/report"), ("GET", "/api/admin/outbox/dead")]:
            observed = {}
            for identity, identity_headers in headers.items():
                status, response_headers, _ = call(session, method, base + path, headers=identity_headers)
                observed[identity] = {"status": status, "traceIdReturned": bool(response_headers.get("X-Trace-Id"))}
            expected = {"anonymous": 401, "user": 403, "admin": 200}
            if any(observed[key]["status"] != value for key, value in expected.items()):
                raise AssertionError(f"permission matrix mismatch for {path}: {observed}")
            result["permissionMatrix"].append({"method": method, "path": path, "observed": observed, "expected": expected})

        management_expected = {
            "/actuator/health/readiness": {"anonymous": 200, "user": 200, "admin": 200},
            "/actuator/prometheus": {"anonymous": 200, "user": 200, "admin": 200},
            "/actuator/info": {"anonymous": 401, "user": 200, "admin": 200},
        }
        for path, expected in management_expected.items():
            observed = {}
            for identity, identity_headers in headers.items():
                status, response_headers, _ = call(session, "GET", base + path, headers=identity_headers)
                observed[identity] = {"status": status, "traceIdReturned": bool(response_headers.get("X-Trace-Id"))}
            if any(observed[key]["status"] != value for key, value in expected.items()):
                raise AssertionError(f"management matrix mismatch for {path}: {observed}")
            result["permissionMatrix"].append({"method": "GET", "path": path, "observed": observed, "expected": expected})

        failed_statuses = []
        trace_ids = []
        for _ in range(5):
            status, response_headers, _ = call(session, "POST", base + "/api/auth/login", json={
                "username": user_name, "password": "wrong-password",
            })
            failed_statuses.append(status)
            trace_ids.append(bool(response_headers.get("X-Trace-Id")))
        status, response_headers, _ = call(session, "POST", base + "/api/auth/login", json={
            "username": user_name, "password": "wrong-password",
        })
        failed_statuses.append(status)
        trace_ids.append(bool(response_headers.get("X-Trace-Id")))
        result["loginAttack"] = {
            "attempts": len(failed_statuses),
            "statusCodes": failed_statuses,
            "threshold": 5,
            "rateLimitedAttempts": sum(1 for item in failed_statuses[5:] if item == 429),
            "traceIdsPresent": all(trace_ids),
            "auditCorrelationBoundary": "traceId is returned and emitted through the request MDC; log sink correlation is not copied into this report",
        }
        if failed_statuses[:5] != [401] * 5 or failed_statuses[5] != 429 or not all(trace_ids):
            raise AssertionError(f"login rate-limit validation failed: {result['loginAttack']}")
    except Exception as exc:
        result["status"] = "FAIL"
        result["error"] = str(exc)
    finally:
        cleanup(args.mysql_container, user_name)
        result["finishedAt"] = now()
        output.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
        print(f"security_validation={output}")
        print(f"status={result['status']}")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
