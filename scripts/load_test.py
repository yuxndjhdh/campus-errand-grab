"""Reproducible HTTP load scenarios for the authenticated API.

The script records raw JSON results under reports/ and deliberately does not import
redis. Redis is an implementation detail; correctness is checked through HTTP and
the reconciliation endpoint.
"""
import argparse
import json
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import pymysql
import requests


def post(base, path, body=None, token=None, timeout=10):
    start = time.perf_counter()
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    response = requests.post(base + path, json=body, headers=headers, timeout=timeout)
    elapsed = (time.perf_counter() - start) * 1000
    try:
        payload = response.json()
    except ValueError:
        payload = {"code": "INVALID_JSON"}
    return response.status_code, payload, elapsed


def register_and_login(base, prefix):
    suffix = str(time.time_ns())
    username = f"{prefix}-{suffix}"
    password = os.getenv("LOAD_PASSWORD", "load-password-123")
    register = post(base, "/api/auth/register", {"username": username, "nickname": prefix, "password": password})
    if register[0] != 200:
        raise RuntimeError(f"register failed: {register}")
    login = post(base, "/api/auth/login", {"username": username, "password": password})
    if login[0] != 200:
        raise RuntimeError(f"login failed: {login}")
    return register[1]["data"]["id"], login[1]["data"]["accessToken"]


def setup(base):
    publisher, publisher_token = register_and_login(base, "load-publisher")
    post(base, "/api/users/me/recharge", {"amountCents": 10_000_000, "idemKey": f"load-recharge-{time.time_ns()}"}, publisher_token)
    taker, taker_token = register_and_login(base, "load-taker")
    return publisher, publisher_token, taker, taker_token


def burst(base, clients):
    _, publisher_token, _, taker_token = setup(base)
    order = post(base, "/api/orders", {
        "title": "load burst", "rewardCents": 1000, "claimTtlSeconds": 120,
    }, publisher_token)[1]["data"]["id"]
    barrier = threading.Barrier(clients)

    def call(_):
        barrier.wait()
        return post(base, f"/api/orders/{order}/grab", token=taker_token)

    before = mysql_updates()
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=clients) as pool:
        results = list(pool.map(call, range(clients)))
    duration_ms = (time.perf_counter() - started) * 1000
    after = mysql_updates()
    latencies = sorted(item[2] for item in results)
    successes = sum(1 for status, payload, _ in results if status == 200 and payload.get("data", {}).get("won"))
    report = {
        "scenario": "burst", "clients": clients, "durationMs": duration_ms,
        "throughputRps": clients / (duration_ms / 1000), "successes": successes,
        "rejected": clients - successes, "latencyMs": latency_summary(latencies),
        "dbUpdateDelta": after - before,
    }
    print(json.dumps(report, indent=2))
    return report


def mixed(base, orders_count, clients_per_order):
    _, publisher_token, _, taker_token = setup(base)
    orders = []
    for index in range(orders_count):
        response = post(base, "/api/orders", {
            "title": f"mixed-{index}", "rewardCents": 1000, "claimTtlSeconds": 120,
        }, publisher_token)
        orders.append(response[1]["data"]["id"])
    with ThreadPoolExecutor(max_workers=orders_count * clients_per_order) as pool:
        futures = [pool.submit(post, base, f"/api/orders/{order}/grab", None, taker_token)
                   for order in orders for _ in range(clients_per_order)]
        results = [future.result() for future in futures]
    successes = sum(1 for status, payload, _ in results if status == 200 and payload.get("data", {}).get("won"))
    report = {"scenario": "mixed", "orders": orders_count, "clientsPerOrder": clients_per_order,
              "contenders": len(results), "successes": successes, "expected": orders_count}
    print(json.dumps(report, indent=2))
    return report


def invariants(base):
    login = post(base, "/api/auth/login", {
        "username": os.getenv("ADMIN_USERNAME", "admin"),
        "password": os.environ["ADMIN_PASSWORD"],
    })
    token = login[1]["data"]["accessToken"]
    status, payload, elapsed = post(base, "/api/admin/recon/run", {}, token)
    report = {"scenario": "invariants", "status": status, "elapsedMs": elapsed, "response": payload}
    print(json.dumps(report, indent=2))
    return report


def mysql_updates():
    connection = pymysql.connect(
        host=os.getenv("DB_HOST", "127.0.0.1"), port=int(os.getenv("DB_PORT", "3306")),
        user=os.getenv("DB_USERNAME", "root"), password=os.getenv("DB_PASSWORD", ""),
        database=os.getenv("DB_NAME", "campus_errand"),
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute("SHOW GLOBAL STATUS LIKE 'Com_update'")
            return int(cursor.fetchone()[1])
    finally:
        connection.close()


def percentile(values, p):
    if not values:
        return 0
    return values[min(len(values) - 1, int(len(values) * p))]


def latency_summary(values):
    return {"p50": percentile(values, .50), "p95": percentile(values, .95),
            "p99": percentile(values, .99), "max": max(values) if values else 0}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--clients", type=int, default=200)
    parser.add_argument("--phase", choices=["burst", "mixed", "invariants"], default="burst")
    parser.add_argument("--orders", type=int, default=20)
    parser.add_argument("--clients-per-order", type=int, default=10)
    args = parser.parse_args()
    os.makedirs("reports", exist_ok=True)
    if args.phase == "burst":
        report = burst(args.base, args.clients)
    elif args.phase == "mixed":
        report = mixed(args.base, args.orders, args.clients_per_order)
    else:
        report = invariants(args.base)
    path = os.path.join("reports", f"load-{args.phase}-{time.strftime('%Y%m%d-%H%M%S')}.json")
    with open(path, "w", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=True, indent=2)
    print(f"raw_report={path}")


if __name__ == "__main__":
    main()
