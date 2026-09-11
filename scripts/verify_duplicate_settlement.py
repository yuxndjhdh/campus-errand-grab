"""Verify concurrent delivery requests produce one settlement effect."""

from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from typing import Any

import requests

from failure_common import (
    add_common_arguments,
    admin_token,
    create_order,
    db_connection,
    json_request,
    recharge,
    recon,
    register_and_login,
    require_test_environment,
    run_scenario,
    unique_key,
    utc_now,
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_common_arguments(parser)
    parser.add_argument("--clients", type=int, default=20)
    args = parser.parse_args()

    def scenario() -> dict[str, Any]:
        require_test_environment(args)
        session = requests.Session()
        _, publisher_token = register_and_login(session, args.base, "settlement-publisher")
        taker_id, taker_token = register_and_login(session, args.base, "settlement-taker")
        recharge(session, args.base, publisher_token, 10_000, unique_key("failure-settlement-recharge"))
        order_id = create_order(session, args.base, publisher_token, "duplicate settlement")
        status, payload, _ = json_request(
            session, "POST", args.base, f"/api/orders/{order_id}/grab", token=taker_token
        )
        if status != 200 or not payload.get("data", {}).get("won"):
            raise AssertionError(f"setup grab failed: HTTP {status} {payload}")

        def deliver() -> tuple[int, dict[str, Any], float]:
            return json_request(
                requests.Session(), "POST", args.base, f"/api/orders/{order_id}/deliver", token=taker_token
            )

        settlement_started_at = utc_now()
        with ThreadPoolExecutor(max_workers=args.clients) as pool:
            results = list(pool.map(lambda _: deliver(), range(args.clients)))
        settlement_finished_at = utc_now()
        successes = [result for result in results if result[0] == 200]
        errors = [result for result in results if result[0] >= 500]
        if len(successes) != 1:
            raise AssertionError(f"expected one successful delivery/settlement, got {len(successes)}")
        if errors:
            raise AssertionError(f"duplicate settlement produced system errors: {errors}")
        non_success_statuses = {result[0] for result in results if result[0] != 200}
        if non_success_statuses - {409}:
            raise AssertionError(f"duplicate delivery did not produce business conflicts: {non_success_statuses}")

        connection = db_connection()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT COUNT(*) FROM t_idempotent_op WHERE op_key=%s", (f"SETTLE:ORDER:{order_id}",)
                )
                idempotency_count = cursor.fetchone()[0]
                cursor.execute(
                    "SELECT COUNT(*) FROM t_ledger_entry WHERE biz_type='SETTLE' AND biz_id=%s", (order_id,)
                )
                settle_entries = cursor.fetchone()[0]
                cursor.execute("SELECT COALESCE(SUM(amount_cents),0) FROM t_ledger_entry")
                ledger_total = cursor.fetchone()[0]
                cursor.execute("SELECT status FROM t_errand_order WHERE id=%s", (order_id,))
                order_status = cursor.fetchone()[0]
        finally:
            connection.close()
        if order_status != "SETTLED" or idempotency_count != 1 or settle_entries != 3 or ledger_total != 0:
            raise AssertionError(
                f"settlement invariants failed: status={order_status}, idempotency={idempotency_count}, "
                f"entries={settle_entries}, total={ledger_total}"
            )
        report = recon(session, args.base, admin_token(session, args.base))
        if not report.get("passed"):
            raise AssertionError(f"reconciliation failed after duplicate settlement: {report}")
        return {
            "orderId": order_id,
            "takerId": taker_id,
            "clients": args.clients,
            "successfulDeliveries": len(successes),
            "settlementIdempotencyRows": idempotency_count,
            "settlementLedgerEntries": settle_entries,
            "ledgerTotal": ledger_total,
            "orderStatus": order_status,
            "concurrencyStartedAt": settlement_started_at,
            "concurrencyFinishedAt": settlement_finished_at,
            "reconciliation": report,
        }

    return run_scenario("duplicate-settlement", scenario)


if __name__ == "__main__":
    raise SystemExit(main())
