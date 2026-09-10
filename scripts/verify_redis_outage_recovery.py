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
    json_request,
    redis_cli,
    recon,
    register_and_login,
    require_test_environment,
    run_scenario,
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
        publisher_id, publisher_token = register_and_login(session, args.base, "redis-publisher")
        del publisher_id
        _, admin = register_and_login(session, args.base, "redis-admin-placeholder")
        del admin
        # The configured bootstrap admin is used for the final reconciliation call.
        from failure_common import recharge

        recharge(session, args.base, publisher_token, 500_000, "failure-redis-recharge")
        takers = [register_and_login(session, args.base, f"redis-taker-{index}") for index in range(args.clients)]
        hot_order = create_order(session, args.base, publisher_token, "redis outage hot order")
        recovery_order = None
        stopped = False
        try:
            compose(args.compose_file, "stop", "redis")
            stopped = True
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
            winners = [result for result in results if result[0] == 200 and result[1].get("data", {}).get("won")]
            system_errors = [result for result in results if result[0] >= 500]
            if len(winners) != 1:
                raise AssertionError(f"expected one winner, got {len(winners)}")
            if system_errors:
                raise AssertionError(f"Redis outage leaked HTTP 5xx responses: {system_errors}")

            readiness = requests.get(args.base + "/actuator/health/readiness", timeout=5)
            if readiness.status_code != 200 or readiness.json().get("status") != "UP":
                raise AssertionError(f"readiness degraded during Redis outage: {readiness.status_code} {readiness.text}")
            winner_index = next(index for index, result in enumerate(results) if result in winners)
            winner_token = takers[winner_index][1]
        finally:
            if stopped:
                compose(args.compose_file, "start", "redis")

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
        return {"hotOrderId": hot_order, "recoveryOrderId": recovery_order, "clients": args.clients, "reconciliation": report}

    return run_scenario("redis-outage", scenario)


if __name__ == "__main__":
    raise SystemExit(main())
