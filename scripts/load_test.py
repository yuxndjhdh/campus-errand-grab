"""Reproducible HTTP load scenarios for the authenticated API.

The script records raw JSON results under reports/ and deliberately does not import
redis. Redis is an implementation detail; correctness is checked through HTTP and
the reconciliation endpoint.
"""
import argparse
from collections import Counter
import json
import os
import re
import subprocess
import threading
import time
from concurrent.futures import ThreadPoolExecutor

import requests
from requests.adapters import HTTPAdapter


_thread_local = threading.local()
METRIC_FIELDS = ("grabDbCas", "redisFiltered", "rateLimited", "grabAttempts", "grabWinners")
PROMETHEUS_SAMPLE = re.compile(
    r"^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+([-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?)$"
)
RUNTIME_METRIC_NAMES = {
    "redis_lua_calls_total",
    "redis_lua_success_total",
    "redis_lua_failure_total",
    "redis_lua_duration_seconds",
    "redis_lua_duration_seconds_count",
    "redis_lua_duration_seconds_sum",
    "redis_lua_duration_seconds_max",
    "redis_lua_in_flight",
    "redis_lua_concurrency_max",
    "grab_db_cas_duration_seconds_count",
    "grab_db_cas_duration_seconds_sum",
    "grab_db_cas_duration_seconds_max",
    "grab_db_cas_duration_seconds",
    "hikaricp_connections_active",
    "hikaricp_connections_idle",
    "hikaricp_connections_pending",
    "hikaricp_connections_max",
    "hikaricp_connections_min",
    "hikaricp_connections_acquire_seconds_count",
    "hikaricp_connections_acquire_seconds_sum",
    "hikaricp_connections_acquire_seconds_max",
    "jvm_gc_pause_seconds_count",
    "jvm_gc_pause_seconds_sum",
    "jvm_gc_pause_seconds_max",
    "jvm_threads_live_threads",
    "jvm_threads_peak_threads",
    "jvm_threads_daemon_threads",
    "jvm_threads_states_threads",
    "jvm_memory_used_bytes",
    "jvm_memory_committed_bytes",
    "jvm_memory_max_bytes",
    "process_cpu_usage",
    "process_uptime_seconds",
    "system_cpu_usage",
    "system_load_average_1m",
    "executor_active_threads",
    "executor_queued_tasks",
    "executor_pool_size",
    "executor_completed_tasks",
}
RUNTIME_AGGREGATE_NAMES = {
    "jvm_gc_pause_seconds_count",
    "jvm_gc_pause_seconds_sum",
    "jvm_gc_pause_seconds_max",
    "jvm_threads_states_threads",
    "jvm_memory_used_bytes",
    "jvm_memory_committed_bytes",
    "jvm_memory_max_bytes",
}


def http_session():
    session = getattr(_thread_local, "session", None)
    if session is None:
        session = requests.Session()
        adapter = HTTPAdapter(pool_connections=1, pool_maxsize=2, max_retries=0)
        session.mount("http://", adapter)
        session.mount("https://", adapter)
        _thread_local.session = session
    return session


def post(base, path, body=None, token=None, timeout=10):
    start = time.perf_counter()
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    response = http_session().post(base + path, json=body, headers=headers, timeout=timeout)
    elapsed = (time.perf_counter() - start) * 1000
    try:
        payload = response.json()
    except ValueError:
        payload = {"code": "INVALID_JSON"}
    return response.status_code, payload, elapsed


def get(base, path, token=None, timeout=10):
    start = time.perf_counter()
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    response = http_session().get(base + path, headers=headers, timeout=timeout)
    elapsed = (time.perf_counter() - start) * 1000
    try:
        payload = response.json()
    except ValueError:
        payload = {"code": "INVALID_JSON"}
    return response.status_code, payload, elapsed


def parse_prometheus_metrics(text):
    """Keep a low-cardinality allow-list from the application's Prometheus scrape."""
    values = {}
    for line in text.splitlines():
        if not line or line.startswith("#"):
            continue
        match = PROMETHEUS_SAMPLE.match(line.strip())
        if not match:
            continue
        name, labels, raw_value = match.groups()
        if name not in RUNTIME_METRIC_NAMES:
            continue
        try:
            value = float(raw_value)
        except ValueError:
            continue
        if name in RUNTIME_AGGREGATE_NAMES:
            key = name
        elif labels:
            key = f"{name}[{labels[1:-1].replace(chr(34), '')}]"
        else:
            key = name
        values[key] = values.get(key, 0.0) + value if name in RUNTIME_AGGREGATE_NAMES else value
    return values


