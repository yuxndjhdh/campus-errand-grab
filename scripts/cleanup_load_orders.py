"""Cancel only generated load-test orders through the real business API."""

from __future__ import annotations

import argparse
import json
import os
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import pymysql
import requests


ROOT = Path(__file__).resolve().parents[1]


def db_connection():
    return pymysql.connect(
        host=os.getenv("DB_HOST", "127.0.0.1"),
        port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USERNAME", "root"),
        password=os.getenv("DB_PASSWORD", ""),
        database=os.getenv("DB_NAME", "campus_errand"),
        autocommit=True,
    )


def generated_orders() -> dict[str, list[tuple[int, str]]]:
    connection = db_connection()
    try:
        with connection.cursor() as cursor:
            cursor.execute(
                "SELECT o.id,u.username FROM t_errand_order o "
                "JOIN t_user u ON u.id=o.publisher_id "
                "WHERE u.username LIKE 'load-publisher-%' "
                "AND o.status IN ('PUBLISHED','TAKEN') "
                "AND (o.title LIKE 'load %' OR o.title LIKE 'mixed-%' OR o.title LIKE 'sustained-%') "
                "ORDER BY o.id"
            )
            grouped: dict[str, list[tuple[int, str]]] = {}
            for order_id, username in cursor.fetchall():
                grouped.setdefault(str(username), []).append((int(order_id), str(username)))
            return grouped
    finally:
        connection.close()


def cancel_publisher(args: argparse.Namespace, item: tuple[str, list[tuple[int, str]]]) -> dict[str, Any]:
    username, orders = item
    session = requests.Session()
    login = session.post(args.base + "/api/auth/login", json={
        "username": username,
        "password": args.load_password,
    }, timeout=15)
    if login.status_code != 200:
        return {"username": username, "orders": len(orders), "cancelled": 0, "failed": len(orders), "error": login.text[:300]}
    token = login.json()["data"]["accessToken"]
    cancelled = 0
    failed: list[dict[str, Any]] = []
    for order_id, _ in orders:
        response = session.post(
            args.base + f"/api/orders/{order_id}/cancel",
            json={},
            headers={"Authorization": f"Bearer {token}"},
            timeout=15,
        )
        if response.status_code == 200:
            cancelled += 1
        else:
            failed.append({"orderId": order_id, "status": response.status_code, "body": response.text[:300]})
    return {"username": username, "orders": len(orders), "cancelled": cancelled, "failed": len(failed), "failures": failed[:10]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.getenv("BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--test-environment", action="store_true")
    parser.add_argument("--workers", type=int, default=16)
    parser.add_argument("--load-password", default=os.getenv("LOAD_PASSWORD", "load-password-123"))
    parser.add_argument("--output", default="reports/runtime/load-cleanup.json")
    args = parser.parse_args()
    if not args.test_environment:
        parser.error("refusing to mutate orders without --test-environment")
    if args.workers < 1:
        parser.error("workers must be positive")
    grouped = generated_orders()
    with ThreadPoolExecutor(max_workers=min(args.workers, max(1, len(grouped)))) as pool:
        results = list(pool.map(lambda item: cancel_publisher(args, item), grouped.items()))
    remaining = generated_orders()
    report = {
        "scenario": "load-order-cleanup",
        "startedAt": datetime.now(timezone.utc).isoformat(),
        "publishers": len(grouped),
        "ordersSelected": sum(len(orders) for orders in grouped.values()),
        "ordersCancelled": sum(int(result.get("cancelled", 0)) for result in results),
        "requestFailures": sum(int(result.get("failed", 0)) for result in results),
        "remainingOpenOrders": sum(len(orders) for orders in remaining.values()),
        "publisherResults": results,
    }
    report["passed"] = report["remainingOpenOrders"] == 0
    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True, indent=2))
    print(f"cleanup_report={output}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
