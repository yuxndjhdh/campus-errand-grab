"""Shared helpers for local reliability failure demonstrations."""

from __future__ import annotations

import argparse
import json
import os
import secrets
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

import pymysql
import requests


ROOT = Path(__file__).resolve().parents[1]
REPORT_DIR = ROOT / "reports" / "failure-tests"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def add_common_arguments(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--base", default=os.getenv("BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--compose-file", default=os.getenv("COMPOSE_FILE", "compose.yml"))
    parser.add_argument(
        "--test-environment",
        action="store_true",
        help="confirm that this command targets a disposable test environment",
    )


def require_test_environment(args: argparse.Namespace, require_test_database: bool = False) -> None:
    if not args.test_environment:
        raise RuntimeError("refusing to mutate infrastructure without --test-environment")
    if require_test_database:
        database = os.getenv("DB_NAME", "campus_errand")
        if "test" not in database.lower():
            raise RuntimeError("reconciliation drift requires DB_NAME containing 'test'")


def json_request(
    session: requests.Session,
    method: str,
    base: str,
    path: str,
    body: dict[str, Any] | None = None,
    token: str | None = None,
    timeout: float = 10,
) -> tuple[int, dict[str, Any], float]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    started = time.perf_counter()
    response = session.request(method, base + path, json=body, headers=headers, timeout=timeout)
    elapsed_ms = (time.perf_counter() - started) * 1000
    try:
        payload = response.json()
    except ValueError:
        payload = {"code": "INVALID_JSON", "body": response.text[:500]}
    return response.status_code, payload, elapsed_ms


def register_and_login(session: requests.Session, base: str, prefix: str) -> tuple[int, str]:
    suffix = secrets.token_hex(8)
    username = f"failure-{prefix}-{suffix}"
    password = os.getenv("FAILURE_TEST_PASSWORD", "failure-test-password")
    status, payload, _ = json_request(
        session,
        "POST",
        base,
        "/api/auth/register",
        {"username": username, "nickname": prefix, "password": password},
    )
    if status != 200:
        raise RuntimeError(f"registration failed: HTTP {status} {payload}")
    status, payload, _ = json_request(
        session, "POST", base, "/api/auth/login", {"username": username, "password": password}
    )
    if status != 200:
        raise RuntimeError(f"login failed: HTTP {status} {payload}")
    return int(payload["data"]["userId"]), payload["data"]["accessToken"]


def recharge(session: requests.Session, base: str, token: str, amount: int, key: str) -> None:
    status, payload, _ = json_request(
        session,
        "POST",
        base,
        "/api/users/me/recharge",
        {"amountCents": amount, "idemKey": key},
        token,
    )
    if status != 200:
        raise RuntimeError(f"recharge failed: HTTP {status} {payload}")


def create_order(session: requests.Session, base: str, token: str, title: str, reward: int = 1000) -> int:
    status, payload, _ = json_request(
        session,
        "POST",
        base,
        "/api/orders",
        {"title": title, "rewardCents": reward, "claimTtlSeconds": 120},
        token,
    )
    if status != 200:
        raise RuntimeError(f"order creation failed: HTTP {status} {payload}")
    return int(payload["data"]["id"])


def admin_token(session: requests.Session, base: str) -> str:
    username = os.getenv("ADMIN_USERNAME", "admin")
    password = os.environ["ADMIN_PASSWORD"]
    status, payload, _ = json_request(
        session, "POST", base, "/api/auth/login", {"username": username, "password": password}
    )
    if status != 200:
        raise RuntimeError(f"admin login failed: HTTP {status} {payload}")
    return payload["data"]["accessToken"]


def recon(session: requests.Session, base: str, token: str) -> dict[str, Any]:
    status, payload, _ = json_request(session, "POST", base, "/api/admin/recon/run", {}, token)
    if status != 200:
        raise RuntimeError(f"reconciliation request failed: HTTP {status} {payload}")
    return payload["data"]


def db_connection(database: str | None = None):
    return pymysql.connect(
        host=os.getenv("DB_HOST", "127.0.0.1"),
        port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USERNAME", "root"),
        password=os.getenv("DB_PASSWORD", ""),
        database=database or os.getenv("DB_NAME", "campus_errand"),
        autocommit=True,
    )


def compose(compose_file: str, *arguments: str) -> str:
    result = subprocess.run(
        ["docker", "compose", "-f", compose_file, *arguments],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def redis_cli(compose_file: str, *arguments: str) -> str:
    return compose(compose_file, "exec", "-T", "redis", "redis-cli", *arguments).strip()


def wait_until(predicate: Callable[[], bool], timeout: float = 30, interval: float = 0.5) -> None:
    deadline = time.monotonic() + timeout
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except Exception as exc:  # retain the last transient failure for a useful final error
            last_error = exc
        time.sleep(interval)
    if last_error:
        raise TimeoutError(f"condition did not become true: {last_error}") from last_error
    raise TimeoutError("condition did not become true before timeout")


def write_report(name: str, report: dict[str, Any]) -> Path:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    path = REPORT_DIR / f"{name}-{stamp}.json"
    path.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    return path


def run_scenario(name: str, callback: Callable[[], dict[str, Any]]) -> int:
    started = utc_now()
    report: dict[str, Any] = {"scenario": name, "startedAt": started}
    try:
        report.update(callback())
        report["passed"] = True
    except Exception as exc:
        report.update({"passed": False, "errorType": type(exc).__name__, "error": str(exc)})
    report["finishedAt"] = utc_now()
    path = write_report(name, report)
    print(json.dumps(report, ensure_ascii=True, indent=2))
    print(f"report={path}")
    return 0 if report["passed"] else 1