def parse_percent(value):
    try:
        return float(str(value).rstrip("%"))
    except (TypeError, ValueError):
        return None


def parse_bytes(value):
    match = re.match(r"^\s*([0-9.]+)\s*([kmgtpe]?i?b)?\s*$", str(value), re.IGNORECASE)
    if not match:
        return None
    number = float(match.group(1))
    suffix = (match.group(2) or "b").lower()
    units = {"b": 1, "kb": 1000, "mb": 1000**2, "gb": 1000**3, "tb": 1000**4,
             "kib": 1024, "mib": 1024**2, "gib": 1024**3, "tib": 1024**4}
    return int(number * units.get(suffix, 1))


def capture_container_resources():
    """Capture best-effort Docker CPU and memory facts without making them acceptance gates."""
    try:
        result = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{json .}}"],
            capture_output=True, text=True, timeout=5, check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return {"status": "collection_failed", "reason": f"{type(exc).__name__}: {exc}"}
    if result.returncode != 0:
        return {"status": "collection_failed", "reason": (result.stderr or result.stdout).strip()[:300]}
    containers = {}
    for line in result.stdout.splitlines():
        try:
            item = json.loads(line)
        except json.JSONDecodeError:
            continue
        name = item.get("Name")
        if not name:
            continue
        memory_usage = str(item.get("MemUsage", "")).split("/", 1)
        containers[name] = {
            "cpuPercent": parse_percent(item.get("CPUPerc")),
            "memoryUsageBytes": parse_bytes(memory_usage[0]) if memory_usage else None,
            "memoryLimitBytes": parse_bytes(memory_usage[1]) if len(memory_usage) > 1 else None,
            "memoryPercent": parse_percent(item.get("MemPerc")),
            "pids": int(item["PIDs"]) if str(item.get("PIDs", "")).isdigit() else None,
        }
    return {"status": "complete", "containers": containers}


def capture_runtime_snapshot(base):
    try:
        response = http_session().get(base + "/actuator/prometheus", timeout=5)
        if response.status_code != 200:
            return {
                "status": "collection_failed",
                "reason": f"prometheus endpoint returned HTTP {response.status_code}",
                "containerResources": capture_container_resources(),
            }
        values = parse_prometheus_metrics(response.text)
        required_prefixes = ("redis_lua_calls_total", "redis_lua_duration_seconds_count", "grab_db_cas_duration_seconds_count")
        if not any(any(key.startswith(prefix) for key in values) for prefix in required_prefixes):
            return {
                "status": "collection_failed",
                "reason": "expected Redis Lua and DB CAS metrics were absent from Prometheus scrape",
                "values": values,
                "containerResources": capture_container_resources(),
            }
        return {
            "status": "complete",
            "values": values,
            "containerResources": capture_container_resources(),
        }
    except requests.RequestException as exc:
        return {
            "status": "collection_failed",
            "reason": f"{type(exc).__name__}: {str(exc)[:300]}",
            "containerResources": capture_container_resources(),
        }


class RuntimeSampler:
    def __init__(self, base, interval_seconds=2.0):
        self.base = base
        self.interval_seconds = interval_seconds
        self.samples = []
        self.stop_event = threading.Event()
        self.thread = None

    def start(self):
        self._record()
        self.thread = threading.Thread(target=self._run, name="runtime-metrics-sampler", daemon=True)
        self.thread.start()

    def _run(self):
        while not self.stop_event.wait(self.interval_seconds):
            self._record()

    def _record(self):
        snapshot = capture_runtime_snapshot(self.base)
        snapshot["capturedAtEpochMs"] = int(time.time() * 1000)
        self.samples.append(snapshot)

    def stop(self):
        self.stop_event.set()
        if self.thread is not None:
            self.thread.join(timeout=6)
        self._record()
        successful = [sample for sample in self.samples if sample.get("status") == "complete"]
        aggregate = {}
        for sample in successful:
            for key, value in sample.get("values", {}).items():
                aggregate.setdefault(key, []).append(value)
        summary = {
            key: {
                "min": min(values),
                "max": max(values),
                "mean": sum(values) / len(values),
                "first": values[0],
                "last": values[-1],
            }
            for key, values in aggregate.items()
        }
        status = "complete" if successful and len(successful) == len(self.samples) else (
            "partial" if successful else "collection_failed"
        )
        return {
            "status": status,
            "intervalSeconds": self.interval_seconds,
            "sampleCount": len(self.samples),
            "successfulSamples": len(successful),
            "summary": summary,
            "samples": self.samples,
        }


