"""Run an authenticated grab benchmark while the Compose Redis service is stopped."""

from __future__ import annotations

import argparse
import json
import os
import time
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests

from failure_common import (
    ROOT,
    admin_token,
    compose,
    create_order,
    get_order,
    json_request,
    prometheus_metric,
    recharge,
    redis_cli,
    recon,
    register_and_login,
    require_test_environment,
    unique_key,
    utc_now,
    wait_until,
)


DEFAULT_OUTPUT = ROOT / "reports" / "benchmarks" / "raw" / (
    f"redis-fault-{datetime.now().strftime('%Y%m%d-%H%M%S-%f')}.json"
)


def latency_summary(values: list[float]) -> dict[str, float]:
    values = sorted(values)
    if not values:
        return {"p50": 0, "p95": 0, "p99": 0, "max": 0}

    def percentile(fraction: float) -> float:
        return values[min(len(values) - 1, int(len(values) * fraction))]

    return {
        "p50": percentile(0.50),
        "p95": percentile(0.95),
        "p99": percentile(0.99),
        "max": max(values),
    }


def run(args: argparse.Namespace) -> dict[str, Any]:
    require_test_environment(args)
    session = requests.Session()
    _, publisher_token = register_and_login(session, args.base, "redis-benchmark-publisher")
    recharge(session, args.base, publisher_token, 10_000_000, unique_key("redis-benchmark-recharge"))
    takers = [register_and_login(session, args.base, f"redis-benchmark-taker-{index}")
              for index in range(args.clients)]
    hot_order = create_order(session, args.base, publisher_token, "redis fault benchmark")
    before = {
        "redisDegraded": prometheus_metric(session, args.base, "redis_degraded_total"),
        "dbCas": prometheus_metric(session, args.base, "grab_db_cas_total"),
    }
    failure_at = None
    recovery_at = None
    recovery_order = None
    stopped = False
    results: list[tuple[int, dict[str, Any], float]] = []
    try:
        compose(args.compose_file, "stop", "redis")
        stopped = True
        failure_at = utc_now()
        recovery_order = create_order(session, args.base, publisher_token, "redis fault recovery")

        def grab(taker: tuple[int, str]) -> tuple[int, dict[str, Any], float]:
            return json_request(
                requests.Session(), "POST", args.base, f"/api/orders/{hot_order}/grab",
                token=taker[1], timeout=args.request_timeout,
            )

        started = time.perf_counter()
        with ThreadPoolExecutor(max_workers=args.clients) as pool:
            results = list(pool.map(grab, takers))
        duration_ms = (time.perf_counter() - started) * 1000
        during = {
            "redisDegraded": prometheus_metric(session, args.base, "redis_degraded_total"),
            "dbCas": prometheus_metric(session, args.base, "grab_db_cas_total"),
        }
        winners = [
            (index, result) for index, result in enumerate(results)
            if result[0] == 200 and result[1].get("data", {}).get("won")
        ]
        system_errors = [result for result in results if result[0] >= 500]
        if len(winners) != 1:
            raise AssertionError(f"expected exactly one winner, got {len(winners)}")
        if system_errors:
            raise AssertionError(f"Redis outage returned HTTP 5xx: {system_errors[:3]}")
        if during["redisDegraded"] <= before["redisDegraded"]:
            raise AssertionError(f"redis_degraded_total did not increase: {before} -> {during}")
        if during["dbCas"] <= before["dbCas"]:
            raise AssertionError(f"grab_db_cas_total did not increase: {before} -> {during}")
    finally:
        if stopped:
            compose(args.compose_file, "start", "redis")
            recovery_at = utc_now()

    if recovery_order is None or failure_at is None or recovery_at is None:
        raise AssertionError("Redis fault timestamps or recovery order were not recorded")
    wait_until(lambda: redis_cli(args.compose_file, "ping") == "PONG", timeout=45)
    wait_until(
        lambda: redis_cli(args.compose_file, "EXISTS", f"grab:stock:{recovery_order}") == "1",
        timeout=45,
    )
    winner_index, winner = [
        (index, result) for index, result in enumerate(results)
        if result[0] == 200 and result[1].get("data", {}).get("won")
    ][0]
    winner_token = takers[winner_index][1]
    status, payload, _ = json_request(
        session, "POST", args.base, f"/api/orders/{hot_order}/deliver", token=winner_token
    )
    if status != 200:
        raise AssertionError(f"winner could not settle after recovery: HTTP {status} {payload}")
    status, payload, _ = json_request(
        session, "POST", args.base, f"/api/orders/{recovery_order}/grab", token=takers[0][1]
    )
    if status != 200 or not payload.get("data", {}).get("won"):
        raise AssertionError(f"new order did not recover after Redis restart: HTTP {status} {payload}")
    status, payload, _ = json_request(
        session, "POST", args.base, f"/api/orders/{recovery_order}/deliver", token=takers[0][1]
    )
    if status != 200:
        raise AssertionError(f"recovery order could not settle: HTTP {status} {payload}")
    reconciliation = recon(session, args.base, admin_token(session, args.base))
    after = {
        "redisDegraded": prometheus_metric(session, args.base, "redis_degraded_total"),
        "dbCas": prometheus_metric(session, args.base, "grab_db_cas_total"),
    }
    if not reconciliation.get("passed"):
        raise AssertionError(f"reconciliation failed after Redis recovery: {reconciliation}")
    counts = Counter(str(result[0]) for result in results)
    return {
        "scenario": "redis-fault",
        "clients": args.clients,
        "hotOrderId": hot_order,
        "recoveryOrderId": recovery_order,
        "winnerCount": 1,
        "durationMs": duration_ms,
        "throughputRps": len(results) / (duration_ms / 1000),
        "latencyMs": latency_summary([result[2] for result in results]),
        "httpStatusCounts": dict(counts),
        "httpSystemErrorRate": sum(1 for result in results if result[0] >= 500) / len(results),
        "failureAt": failure_at,
        "recoveryAt": recovery_at,
        "recoveryDurationMs": (
            datetime.fromisoformat(recovery_at).timestamp() - datetime.fromisoformat(failure_at).timestamp()
        ) * 1000,
        "metricsBefore": before,
        "metricsDuring": during,
        "metricsAfter": after,
        "reconciliation": reconciliation,
        "winnerInvariantPassed": True,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.getenv("BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--compose-file", default=os.getenv("COMPOSE_FILE", "compose.yml"))
    parser.add_argument("--clients", type=int, default=200)
    parser.add_argument("--request-timeout", type=float, default=10)
    parser.add_argument("--test-environment", action="store_true")
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    args = parser.parse_args()
    if args.clients < 1 or args.request_timeout <= 0:
        parser.error("clients must be positive and request-timeout must be positive")
    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)
    started = utc_now()
    report: dict[str, Any] = {"scenario": "redis-fault", "startedAt": started}
    try:
        report.update(run(args))
        report["passed"] = True
    except Exception as exc:
        report.update({"passed": False, "errorType": type(exc).__name__, "error": str(exc)})
    report["finishedAt"] = utc_now()
    report["benchmark"] = {
        "scenario": "redis-fault",
        "clients": args.clients,
        "command": ["python", "scripts/benchmark_redis_fault.py", "--clients", str(args.clients)],
    }
    output.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=True, indent=2))
    print(f"raw_report={output}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
