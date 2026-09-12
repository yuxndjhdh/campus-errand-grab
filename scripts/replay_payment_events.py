"""Replay sandbox payment and refund events against a disposable local environment.

The script exercises signature validation, replay-window rejection, duplicate and
out-of-order callbacks, refund idempotency, notification uniqueness, dispute
compensation and reconciliation. It never prints credentials or request bodies.
"""

from __future__ import annotations

import argparse
import hashlib
import hmac
import json
import os
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests


ROOT = Path(__file__).resolve().parents[1]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def event_signature(secret: str, event_id: str, timestamp: int, provider_payment_id: str,
                   user_id: int, amount_cents: int) -> str:
    canonical = f"{event_id}|{timestamp}|{provider_payment_id}|{user_id}|{amount_cents}"
    return hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()


def request_json(session: requests.Session, method: str, url: str, **kwargs: Any) -> dict[str, Any]:
    started = time.perf_counter()
    response = session.request(method, url, timeout=20, **kwargs)
    try:
        body = response.json()
    except ValueError:
        body = {}
    return {
        "status": response.status_code,
        "body": body if isinstance(body, dict) else {},
        "elapsedMs": round((time.perf_counter() - started) * 1000, 3),
    }


def response_data(response: dict[str, Any]) -> dict[str, Any]:
    data = response.get("body", {}).get("data")
    return data if isinstance(data, dict) else {}


def error_code(response: dict[str, Any]) -> str:
    return str(response.get("body", {}).get("code", ""))


def sql(container: str, database: str, statement: str, password: str) -> list[list[str]]:
    process = subprocess.run(
        ["docker", "exec", "-e", f"MYSQL_PWD={password}", container,
         "mysql", "-uroot", "-N", "-B", database, "-e", statement],
        cwd=ROOT, capture_output=True, text=True, check=False,
    )
    if process.returncode != 0:
        raise RuntimeError("database query failed")
    return [line.split("\t") for line in process.stdout.splitlines() if line]


def scalar(container: str, database: str, statement: str, password: str) -> int:
    rows = sql(container, database, statement, password)
    if not rows or not rows[0]:
        raise RuntimeError("database query returned no scalar")
    return int(rows[0][0])


def register_and_login(session: requests.Session, base: str, username: str, password: str) -> tuple[int, str]:
    registered = request_json(session, "POST", base + "/api/auth/register", json={
        "username": username,
        "nickname": "payment-replay",
        "password": password,
    })
    if registered["status"] != 200:
        raise RuntimeError(f"test identity registration failed with HTTP {registered['status']}")
    logged_in = request_json(session, "POST", base + "/api/auth/login", json={
        "username": username,
        "password": password,
    })
    if logged_in["status"] != 200 or not response_data(logged_in).get("accessToken"):
        raise RuntimeError(f"test identity login failed with HTTP {logged_in['status']}")
    user_id = int(response_data(logged_in)["userId"])
    return user_id, str(response_data(logged_in)["accessToken"])


def login(session: requests.Session, base: str, username: str, password: str) -> str:
    response = request_json(session, "POST", base + "/api/auth/login", json={
        "username": username,
        "password": password,
    })
    if response["status"] != 200 or not response_data(response).get("accessToken"):
        raise RuntimeError(f"admin login failed with HTTP {response['status']}")
    return str(response_data(response)["accessToken"])


def callback(session: requests.Session, base: str, secret: str, user_id: int,
             provider_payment_id: str, event_id: str, amount_cents: int,
             timestamp: int | None = None, signature_override: str | None = None) -> dict[str, Any]:
    event_timestamp = int(time.time()) if timestamp is None else timestamp
    signature = signature_override or event_signature(
        secret, event_id, event_timestamp, provider_payment_id, user_id, amount_cents,
    )
    return request_json(session, "POST", base + "/api/payments/webhook", json={
        "providerPaymentId": provider_payment_id,
        "eventId": event_id,
        "userId": user_id,
        "amountCents": amount_cents,
        "timestampEpochSeconds": event_timestamp,
        "signature": signature,
    })


