"""Verify recovery of an Outbox event left PROCESSING by a simulated crash."""

from __future__ import annotations

import argparse
from typing import Any

import requests

from failure_common import (
    add_common_arguments,
    admin_token,
    create_order,
    db_connection,
    json_request,
    recon,
    register_and_login,
    require_test_environment,
    run_scenario,
    wait_until,
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_common_arguments(parser)
    args = parser.parse_args()

    def scenario() -> dict[str, Any]:
        require_test_environment(args)
        session = requests.Session()
        _, publisher_token = register_and_login(session, args.base, "outbox-publisher")
        from failure_common import recharge

        recharge(session, args.base, publisher_token, 10_000, "failure-outbox-recharge")
        order_id = create_order(session, args.base, publisher_token, "outbox crash recovery")
        connection = db_connection()
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT id FROM t_outbox_event WHERE event_type='ORDER_PUBLISHED' AND biz_id=%s", (order_id,)
                )
                row = cursor.fetchone()
                if not row:
                    raise AssertionError(f"ORDER_PUBLISHED event missing for order {order_id}")
                event_id = row[0]
                cursor.execute(
                    "UPDATE t_outbox_event SET status='PROCESSING',locked_until=DATE_SUB(NOW(3), INTERVAL 1 SECOND) WHERE id=%s",
                    (event_id,),
                )
        finally:
            connection.close()

        def published() -> bool:
            connection = db_connection()
            try:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT status FROM t_outbox_event WHERE id=%s", (event_id,))
                    return cursor.fetchone()[0] == "PUBLISHED"
            finally:
                connection.close()

        wait_until(published, timeout=45)
        status, payload, _ = json_request(session, "POST", args.base, f"/api/orders/{order_id}/cancel", token=publisher_token)
        if status != 200:
            raise AssertionError(f"could not clean up recovered order: HTTP {status} {payload}")
        report = recon(session, args.base, admin_token(session, args.base))
        if not report.get("passed"):
            raise AssertionError(f"reconciliation failed after Outbox recovery: {report}")
        return {"orderId": order_id, "eventId": event_id, "finalStatus": "PUBLISHED", "reconciliation": report}

    return run_scenario("outbox-recovery", scenario)


if __name__ == "__main__":
    raise SystemExit(main())
