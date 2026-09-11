"""Verify reconciliation detects and then clears an intentional test-database drift."""

from __future__ import annotations

import argparse
from typing import Any

import requests

from failure_common import (
    add_common_arguments,
    admin_token,
    db_connection,
    json_request,
    recon,
    register_and_login,
    require_test_environment,
    run_scenario,
    utc_now,
)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_common_arguments(parser)
    args = parser.parse_args()

    def scenario() -> dict[str, Any]:
        require_test_environment(args, require_test_database=True)
        session = requests.Session()
        user_id, _ = register_and_login(session, args.base, "recon-drift")
        connection = db_connection()
        old_balance: int | None = None
        try:
            with connection.cursor() as cursor:
                cursor.execute(
                    "SELECT balance_cents FROM t_account WHERE user_id=%s AND account_type='AVAILABLE'", (user_id,)
                )
                old_balance = int(cursor.fetchone()[0])
                cursor.execute(
                    "UPDATE t_account SET balance_cents=balance_cents+1 WHERE user_id=%s AND account_type='AVAILABLE'",
                    (user_id,),
                )
            drift_at = utc_now()
            detected = recon(session, args.base, admin_token(session, args.base))
            detected_at = utc_now()
            invariant = detected["invariants"]["INV-2-materialized-balances-match-ledger"]
            if detected.get("passed") or invariant is not False:
                raise AssertionError(f"reconciliation did not identify INV-2 drift: {detected}")
        finally:
            if old_balance is not None:
                with connection.cursor() as cursor:
                    cursor.execute(
                        "UPDATE t_account SET balance_cents=%s WHERE user_id=%s AND account_type='AVAILABLE'",
                        (old_balance, user_id),
                    )
            connection.close()

        restored = recon(session, args.base, admin_token(session, args.base))
        restored_at = utc_now()
        if not restored.get("passed"):
            raise AssertionError(f"reconciliation did not pass after restoring drift: {restored}")
        return {
            "userId": user_id,
            "driftDetected": True,
            "restored": True,
            "driftInjectedAt": drift_at,
            "detectedAt": detected_at,
            "restoredAt": restored_at,
            "reconciliation": restored,
        }

    return run_scenario("reconciliation-drift", scenario)


if __name__ == "__main__":
    raise SystemExit(main())