def register_and_login(base, prefix):
    suffix = str(time.time_ns())
    username = f"{prefix}-{suffix}"
    password = os.getenv("LOAD_PASSWORD", "load-password-123")
    register = post(base, "/api/auth/register", {"username": username, "nickname": prefix, "password": password})
    if register[0] != 200:
        raise RuntimeError(f"register failed: {register}")
    login = post(base, "/api/auth/login", {"username": username, "password": password})
    if login[0] != 200:
        raise RuntimeError(f"login failed: {login}")
    return register[1]["data"]["id"], login[1]["data"]["accessToken"]


def admin_token(base):
    password = os.getenv("ADMIN_PASSWORD", "change-me-admin")
    if not password:
        raise RuntimeError("ADMIN_PASSWORD is empty; set it to the Compose administrator password")
    status, payload, _ = post(base, "/api/auth/login", {
        "username": os.getenv("ADMIN_USERNAME", "admin"), "password": password,
    })
    if status != 200:
        raise RuntimeError(f"admin login failed: {status} {payload}")
    return payload["data"]["accessToken"]


def admin_stats(base):
    status, payload, _ = get(base, "/api/admin/stats", token=admin_token(base))
    if status != 200:
        raise RuntimeError(f"admin stats failed: {status} {payload}")
    return payload["data"]


def capture_metrics(base):
    """Capture the counters needed to explain database and Redis pressure."""
    try:
        stats = admin_stats(base)
        missing = [field for field in METRIC_FIELDS
                   if isinstance(stats.get(field), bool) or not isinstance(stats.get(field), (int, float))]
        if missing:
            return {
                "status": "collection_failed",
                "reason": "admin stats response is missing numeric fields: " + ", ".join(missing),
                "missingFields": missing,
            }
        return {
            "status": "complete",
            "values": {field: int(stats[field]) for field in METRIC_FIELDS},
        }
    except (KeyError, RuntimeError, TypeError, ValueError, requests.RequestException) as exc:
        return {
            "status": "collection_failed",
            "reason": f"{type(exc).__name__}: {str(exc)[:300]}",
        }


def attach_metric_delta(report, before, after):
    """Attach a traceable metric state without turning an unavailable value into zero."""
    if before.get("status") == "complete" and after.get("status") == "complete":
        before_values = before["values"]
        after_values = after["values"]
        deltas = {field: after_values[field] - before_values[field] for field in METRIC_FIELDS}
        negative = [field for field, value in deltas.items() if value < 0]
        if not negative:
            report["metrics"] = {
                "status": "complete",
                "fields": list(METRIC_FIELDS),
                "before": before_values,
                "after": after_values,
                "delta": deltas,
            }
            report["metricsComplete"] = True
            report["dbCasDelta"] = deltas["grabDbCas"]
            report["redisFilteredDelta"] = deltas["redisFiltered"]
            report["rateLimitedDelta"] = deltas["rateLimited"]
            report["grabAttemptsDelta"] = deltas["grabAttempts"]
            report["grabWinnersDelta"] = deltas["grabWinners"]
            return
        reason = "counter reset detected while calculating deltas: " + ", ".join(negative)
    else:
        failures = []
        for phase, snapshot in (("before", before), ("after", after)):
            if snapshot.get("status") != "complete":
                failures.append(f"{phase}: {snapshot.get('reason', 'unknown collection failure')}")
        reason = "; ".join(failures) or "metric snapshots were incomplete"
    report["metrics"] = {"status": "collection_failed", "reason": reason}
    report["metricsComplete"] = False
    report["metricsUnavailable"] = {"status": "collection_failed", "reason": reason}


