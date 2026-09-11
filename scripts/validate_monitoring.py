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
    "outbox_pending",
    "outbox_dead",
    "timeout_queue_lag_seconds",
    "recon_failure_total",
    "recon_last_success_timestamp",
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
        fired = wait_for_alert(args.prometheus, "CampusErrandOutboxDead", "firing", args.alert_timeout)
        alert_results.append({"alert": "CampusErrandOutboxDead", "fired": fired})
        sql(args.mysql_container, cleanup.pop())
        alert_results[-1]["resolved"] = wait_for_alert(
            args.prometheus, "CampusErrandOutboxDead", "inactive", args.alert_timeout
        )

        account_before = int(sql(
            args.mysql_container,
            "SELECT balance_cents FROM t_account WHERE user_id=1 AND account_type='AVAILABLE'",
        ))
        sql(args.mysql_container, "UPDATE t_account SET balance_cents=balance_cents+1 WHERE user_id=1 AND account_type='AVAILABLE'")
        cleanup.append(
            f"UPDATE t_account SET balance_cents={account_before} WHERE user_id=1 AND account_type='AVAILABLE'"
        )
        recon_failure = recon(args.base, token)
        if recon_failure.get("passed") is not False:
            raise RuntimeError("reconciliation drift injection did not fail")
        fired = wait_for_alert(args.prometheus, "CampusErrandReconFailure", "firing", args.alert_timeout)
        alert_results.append({"alert": "CampusErrandReconFailure", "fired": fired,
                              "reconciliationDuringFault": recon_failure})
        sql(args.mysql_container, cleanup.pop())
        recon_recovered = recon(args.base, token)
        if recon_recovered.get("passed") is not True:
            raise RuntimeError("reconciliation did not recover after drift repair")
        alert_results[-1]["resolved"] = wait_for_alert(
            args.prometheus, "CampusErrandReconFailure", "inactive", args.alert_timeout
        )

        invalid_member = f"CLAIM:monitor-invalid-{int(time.time())}"
        past_ms = int(time.time() * 1000) - 120_000
        docker_exec(args.redis_container, ["redis-cli", "ZADD", "delay:claim", str(past_ms), invalid_member])
        cleanup.append(f"redis-cli ZREM delay:claim {invalid_member}")
        fired = wait_for_alert(args.prometheus, "CampusErrandTimeoutQueueLagHigh", "firing", args.alert_timeout)
        alert_results.append({"alert": "CampusErrandTimeoutQueueLagHigh", "fired": fired})
        docker_exec(args.redis_container, ["redis-cli", "ZREM", "delay:claim", invalid_member])
        cleanup.pop()
        alert_results[-1]["resolved"] = wait_for_alert(
            args.prometheus, "CampusErrandTimeoutQueueLagHigh", "inactive", args.alert_timeout
        )

        result["alerts"] = alert_results
        result["finishedAt"] = utc_now()
    except Exception as exc:
        result["status"] = "FAIL"
        result["error"] = str(exc)
        result["finishedAt"] = utc_now()
    finally:
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
