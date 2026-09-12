"""Validate Prometheus/Grafana wiring and exercise configured alert rules."""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "reports" / "monitoring" / "monitoring-validation.json"
EXPECTED_METRICS = {
    "grab_attempt_total",
    "grab_redis_filtered_total",
    "grab_db_cas_total",
    "grab_winner_total",
    "settlement_retry_total",
    "settlement_dead",
    "redis_degraded_total",
    "outbox_pending",
    "outbox_dead",
    "timeout_queue_lag_seconds",
    "recon_failure_total",
    "recon_last_success_timestamp",
    "redis_lua_in_flight",
    "redis_lua_concurrency_max",
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def get_json(url: str, **kwargs: Any) -> Any:
    response = requests.get(url, timeout=15, **kwargs)
    response.raise_for_status()
    return response.json()


def post_json(url: str, body: dict[str, Any], **kwargs: Any) -> Any:
    response = requests.post(url, json=body, timeout=15, **kwargs)
    response.raise_for_status()
    return response.json()


def docker_exec(container: str, args: list[str], env: dict[str, str] | None = None) -> str:
    command = ["docker", "exec"]
    for key, value in (env or {}).items():
        command.extend(["-e", f"{key}={value}"])
    command.extend([container, *args])
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=True)
    return result.stdout.strip()


def docker_control(container: str, action: str) -> None:
    result = subprocess.run(["docker", action, container], cwd=ROOT, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"docker {action} {container} failed: {result.stderr[-500:]}")


def wait_healthy(container: str, timeout: int = 60) -> float:
    started = time.monotonic()
    deadline = started + timeout
    while time.monotonic() < deadline:
        result = subprocess.run(
            ["docker", "inspect", "--format", "{{.State.Health.Status}}", container],
            cwd=ROOT, capture_output=True, text=True, check=False,
        )
        if result.returncode == 0 and result.stdout.strip() == "healthy":
            return round(time.monotonic() - started, 3)
        time.sleep(2)
    raise TimeoutError(f"container {container} did not become healthy")


def sql(container: str, statement: str) -> str:
    return docker_exec(
        container,
        ["mysql", "-uroot", "-N", "-B", "campus_errand", "-e", statement],
        {"MYSQL_PWD": "change-me"},
    )


def metric_names(text: str) -> set[str]:
    names: set[str] = set()
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        names.add(line.split("{", 1)[0].split(" ", 1)[0])
    return names


def metric_samples(text: str) -> dict[str, float]:
    samples: dict[str, float] = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        name, value = line.rsplit(" ", 1)
        samples[name.split("{", 1)[0]] = float(value)
    return samples


def active_alerts(prometheus: str) -> list[dict[str, Any]]:
    payload = get_json(prometheus.rstrip("/") + "/api/v1/alerts")
    return payload.get("data", {}).get("alerts", [])


def alert_state(prometheus: str, name: str) -> str:
    rules = get_json(prometheus.rstrip("/") + "/api/v1/rules?type=alert")
    for group in rules.get("data", {}).get("groups", []):
        for rule in group.get("rules", []):
            if rule.get("name") == name:
                return str(rule.get("state", "unknown"))
    return "missing"


def wait_for_alert(prometheus: str, name: str, expected: str, timeout: int) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    last = "unknown"
    while time.monotonic() < deadline:
        last = alert_state(prometheus, name)
        if last == expected:
            return {"state": last, "observedAt": utc_now()}
        time.sleep(10)
    raise TimeoutError(f"alert {name} did not reach {expected}; last state={last}")


def admin_token(base: str, username: str, password: str) -> str:
    payload = post_json(base.rstrip("/") + "/api/auth/login", {
        "username": username,
        "password": password,
    })
    return str(payload["data"]["accessToken"])