def admin_reconciliation(base):
    token = admin_token(base)
    last = None
    for attempt in range(5):
        status, payload, elapsed = post(base, "/api/admin/recon/run", {}, token)
        if status != 200:
            raise RuntimeError(f"reconciliation failed: {status} {payload}")
        last = {"elapsedMs": elapsed, "attempt": attempt + 1, **payload["data"]}
        if last.get("passed") is True or attempt == 4:
            return last
        time.sleep(5)
    raise RuntimeError(f"reconciliation failed: {last}")


def load_claim_ttl_seconds():
    return int(os.getenv("LOAD_CLAIM_TTL_SECONDS", "600"))


def setup(base, taker_count=1):
    publisher, publisher_token = register_and_login(base, "load-publisher")
    recharge = post(base, "/api/users/me/recharge", {
        "amountCents": 10_000_000, "idemKey": f"load-recharge-{time.time_ns()}"
    }, publisher_token)
    if recharge[0] != 200:
        raise RuntimeError(f"recharge failed: {recharge}")
    takers = [register_and_login(base, f"load-taker-{index}")[1] for index in range(taker_count)]
    return publisher, publisher_token, takers


def burst(base, clients, taker_count):
    _, publisher_token, taker_tokens = setup(base, max(1, taker_count))
    order = post(base, "/api/orders", {
        "title": "load burst", "rewardCents": 1000, "claimTtlSeconds": load_claim_ttl_seconds(),
    }, publisher_token)
    if order[0] != 200:
        raise RuntimeError(f"order creation failed: {order}")
    order_id = order[1]["data"]["id"]
    barrier = threading.Barrier(clients)

    def call(index):
        barrier.wait()
        return post(base, f"/api/orders/{order_id}/grab", token=taker_tokens[index % len(taker_tokens)])

    before = capture_metrics(base)
    sampler = RuntimeSampler(base)
    sampler.start()
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=clients) as pool:
        results = list(pool.map(call, range(clients)))
    runtime_metrics = sampler.stop()
    duration_ms = (time.perf_counter() - started) * 1000
    after = capture_metrics(base)
    latencies = sorted(item[2] for item in results)
    successes = sum(1 for status, payload, _ in results if status == 200 and payload.get("data", {}).get("won"))
    reconciliation = admin_reconciliation(base)
    report = {
        "scenario": "burst", "orderId": order_id, "clients": clients, "takers": len(taker_tokens), "durationMs": duration_ms,
        "throughputRps": clients / (duration_ms / 1000), "successes": successes,
        "winnerInvariantPassed": successes == 1,
        "rejected": clients - successes, "latencyMs": latency_summary(latencies),
        "httpStatusCounts": dict(Counter(str(item[0]) for item in results)),
        "businessRejectionCounts": business_rejection_counts(results),
        "httpSystemErrorRate": http_system_error_rate(results),
        "reconciliation": reconciliation,
        "runtimeMetrics": runtime_metrics,
    }
    attach_metric_delta(report, before, after)
    print(json.dumps(report, indent=2))
    return report


