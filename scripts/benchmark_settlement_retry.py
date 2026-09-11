"""Exercise one transient settlement failure and verify the scheduled retry."""

from __future__ import annotations

import argparse
import json
import os
from datetime import datetime
from pathlib import Path
from typing import Any

import requests

from failure_common import (
    ROOT,
    admin_token,
    create_order,
    db_connection,
    json_request,
    prometheus_metric,
    recharge,
    recon,
    register_and_login,
    require_test_environment,
    unique_key,
    utc_now,
    wait_until,
)


DEFAULT_OUTPUT = ROOT / "reports" / "benchmarks" / "raw" / (
    f"settlement-retry-{datetime.now().strftime('%Y%m%d-%H%M%S-%f')}.json"
)


def order_state(order_id: int) -> dict[str, Any]:
    connection = db_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT status,settlement_retry_count,settlement_dead FROM t_errand_order WHERE id=%s",
                (order_id,),
            )
            row = cursor.fetchone()
            if not row:
                raise RuntimeError(f"order {order_id} disappeared")
            dead_value = row[2]
            dead = dead_value not in (None, 0, False, "0", b"\x00", "\x00")
            return {"status": row[0], "retryCount": int(row[1]), "dead": dead}
    finally:
        connection.close()


def set_role(user_id: int, role: str) -> None:
    connection = db_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute("UPDATE t_user SET role=%s WHERE id=%s", (role, user_id))
    finally:
        connection.close()


def run(args: argparse.Namespace) -> dict[str, Any]:
    require_test_environment(args)
    session = requests.Session()
    _, publisher_token = register_and_login(session, args.base, "settlement-retry-publisher")
    taker_id, taker_token = register_and_login(session, args.base, "settlement-retry-taker")
    recharge(session, args.base, publisher_token, 10_000, unique_key("settlement-retry-recharge"))
    order_id = create_order(session, args.base, publisher_token, "settlement retry benchmark")
    status, payload, _ = json_request(
        session, "POST", args.base, f"/api/orders/{order_id}/grab", token=taker_token
    )
    if status != 200 or not payload.get("data", {}).get("won"):
        raise AssertionError(f"setup grab failed: HTTP {status} {payload}")
    retry_before = prometheus_metric(session, args.base, "settlement_retry_total")
    set_role(taker_id, "PLATFORM")
    try:
        status, payload, _ = json_request(
            session, "POST", args.base, f"/api/orders/{order_id}/deliver", token=taker_token
        )
        if status != 200 or payload.get("data", {}).get("status") != "DELIVERED":
            raise AssertionError(f"expected durable DELIVERED state after injected failure: HTTP {status} {payload}")
        delivered_state = order_state(order_id)
        if delivered_state["retryCount"] < 1:
            raise AssertionError(f"settlement failure did not record a retry: {delivered_state}")
        wait_until(
            lambda: prometheus_metric(session, args.base, "settlement_retry_total") > retry_before,
            timeout=args.timeout,
            interval=0.5,
        )
    finally:
        set_role(taker_id, "USER")

    def settled() -> bool:
        return order_state(order_id)["status"] == "SETTLED"

    wait_until(settled, timeout=args.timeout, interval=0.5)
    retry_after = prometheus_metric(session, args.base, "settlement_retry_total")
    final_state = order_state(order_id)
    reconciliation = recon(session, args.base, admin_token(session, args.base))
    if retry_after <= retry_before:
        raise AssertionError(f"settlement_retry_total did not increase: {retry_before} -> {retry_after}")
    if final_state["status"] != "SETTLED" or final_state["dead"]:
        raise AssertionError(f"settlement retry did not finish cleanly: {final_state}")
    if not reconciliation.get("passed"):
        raise AssertionError(f"reconciliation failed after settlement retry: {reconciliation}")
    return {
        "scenario": "settlement-retry",
        "orderId": order_id,
        "takerId": taker_id,
        "injectedFailure": "temporary taker role conflict during first settlement attempt",
        "deliveredState": delivered_state,
        "finalState": final_state,
        "retryMetricBefore": retry_before,
        "retryMetricAfter": retry_after,
        "reconciliation": reconciliation,
        "winnerInvariantPassed": True,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.getenv("BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--test-environment", action="store_true")
    parser.add_argument("--timeout", type=int, default=45)
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error("timeout must be positive")
    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)
    report: dict[str, Any] = {"scenario": "settlement-retry", "startedAt": utc_now()}
    try:
        report.update(run(args))
        report["passed"] = True
    except Exception as exc:
        report.update({"passed": False, "errorType": type(exc).__name__, "error": str(exc)})
    report["finishedAt"] = utc_now()
    report["benchmark"] = {
        "scenario": "settlement-retry",
        "command": ["python", "scripts/benchmark_settlement_retry.py"],
    }
    output.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True, indent=2))
    print(f"raw_report={output}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