def recon(base: str, token: str) -> dict[str, Any]:
    return post_json(base.rstrip("/") + "/api/admin/recon/run", {}, headers={
        "Authorization": f"Bearer {token}",
    })["data"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.getenv("BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--prometheus", default=os.getenv("PROMETHEUS_URL", "http://127.0.0.1:9090"))
    parser.add_argument("--grafana", default=os.getenv("GRAFANA_URL", "http://127.0.0.1:3000"))
    parser.add_argument("--grafana-username", default=os.getenv("GRAFANA_ADMIN_USER", "admin"))
    parser.add_argument("--grafana-password", default=os.getenv("GRAFANA_ADMIN_PASSWORD", "change-me-grafana"))
    parser.add_argument("--mysql-container", default="campus-errand-grab-mysql-1")
    parser.add_argument("--redis-container", default="campus-errand-grab-redis-1")
    parser.add_argument("--admin-username", default=os.getenv("ADMIN_USERNAME", "admin"))
    parser.add_argument("--admin-password", default=os.getenv("ADMIN_PASSWORD", "change-me-admin"))
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    parser.add_argument(
        "--alert-timeout", type=int, default=420,
        help="Maximum seconds to wait for each alert state; must exceed the longest rule lookback window.",
    )
    args = parser.parse_args()

    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)

    started = utc_now()
    base = args.base.rstrip("/")
    prometheus = args.prometheus.rstrip("/")
    grafana = args.grafana.rstrip("/")
    result: dict[str, Any] = {
        "status": "PASS",
        "startedAt": started,
        "command": "python scripts/validate_monitoring.py",
        "sensitiveValuesExcluded": True,
    }
    cleanup: list[str] = []
    redis_stopped = False

    try:
        metrics_text = requests.get(base + "/actuator/prometheus", timeout=15).text
        names = metric_names(metrics_text)
        high_cardinality = [
            line for line in metrics_text.splitlines()
            if any(label in line for label in ["userId=", "orderId=", "user_id=", "order_id="])
        ]
        samples = metric_samples(metrics_text)
        targets = get_json(prometheus + "/api/v1/targets")
        rules = get_json(prometheus + "/api/v1/rules")
        grafana_health = get_json(grafana + "/api/health")
        grafana_auth = (args.grafana_username, args.grafana_password)
        grafana_datasources = get_json(grafana + "/api/datasources", auth=grafana_auth)
        grafana_search = get_json(grafana + "/api/search", auth=grafana_auth)

        result["wiring"] = {
            "metricsPresent": sorted(EXPECTED_METRICS & names),
            "missingMetrics": sorted(EXPECTED_METRICS - names),
            "highCardinalityViolations": len(high_cardinality),
            "prometheusTargetHealth": [
                {"job": target.get("labels", {}).get("job"), "health": target.get("health"),
                 "lastError": target.get("lastError", "")}
                for target in targets.get("data", {}).get("activeTargets", [])
            ],
            "loadedRuleGroups": [group.get("name") for group in rules.get("data", {}).get("groups", [])],
            "grafanaHealth": {"database": grafana_health.get("database"), "version": grafana_health.get("version")},
            "grafanaDatasources": [item.get("name") for item in grafana_datasources],
            "grafanaDashboards": [item.get("title") for item in grafana_search if item.get("type") == "dash-db"],
            "sampleValues": {name: samples.get(name) for name in sorted(EXPECTED_METRICS)},
        }
        if result["wiring"]["missingMetrics"] or high_cardinality:
            raise RuntimeError("metric presence or label-cardinality validation failed")

        token = admin_token(args.base, args.admin_username, args.admin_password)
        alert_results: list[dict[str, Any]] = []

        dead_biz_id = int(time.time() * 1000)
        sql(args.mysql_container, (
            "INSERT INTO t_outbox_event(event_type,biz_id,payload_json,status,retry_count,last_error) "
            f"VALUES ('MONITOR_DEAD',{dead_biz_id},'{{}}','DEAD',12,'monitoring validation')"
        ))
        cleanup.append(f"DELETE FROM t_outbox_event WHERE event_type='MONITOR_DEAD' AND biz_id={dead_biz_id}")
        dead_injected_at = utc_now()
        fired = wait_for_alert(args.prometheus, "CampusErrandOutboxDead", "firing", args.alert_timeout)
        alert_results.append({"alert": "CampusErrandOutboxDead", "fired": fired})
        dead_repaired_at = utc_now()
        sql(args.mysql_container, cleanup.pop())
        alert_results[-1]["resolved"] = wait_for_alert(
            args.prometheus, "CampusErrandOutboxDead", "inactive", args.alert_timeout
        )
        alert_results[-1]["injectedAt"] = dead_injected_at
        alert_results[-1]["repairedAt"] = dead_repaired_at

        account_before = int(sql(
            args.mysql_container,
            "SELECT balance_cents FROM t_account WHERE user_id=1 AND account_type='AVAILABLE'",
        ))
        sql(args.mysql_container, "UPDATE t_account SET balance_cents=balance_cents+1 WHERE user_id=1 AND account_type='AVAILABLE'")
        cleanup.append(
            f"UPDATE t_account SET balance_cents={account_before} WHERE user_id=1 AND account_type='AVAILABLE'"
        )
        recon_injected_at = utc_now()
        recon_failure = recon(args.base, token)
        if recon_failure.get("passed") is not False:
            raise RuntimeError("reconciliation drift injection did not fail")
        fired = wait_for_alert(args.prometheus, "CampusErrandReconFailure", "firing", args.alert_timeout)
        alert_results.append({"alert": "CampusErrandReconFailure", "fired": fired,
                              "reconciliationDuringFault": recon_failure})
        recon_repaired_at = utc_now()
        sql(args.mysql_container, cleanup.pop())
        recon_recovered = recon(args.base, token)
        if recon_recovered.get("passed") is not True:
            raise RuntimeError("reconciliation did not recover after drift repair")
        alert_results[-1]["resolved"] = wait_for_alert(
            args.prometheus, "CampusErrandReconFailure", "inactive", args.alert_timeout
        )
        alert_results[-1]["injectedAt"] = recon_injected_at
        alert_results[-1]["repairedAt"] = recon_repaired_at

        invalid_member = f"CLAIM:monitor-invalid-{int(time.time())}"
        past_ms = int(time.time() * 1000) - 120_000
        docker_exec(args.redis_container, ["redis-cli", "ZADD", "delay:claim", str(past_ms), invalid_member])
        cleanup.append(f"redis-cli ZREM delay:claim {invalid_member}")
        timeout_injected_at = utc_now()
        fired = wait_for_alert(args.prometheus, "CampusErrandTimeoutQueueLagHigh", "firing", args.alert_timeout)
        alert_results.append({"alert": "CampusErrandTimeoutQueueLagHigh", "fired": fired})
        timeout_repaired_at = utc_now()
        docker_exec(args.redis_container, ["redis-cli", "ZREM", "delay:claim", invalid_member])
        cleanup.pop()
        alert_results[-1]["resolved"] = wait_for_alert(
            args.prometheus, "CampusErrandTimeoutQueueLagHigh", "inactive", args.alert_timeout
        )
        alert_results[-1]["injectedAt"] = timeout_injected_at
        alert_results[-1]["repairedAt"] = timeout_repaired_at

        redis_suffix = str(time.time_ns())
        publisher_name = f"monitoring-redis-publisher-{redis_suffix}"
        taker_name = f"monitoring-redis-taker-{redis_suffix}"
        publisher_password = "monitoring-publisher-password"
        taker_password = "monitoring-taker-password"
        post_json(base + "/api/auth/register", {
            "username": publisher_name, "nickname": publisher_name, "password": publisher_password,
        })
        post_json(base + "/api/auth/register", {
            "username": taker_name, "nickname": taker_name, "password": taker_password,
        })
        publisher_token = admin_token(base, publisher_name, publisher_password)
        taker_token = admin_token(base, taker_name, taker_password)
        recharge_key = f"monitoring-redis-recharge-{redis_suffix}"
        mint_available_before = int(sql(
            args.mysql_container,
            "SELECT balance_cents FROM t_account WHERE user_id=1 AND account_type='AVAILABLE'",
        ))
        post_json(base + "/api/users/me/recharge", {"amountCents": 5000, "idemKey": recharge_key},
                  headers={"Authorization": f"Bearer {publisher_token}"})
        order_payload = post_json(base + "/api/orders", {
            "title": "monitoring redis degraded", "rewardCents": 1000, "claimTtlSeconds": 120,
        }, headers={"Authorization": f"Bearer {publisher_token}"})
        redis_order_id = int(order_payload["data"]["id"])
        publisher_id = int(sql(args.mysql_container,
                               f"SELECT id FROM t_user WHERE username='{publisher_name}'"))
        taker_id = int(sql(args.mysql_container,
                           f"SELECT id FROM t_user WHERE username='{taker_name}'"))
        recharge_biz_id = int(sql(
            args.mysql_container,
            f"SELECT biz_id FROM t_ledger_entry WHERE biz_type='RECHARGE' AND user_id={publisher_id} "
            "ORDER BY id DESC LIMIT 1",
        ))
        cleanup.append(
            "START TRANSACTION; "
            f"DELETE FROM t_outbox_event WHERE biz_id={redis_order_id}; "
            f"DELETE FROM t_ledger_entry WHERE user_id IN ({publisher_id},{taker_id}); "
            f"DELETE FROM t_ledger_entry WHERE biz_type='RECHARGE' AND biz_id={recharge_biz_id}; "
            f"DELETE FROM t_idempotent_op WHERE op_key='{recharge_key}'; "
            f"DELETE FROM t_errand_order WHERE id={redis_order_id}; "
            f"DELETE FROM t_account WHERE user_id IN ({publisher_id},{taker_id}); "
            f"UPDATE t_account SET balance_cents={mint_available_before} WHERE user_id=1 AND account_type='AVAILABLE'; "
            f"DELETE FROM t_user WHERE id IN ({publisher_id},{taker_id}); "
            "COMMIT;"
        )
        docker_control(args.redis_container, "stop")
        redis_stopped = True
        redis_outage_started_at = utc_now()
        grab_result = post_json(base + f"/api/orders/{redis_order_id}/grab", {},
                                headers={"Authorization": f"Bearer {taker_token}"})
        if grab_result.get("data", {}).get("won") is not True:
            raise RuntimeError(f"Redis fallback grab did not win: {grab_result}")
        redis_fired = wait_for_alert(args.prometheus, "CampusErrandRedisDegraded", "firing", args.alert_timeout)
        docker_control(args.redis_container, "start")
        redis_recovery_seconds = wait_healthy(args.redis_container)
        redis_stopped = False
        redis_repaired_at = utc_now()
        post_json(base + f"/api/orders/{redis_order_id}/cancel", {},
                  headers={"Authorization": f"Bearer {publisher_token}"})
        redis_result = {
            "alert": "CampusErrandRedisDegraded",
            "fired": redis_fired,
            "injectedAt": redis_outage_started_at,
            "repairedAt": redis_repaired_at,
            "redisRecoverySeconds": redis_recovery_seconds,
            "fallbackGrabWon": True,
        }
        redis_result["resolved"] = wait_for_alert(
            args.prometheus, "CampusErrandRedisDegraded", "inactive", args.alert_timeout
        )
        alert_results.append(redis_result)

        dead_marker = f"monitoring-settlement-dead-{time.time_ns()}"
        dead_ledger_biz_id = int(time.time() * 1000) + 1
        mint_available = int(sql(args.mysql_container,
                                 "SELECT balance_cents FROM t_account WHERE user_id=1 AND account_type='AVAILABLE'"))
        mint_frozen = int(sql(args.mysql_container,
                              "SELECT balance_cents FROM t_account WHERE user_id=1 AND account_type='FROZEN'"))
        sql(args.mysql_container, (
            "INSERT INTO t_errand_order(publisher_id,taker_id,title,reward_cents,status,claim_deadline_at,"
            "deliver_deadline_at,delivered_at,settlement_retry_count,settlement_dead,settlement_last_error) "
            f"VALUES (1,2,'{dead_marker}',1,'DELIVERED',DATE_ADD(NOW(3),INTERVAL 1 HOUR),"
            f"DATE_ADD(NOW(3),INTERVAL 1 HOUR),NOW(3),12,1,'monitoring validation'); "
            f"UPDATE t_account SET balance_cents={mint_available - 1} WHERE user_id=1 AND account_type='AVAILABLE'; "
            f"UPDATE t_account SET balance_cents={mint_frozen + 1} WHERE user_id=1 AND account_type='FROZEN'; "
            f"INSERT INTO t_ledger_entry(biz_type,biz_id,user_id,account_type,amount_cents) VALUES "
            f"('MONITOR_SETTLEMENT_DEAD',{dead_ledger_biz_id},1,'AVAILABLE',-1),"
            f"('MONITOR_SETTLEMENT_DEAD',{dead_ledger_biz_id},1,'FROZEN',1)"
        ))
        dead_order_id = int(sql(args.mysql_container,
                                f"SELECT id FROM t_errand_order WHERE title='{dead_marker}'"))
        cleanup.append(
            f"DELETE FROM t_ledger_entry WHERE biz_type='MONITOR_SETTLEMENT_DEAD' AND biz_id={dead_ledger_biz_id}; "
            f"UPDATE t_account SET balance_cents={mint_available} WHERE user_id=1 AND account_type='AVAILABLE'; "
            f"UPDATE t_account SET balance_cents={mint_frozen} WHERE user_id=1 AND account_type='FROZEN'; "
            f"DELETE FROM t_errand_order WHERE id={dead_order_id}"
        )
        settlement_injected_at = utc_now()
        fired = wait_for_alert(args.prometheus, "CampusErrandSettlementRetryExhausted", "firing", args.alert_timeout)
        settlement_result = {"alert": "CampusErrandSettlementRetryExhausted", "fired": fired,
                             "injectedAt": settlement_injected_at}
        settlement_repaired_at = utc_now()
        sql(args.mysql_container, cleanup.pop())
        settlement_result["repairedAt"] = settlement_repaired_at
        settlement_result["resolved"] = wait_for_alert(
            args.prometheus, "CampusErrandSettlementRetryExhausted", "inactive", args.alert_timeout
        )
        alert_results.append(settlement_result)

        result["alerts"] = alert_results
        result["finishedAt"] = utc_now()
    except Exception as exc:
        result["status"] = "FAIL"
        result["error"] = str(exc)
        result["finishedAt"] = utc_now()
    finally:
        if redis_stopped:
            try:
                docker_control(args.redis_container, "start")
                wait_healthy(args.redis_container)
            except Exception:
                pass
        for statement in reversed(cleanup):
            try:
                if statement.startswith("redis-cli "):
                    docker_exec(args.redis_container, statement.split())
                else:
                    sql(args.mysql_container, statement)
            except Exception:
                pass
        output.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
        print(f"monitoring_report={output}")
        print(f"status={result['status']}")

    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
