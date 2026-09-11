"""Verify real HTTP grab degradation while the Compose Redis service is stopped."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from typing import Any

import requests

from failure_common import (
    add_common_arguments,
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
    run_scenario,
    unique_key,
    utc_now,
    wait_until,
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_common_arguments(parser)
    parser.add_argument("--clients", type=int, default=16)
    args = parser.parse_args()

    def scenario() -> dict[str, Any]:
        require_test_environment(args)
        session = requests.Session()
        _, publisher_token = register_and_login(session, args.base, "redis-publisher")
        recharge(session, args.base, publisher_token, 500_000, unique_key("failure-redis-recharge"))
        takers = [register_and_login(session, args.base, f"redis-taker-{index}") for index in range(args.clients)]
        hot_order = create_order(session, args.base, publisher_token, "redis outage hot order")
        recovery_order = None
        stopped = False
        failure_at = None
        recovery_at = None
        metrics_before = {
            "redisDegraded": prometheus_metric(session, args.base, "redis_degraded_total"),
            "dbCas": prometheus_metric(session, args.base, "grab_db_cas_total"),
        }
        try:
            compose(args.compose_file, "stop", "redis")
            stopped = True
            failure_at = utc_now()
            recovery_order = create_order(session, args.base, publisher_token, "redis recovery order")

            def grab(taker: tuple[int, str]) -> tuple[int, dict[str, Any], float]:
                return json_request(
                    requests.Session(),
                    "POST",
                    args.base,
                    f"/api/orders/{hot_order}/grab",
                    token=taker[1],
                    timeout=5,
                )

            with ThreadPoolExecutor(max_workers=args.clients) as pool:
                results = list(pool.map(grab, takers))
            winners = [(index, result) for index, result in enumerate(results)
                       if result[0] == 200 and result[1].get("data", {}).get("won")]
            system_errors = [result for result in results if result[0] >= 500]
            if len(winners) != 1:
                raise AssertionError(f"expected one winner, got {len(winners)}")
            if system_errors:
                raise AssertionError(f"Redis outage leaked HTTP 5xx responses: {system_errors}")
            metrics_during = {
                "redisDegraded": prometheus_metric(session, args.base, "redis_degraded_total"),
                "dbCas": prometheus_metric(session, args.base, "grab_db_cas_total"),
            }
            if metrics_during["redisDegraded"] <= metrics_before["redisDegraded"]:
                raise AssertionError(f"Redis outage did not increment redis_degraded_total: {metrics_before} -> {metrics_during}")
            if metrics_during["dbCas"] <= metrics_before["dbCas"]:
                raise AssertionError(f"Redis outage did not reach the DB CAS path: {metrics_before} -> {metrics_during}")

            readiness = requests.get(args.base + "/actuator/health/readiness", timeout=5)
            if readiness.status_code != 200 or readiness.json().get("status") != "UP":
                raise AssertionError(f"readiness degraded during Redis outage: {readiness.status_code} {readiness.text}")
            winner_index, winner = winners[0]
            if winner[1].get("data", {}).get("order", {}).get("takerId") != takers[winner_index][0]:
                raise AssertionError(f"winner response identified the wrong taker: {winner}")
            winner_token = takers[winner_index][1]
        finally:
            if stopped:
                compose(args.compose_file, "start", "redis")
                recovery_at = utc_now()

        if recovery_order is None or failure_at is None or recovery_at is None:
            raise AssertionError("Redis outage timestamps or recovery order were not recorded")
        wait_until(lambda: redis_cli(args.compose_file, "ping") == "PONG", timeout=45)
        wait_until(
            lambda: redis_cli(args.compose_file, "EXISTS", f"grab:stock:{recovery_order}") == "1",
            timeout=45,
        )
        wait_until(
            lambda: redis_cli(args.compose_file, "ZSCORE", "delay:claim", f"CLAIM:{recovery_order}") not in {"", "(nil)"},
            timeout=45,
        )
        wait_until(
            lambda: redis_cli(args.compose_file, "ZSCORE", "delay:deliver", f"DELIVER:{hot_order}") not in {"", "(nil)"},
            timeout=45,
        )

        hot_state = get_order(session, args.base, hot_order, winner_token)
        if hot_state.get("status") != "TAKEN" or hot_state.get("takerId") != takers[winner_index][0]:
            raise AssertionError(f"hot order lost its single-winner state: {hot_state}")
        status, payload, _ = json_request(session, "POST", args.base, f"/api/orders/{hot_order}/deliver", token=winner_token)
        if status != 200:
            raise AssertionError(f"winner could not finish after Redis recovery: HTTP {status} {payload}")
        _, recovery_taker_token = takers[0]
        status, payload, _ = json_request(
            session, "POST", args.base, f"/api/orders/{recovery_order}/grab", token=recovery_taker_token
        )
        if status != 200 or not payload.get("data", {}).get("won"):
            raise AssertionError(f"new order did not recover after Redis restart: HTTP {status} {payload}")
        status, payload, _ = json_request(
            session, "POST", args.base, f"/api/orders/{recovery_order}/deliver", token=recovery_taker_token
        )
        if status != 200:
            raise AssertionError(f"recovery order could not settle: HTTP {status} {payload}")
        report = recon(session, args.base, admin_token(session, args.base))
        if not report.get("passed"):
            raise AssertionError(f"reconciliation failed after Redis recovery: {report}")
        return {
            "hotOrderId": hot_order,
            "recoveryOrderId": recovery_order,
            "clients": args.clients,
            "winnerCount": len(winners),
            "failureAt": failure_at,
            "recoveryAt": recovery_at,
            "metricsBefore": metrics_before,
            "metricsDuring": metrics_during,
            "reconciliation": report,
        }

    return run_scenario("redis-outage", scenario)


if __name__ == "__main__":
    raise SystemExit(main())
