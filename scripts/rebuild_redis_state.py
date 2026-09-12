"""Rebuild Redis marker and delay-index state from MySQL authoritative rows."""

from __future__ import annotations

import argparse
import os
import subprocess
from datetime import datetime, timezone


def run(command: list[str], password: str | None = None) -> str:
    env = os.environ.copy()
    if password is not None:
        env["MYSQL_PWD"] = password
    result = subprocess.run(command, capture_output=True, text=True, check=True, env=env)
    return result.stdout.strip()


def delete_pattern(redis: list[str], pattern: str) -> int:
    keys = [key for key in run(redis + ["--scan", "--pattern", pattern]).splitlines() if key]
    if keys:
        run(redis + ["DEL", *keys])
    return len(keys)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mysql-container", default="campus-errand-grab-mysql-1")
    parser.add_argument("--redis-container", default="campus-errand-grab-redis-1")
    parser.add_argument("--database", default="campus_errand")
    args = parser.parse_args()
    password = os.getenv("DB_PASSWORD", "change-me")
    mysql = ["docker", "exec", "-e", f"MYSQL_PWD={password}", args.mysql_container, "mysql", "-uroot", "-N", "-B", args.database, "-e"]
    redis = ["docker", "exec", args.redis_container, "redis-cli"]
    cleared_stock = delete_pattern(redis, "grab:stock:*")
    cleared_known = delete_pattern(redis, "grab:known:*")
    run(redis + ["DEL", "delay:claim", "delay:deliver"])
    rows = run(mysql + [
        "SELECT id,publisher_id,UNIX_TIMESTAMP(claim_deadline_at)*1000,"
        "IFNULL(UNIX_TIMESTAMP(deliver_deadline_at)*1000,0),status "
        "FROM t_errand_order WHERE status IN ('PUBLISHED','TAKEN','DELIVERED')"
    ], password)
    marker_count = 0
    delay_count = 0
    for row in filter(None, rows.splitlines()):
        order_id, publisher_id, claim_ms, deliver_ms, status = row.split("\t")
        if status == "PUBLISHED":
            run(redis + ["SET", f"grab:stock:{order_id}", f"{claim_ms}|{publisher_id}"])
            run(redis + ["SET", f"grab:known:{order_id}", "1"])
            run(redis + ["ZADD", "delay:claim", claim_ms, f"CLAIM:{order_id}"])
            marker_count += 1
            delay_count += 1
        elif status in {"TAKEN", "DELIVERED"} and float(deliver_ms) > 0:
            run(redis + ["ZADD", "delay:deliver", deliver_ms, f"DELIVER:{order_id}"])
            delay_count += 1
    print({
        "status": "PASS",
        "rebuiltAt": datetime.now(timezone.utc).isoformat(),
        "markerCount": marker_count,
        "delayIndexCount": delay_count,
        "clearedStaleStockMarkers": cleared_stock,
        "clearedStaleKnownMarkers": cleared_known,
        "source": "mysql-authoritative-order-state",
    })
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