def cleanup(container: str, database: str, password: str, username: str, event_prefix: str) -> None:
    # All predicates use this run's generated username. The order follows the
    # V6/V7 foreign keys and does not touch unrelated application data.
    user_filter = f"(SELECT id FROM t_user WHERE username='{username}')"
    statements = [
        "DELETE FROM t_outbox_event WHERE event_type IN "
        "('PAYMENT_NOTIFICATION','REFUND_NOTIFICATION','DISPUTE_NOTIFICATION') "
        f"AND biz_id IN (SELECT id FROM t_notification WHERE user_id IN {user_filter})",
        f"DELETE FROM t_compensation WHERE dispute_id IN "
        f"(SELECT id FROM t_dispute WHERE user_id IN {user_filter})",
        f"DELETE FROM t_dispute WHERE user_id IN {user_filter}",
        f"DELETE FROM t_notification WHERE user_id IN {user_filter}",
        f"DELETE FROM t_refund WHERE payment_id IN "
        f"(SELECT id FROM t_payment WHERE user_id IN {user_filter})",
        f"DELETE FROM t_payment WHERE user_id IN {user_filter}",
        "DELETE FROM t_ledger_entry WHERE biz_type IN ('RECHARGE','REFUND','COMPENSATION') "
        f"AND biz_id IN (SELECT biz_id FROM (SELECT l.biz_id FROM t_ledger_entry l "
        f"WHERE l.user_id IN {user_filter}) selected_biz)",
        f"DELETE FROM t_idempotent_op WHERE op_key LIKE 'RECHARGE:PAYMENT:{event_prefix}%'",
        f"DELETE FROM t_account WHERE user_id IN {user_filter}",
        f"DELETE FROM t_user WHERE username='{username}'",
    ]
    for statement in statements:
        sql(container, database, statement, password)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--test-environment", action="store_true", required=True)
    parser.add_argument("--base", default=os.getenv("BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--duplicates", type=int, default=1000)
    parser.add_argument("--amount-cents", type=int, default=3000)
    parser.add_argument("--payment-secret", default=os.getenv("PAYMENT_WEBHOOK_SECRET", "local-payment-webhook-secret"))
    parser.add_argument("--admin-username", default=os.getenv("ADMIN_USERNAME", "admin"))
    parser.add_argument("--admin-password", default=os.getenv("ADMIN_PASSWORD", "change-me-admin"))
    parser.add_argument("--mysql-container", default=os.getenv("MYSQL_CONTAINER", "campus-errand-grab-mysql-1"))
    parser.add_argument("--database", default=os.getenv("DB_NAME", "campus_errand"))
    parser.add_argument("--db-password", default=os.getenv("DB_PASSWORD", "change-me"))
    parser.add_argument("--output", default="reports/payment/payment-replay-report.json")
    args = parser.parse_args()
    if args.duplicates < 1000:
        parser.error("--duplicates must be at least 1000")
    if args.amount_cents < 1:
        parser.error("--amount-cents must be positive")

    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)
    base = args.base.rstrip("/")
    suffix = str(time.time_ns())
    username = f"payment-replay-{suffix}"
    password = "payment-replay-password"
    session = requests.Session()
    result: dict[str, Any] = {
        "status": "PASS",
        "startedAt": utc_now(),
        "baseUrl": base,
        "duplicatesRequested": args.duplicates,
        "sensitiveValuesExcluded": True,
        "checks": {},
        "limitations": [
            "Local SANDBOX provider only; no real payment or refund channel was contacted.",
            "Replay requests are sequential; database uniqueness is additionally covered by the integration schema and service transaction.",
            "The report is local Compose evidence and is not a payment-provider SLA or production-funds claim.",
        ],
    }
    try:
        user_id, user_token = register_and_login(session, base, username, password)
        admin_token = login(session, base, args.admin_username, args.admin_password)
        user_headers = {"Authorization": f"Bearer {user_token}"}
        admin_headers = {"Authorization": f"Bearer {admin_token}"}

        primary_provider = f"replay-{suffix}-primary-payment"
        primary_event = f"replay-{suffix}-primary-event"
        first = callback(session, base, args.payment_secret, user_id, primary_provider, primary_event, args.amount_cents)
        if first["status"] != 200:
            raise RuntimeError(f"initial payment callback failed with HTTP {first['status']}")
        payment_id = int(response_data(first)["id"])
        duplicate_responses = [
            callback(session, base, args.payment_secret, user_id, primary_provider, primary_event, args.amount_cents)
            for _ in range(args.duplicates - 1)
        ]
        duplicate_ids = [int(response_data(item).get("id", -1)) for item in duplicate_responses]
        result["checks"]["duplicatePayment"] = {
            "requested": args.duplicates,
            "http200": sum(1 for item in duplicate_responses if item["status"] == 200) + 1,
            "samePaymentId": all(item == payment_id for item in duplicate_ids),
        }

        conflict = callback(session, base, args.payment_secret, user_id, primary_provider, primary_event,
                            args.amount_cents + 1)
        bad_signature = callback(session, base, args.payment_secret, user_id, f"replay-{suffix}-bad-signature-payment",
                                 f"replay-{suffix}-bad-signature-event", args.amount_cents, signature_override="invalid")
        expired = callback(session, base, args.payment_secret, user_id, f"replay-{suffix}-expired-payment",
                           f"replay-{suffix}-expired-event", args.amount_cents, timestamp=int(time.time()) - 3600)
        result["checks"]["rejectedCallbacks"] = {
            "parameterConflict": {"status": conflict["status"], "code": error_code(conflict)},
            "badSignature": {"status": bad_signature["status"], "code": error_code(bad_signature)},
            "expiredTimestamp": {"status": expired["status"], "code": error_code(expired)},
        }

        normal_provider = f"replay-{suffix}-normal-order"
        normal_first = callback(session, base, args.payment_secret, user_id, normal_provider,
                                f"replay-{suffix}-normal-original", args.amount_cents)
        normal_second = callback(session, base, args.payment_secret, user_id, normal_provider,
                                 f"replay-{suffix}-normal-late", args.amount_cents)
        reverse_provider = f"replay-{suffix}-reverse-order"
        reverse_first = callback(session, base, args.payment_secret, user_id, reverse_provider,
                                 f"replay-{suffix}-reverse-late", args.amount_cents)
        reverse_second = callback(session, base, args.payment_secret, user_id, reverse_provider,
                                  f"replay-{suffix}-reverse-original", args.amount_cents)
        normal_id = int(response_data(normal_first)["id"])
        reverse_id = int(response_data(reverse_first)["id"])
        result["checks"]["outOfOrder"] = {
            "normalCallbacksSucceeded": normal_first["status"] == 200 and normal_second["status"] == 200,
            "reverseCallbacksSucceeded": reverse_first["status"] == 200 and reverse_second["status"] == 200,
            "normalSameId": normal_id == int(response_data(normal_second)["id"]),
            "reverseSameId": reverse_id == int(response_data(reverse_second)["id"]),
        }

        refund_path = base + f"/api/payments/{payment_id}/refund"
        first_refund = request_json(session, "POST", refund_path, headers=user_headers,
                                    json={"amountCents": args.amount_cents, "requestKey": f"replay-{suffix}-refund"})
        if first_refund["status"] != 200:
            raise RuntimeError(f"initial refund failed with HTTP {first_refund['status']}")
        refund_id = int(response_data(first_refund)["id"])
        refund_responses = [
            request_json(session, "POST", refund_path, headers=user_headers,
                         json={"amountCents": args.amount_cents, "requestKey": f"replay-{suffix}-refund"})
            for _ in range(args.duplicates - 1)
        ]
        refund_ids = [int(response_data(item).get("id", -1)) for item in refund_responses]
        second_refund = request_json(session, "POST", refund_path, headers=user_headers,
                                     json={"amountCents": args.amount_cents, "requestKey": f"replay-{suffix}-second-refund"})
        result["checks"]["duplicateRefund"] = {
            "requested": args.duplicates,
            "http200": sum(1 for item in refund_responses if item["status"] == 200) + 1,
            "sameRefundId": all(item == refund_id for item in refund_ids),
            "secondRequestRejected": second_refund["status"] == 409,
            "secondRequestCode": error_code(second_refund),
        }

        dispute = request_json(session, "POST", base + f"/api/disputes/payment/{payment_id}",
                               headers=user_headers, json={"reason": "verified sandbox replay"})
        if dispute["status"] != 200:
            raise RuntimeError(f"dispute opening failed with HTTP {dispute['status']}")
        dispute_id = int(response_data(dispute)["id"])
        compensation_body = {
            "amountCents": 1000,
            "requestKey": f"replay-{suffix}-compensation",
            "reason": "manual sandbox verification",
        }
        compensation_one = request_json(session, "POST", base + f"/api/disputes/{dispute_id}/compensate",
                                         headers=admin_headers, json=compensation_body)
        compensation_two = request_json(session, "POST", base + f"/api/disputes/{dispute_id}/compensate",
                                         headers=admin_headers, json=compensation_body)
        result["checks"]["compensationAudit"] = {
            "firstSucceeded": compensation_one["status"] == 200,
            "duplicateSucceededAsReplay": compensation_two["status"] == 200,
            "finalStatus": response_data(compensation_two).get("status"),
        }

        recon = request_json(session, "POST", base + "/api/admin/recon/run", headers=admin_headers, json={})
        result["checks"]["reconciliation"] = {
            "httpStatus": recon["status"],
            "passed": response_data(recon).get("passed") is True,
        }

        primary_payment_count = scalar(
            args.mysql_container, args.database,
            f"SELECT COUNT(*) FROM t_payment WHERE provider_payment_id='{primary_provider}'",
            args.db_password,
        )
        primary_refund_count = scalar(
            args.mysql_container, args.database,
            f"SELECT COUNT(*) FROM t_refund WHERE request_key='replay-{suffix}-refund'",
            args.db_password,
        )
        payment_notification_count = scalar(
            args.mysql_container, args.database,
            f"SELECT COUNT(*) FROM t_notification WHERE event_key='PAYMENT:{payment_id}'",
            args.db_password,
        )
        refund_notification_count = scalar(
            args.mysql_container, args.database,
            f"SELECT COUNT(*) FROM t_notification WHERE event_key='REFUND:{refund_id}'",
            args.db_password,
        )
        user_recharge_entries = scalar(
            args.mysql_container, args.database,
            f"SELECT COUNT(*) FROM t_ledger_entry WHERE user_id={user_id} AND biz_type='RECHARGE'",
            args.db_password,
        )
        compensation_entries = scalar(
            args.mysql_container, args.database,
            f"SELECT COUNT(*) FROM t_ledger_entry WHERE biz_type='COMPENSATION' AND biz_id={dispute_id}",
            args.db_password,
        )
        result["databaseAssertions"] = {
            "primaryPaymentRows": primary_payment_count,
            "primaryRefundRows": primary_refund_count,
            "paymentNotificationRows": payment_notification_count,
            "refundNotificationRows": refund_notification_count,
            "userRechargeLedgerRows": user_recharge_entries,
            "compensationLedgerRows": compensation_entries,
            "expectedPaymentRows": 1,
            "expectedRefundRows": 1,
            "expectedNotificationRowsPerEvent": 1,
            "expectedRechargeRowsForThreePayments": 3,
            "expectedCompensationRows": 2,
        }
        checks = result["checks"]
        result["status"] = "PASS" if (
            checks["duplicatePayment"]["http200"] == args.duplicates
            and checks["duplicatePayment"]["samePaymentId"]
            and checks["rejectedCallbacks"]["parameterConflict"] == {"status": 409, "code": "IDEMPOTENCY_CONFLICT"}
            and checks["rejectedCallbacks"]["badSignature"] == {"status": 401, "code": "UNAUTHORIZED"}
            and checks["rejectedCallbacks"]["expiredTimestamp"] == {"status": 401, "code": "UNAUTHORIZED"}
            and all(checks["outOfOrder"].values())
            and checks["duplicateRefund"]["http200"] == args.duplicates
            and checks["duplicateRefund"]["sameRefundId"]
            and checks["duplicateRefund"]["secondRequestRejected"]
            and checks["compensationAudit"]["firstSucceeded"]
            and checks["compensationAudit"]["duplicateSucceededAsReplay"]
            and checks["compensationAudit"]["finalStatus"] == "RESOLVED"
            and checks["reconciliation"]["passed"]
            and primary_payment_count == 1
            and primary_refund_count == 1
            and payment_notification_count == 1
            and refund_notification_count == 1
            and user_recharge_entries == 3
            and compensation_entries == 2
        ) else "FAIL"
    except Exception as exc:
        result["status"] = "FAIL"
        result["error"] = str(exc)
    finally:
        try:
            cleanup(args.mysql_container, args.database, args.db_password, username, f"replay-{suffix}")
            result["cleanedUp"] = True
        except Exception:
            result["cleanedUp"] = False
        result["finishedAt"] = utc_now()
        output.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
        print(f"payment_replay_report={output}")
        print(f"status={result['status']}")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