def mixed(base, orders_count, clients_per_order, taker_count, workers):
    _, publisher_token, taker_tokens = setup(base, max(1, taker_count))
    orders = []
    for index in range(orders_count):
        response = post(base, "/api/orders", {
            "title": f"mixed-{index}", "rewardCents": 1000,
            "claimTtlSeconds": load_claim_ttl_seconds(),
        }, publisher_token)
        if response[0] != 200:
            raise RuntimeError(f"order creation failed: {response}")
        orders.append(response[1]["data"]["id"])
    contenders = orders_count * clients_per_order
    before = capture_metrics(base)
    sampler = RuntimeSampler(base)
    sampler.start()
    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=min(contenders, workers)) as pool:
        futures = [pool.submit(post, base, f"/api/orders/{order}/grab", None,
                                taker_tokens[index % len(taker_tokens)])
                   for index, order in enumerate(order for order in orders for _ in range(clients_per_order))]
        results = [future.result() for future in futures]
    runtime_metrics = sampler.stop()
    duration_ms = (time.perf_counter() - started) * 1000
    after = capture_metrics(base)
    successes = sum(1 for status, payload, _ in results if status == 200 and payload.get("data", {}).get("won"))
    reconciliation = admin_reconciliation(base)
    report = {"scenario": "mixed", "orders": orders_count, "clientsPerOrder": clients_per_order,
              "takers": len(taker_tokens), "contenders": len(results), "successes": successes,
              "expected": orders_count, "winnerInvariantPassed": successes == orders_count,
              "durationMs": duration_ms,
              "workerCount": min(contenders, workers),
              "throughputRps": len(results) / (duration_ms / 1000),
              "latencyMs": latency_summary(sorted(item[2] for item in results)),
              "httpStatusCounts": dict(Counter(str(item[0]) for item in results)),
               "businessRejectionCounts": business_rejection_counts(results),
               "httpSystemErrorRate": http_system_error_rate(results),
               "reconciliation": reconciliation,
               "runtimeMetrics": runtime_metrics}
    attach_metric_delta(report, before, after)
    print(json.dumps(report, indent=2))
    return report


def sustained(base, duration_seconds, clients_per_order, taker_count, workers, interval_ms):
    """Keep creating, racing, and settling orders for a fixed wall-clock window."""
    _, publisher_token, taker_tokens = setup(base, max(1, taker_count))
    deadline = time.monotonic() + duration_seconds
    started = time.perf_counter()
    order_reports = []
    status_counts = Counter()
    rejection_counts = Counter()
    system_errors = 0
    contender_count = 0
    winner_count = 0
    settled_count = 0

    before = capture_metrics(base)
    sampler = RuntimeSampler(base)
    sampler.start()
    with ThreadPoolExecutor(max_workers=min(clients_per_order, workers)) as pool:
        while time.monotonic() < deadline:
            order = post(base, "/api/orders", {
                "title": f"sustained-{len(order_reports)}", "rewardCents": 1000,
                "claimTtlSeconds": load_claim_ttl_seconds(),
            }, publisher_token)
            if order[0] != 200:
                raise RuntimeError(f"order creation failed: {order}")
            order_id = order[1]["data"]["id"]
            contenders = [
                (index, taker_tokens[index % len(taker_tokens)])
                for index in range(clients_per_order)
            ]
            results = list(pool.map(
                lambda item: post(base, f"/api/orders/{order_id}/grab", token=item[1]),
                contenders,
            ))
            contender_count += len(results)
            for status, payload, _ in results:
                status_counts[str(status)] += 1
                if status >= 500:
                    system_errors += 1
                if not (status == 200 and payload.get("data", {}).get("won")):
                    rejection = (payload.get("data") or {}).get("reason") if status == 200 else payload.get("code")
                    rejection_counts[str(rejection or f"HTTP_{status}")] += 1
            winners = [
                (index, result) for index, result in enumerate(results)
                if result[0] == 200 and result[1].get("data", {}).get("won")
            ]
            if len(winners) != 1:
                raise RuntimeError(f"sustained order {order_id} expected one winner, got {len(winners)}")
            winner_count += 1
            winner_index = winners[0][0]
            winner_token = taker_tokens[winner_index % len(taker_tokens)]
            delivered = post(base, f"/api/orders/{order_id}/deliver", token=winner_token)
            if delivered[0] != 200:
                raise RuntimeError(f"settlement failed for order {order_id}: {delivered}")
            settled_count += 1
            order_reports.append({
                "orderId": order_id,
                "contenders": len(results),
                "winnerCount": len(winners),
                "deliveryStatus": delivered[0],
            })
            if interval_ms > 0:
                time.sleep(interval_ms / 1000)

    runtime_metrics = sampler.stop()
    duration_ms = (time.perf_counter() - started) * 1000
    after = capture_metrics(base)
    reconciliation = admin_reconciliation(base)
    report = {
        "scenario": "sustained",
        "durationSeconds": duration_seconds,
        "clientsPerOrder": clients_per_order,
        "takers": len(taker_tokens),
        "orders": len(order_reports),
        "contenders": contender_count,
        "winners": winner_count,
        "settled": settled_count,
        "durationMs": duration_ms,
        "throughputRps": contender_count / (duration_ms / 1000) if duration_ms else 0,
        "orderRatePerSecond": len(order_reports) / (duration_ms / 1000) if duration_ms else 0,
        "httpStatusCounts": dict(status_counts),
        "businessRejectionCounts": dict(rejection_counts),
        "httpSystemErrorRate": system_errors / contender_count if contender_count else 0,
        "winnerInvariantPassed": winner_count == len(order_reports),
        "sampleOrders": order_reports[:20],
        "reconciliation": reconciliation,
        "runtimeMetrics": runtime_metrics,
    }
    attach_metric_delta(report, before, after)
    print(json.dumps(report, indent=2))
    return report


def invariants(base):
    token = admin_token(base)
    status, payload, elapsed = post(base, "/api/admin/recon/run", {}, token)
    if status != 200 or not payload.get("data", {}).get("passed"):
        raise RuntimeError(f"reconciliation failed: {status} {payload}")
    report = {"scenario": "invariants", "status": status, "elapsedMs": elapsed, "response": payload}
    print(json.dumps(report, indent=2))
    return report


def percentile(values, p):
    if not values:
        return 0
    return values[min(len(values) - 1, int(len(values) * p))]


def latency_summary(values):
    return {"p50": percentile(values, .50), "p95": percentile(values, .95),
            "p99": percentile(values, .99), "max": max(values) if values else 0}


def business_rejection_counts(results):
    counts = Counter()
    for status, payload, _ in results:
        data = payload.get("data") or {}
        if status == 200 and data.get("won"):
            continue
        rejection = data.get("reason") if status == 200 else payload.get("code")
        counts[str(rejection or f"HTTP_{status}")] += 1
    return dict(counts)


def http_system_error_rate(results):
    return sum(1 for status, _, _ in results if status >= 500) / len(results) if results else 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8080")
    parser.add_argument("--clients", type=int, default=200)
    parser.add_argument("--phase", choices=["burst", "mixed", "sustained", "invariants"], default="burst")
    parser.add_argument("--orders", type=int, default=20)
    parser.add_argument("--clients-per-order", type=int, default=10)
    parser.add_argument("--takers", type=int, default=1,
                        help="number of taker identities to rotate through; use clients for an unthrottled hot-order race")
    parser.add_argument("--workers", type=int, default=1000,
                        help="maximum worker threads for the mixed phase")
    parser.add_argument("--duration-seconds", type=int, default=300,
                        help="wall-clock duration for the sustained phase")
    parser.add_argument("--interval-ms", type=int, default=0,
                        help="pause between sustained orders")
    parser.add_argument("--output", help="write the report to this exact JSON path")
    args = parser.parse_args()
    if args.clients < 1 or args.orders < 1 or args.clients_per_order < 1:
        parser.error("clients, orders, and clients-per-order must be positive")
    if args.duration_seconds < 1 or args.interval_ms < 0:
        parser.error("duration-seconds must be positive and interval-ms must not be negative")
    if args.takers < 1 or args.workers < 1:
        parser.error("takers and workers must be positive")
    if args.phase == "burst":
        report = burst(args.base, args.clients, args.takers)
    elif args.phase == "mixed":
        report = mixed(base=args.base, orders_count=args.orders, clients_per_order=args.clients_per_order,
                       taker_count=args.takers, workers=args.workers)
    elif args.phase == "sustained":
        report = sustained(base=args.base, duration_seconds=args.duration_seconds,
                           clients_per_order=args.clients_per_order, taker_count=args.takers,
                           workers=args.workers, interval_ms=args.interval_ms)
    else:
        report = invariants(args.base)
    report["functionalPassed"] = (
        report.get("reconciliation", {}).get("passed", True) is True
        and report.get("winnerInvariantPassed", True) is True
        and report.get("httpSystemErrorRate", 0) == 0
    )
    report["passed"] = report["functionalPassed"] and (
        args.phase == "invariants" or report.get("metricsComplete") is True
    )
    path = args.output or os.path.join("reports", f"load-{args.phase}-{time.strftime('%Y%m%d-%H%M%S')}.json")
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    with open(path, "w", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=True, indent=2)
    print(f"raw_report={path}")
    if not report["passed"]:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
