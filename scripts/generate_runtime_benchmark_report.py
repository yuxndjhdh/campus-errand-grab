"""Summarize runtime telemetry from a benchmark run index.

The report is intentionally separate from the formal benchmark report because a
targeted diagnostic matrix must not replace the repository's complete acceptance
matrix. It reports both positive and negative A/B outcomes.
"""

from __future__ import annotations

import argparse
import json
import statistics
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = ROOT / "reports" / "benchmarks" / "raw"
DEFAULT_OUTPUT = ROOT / "reports" / "benchmarks" / "runtime-bottleneck-report.md"


def resolve_path(value: str | Path) -> Path:
    path = Path(value)
    return path if path.is_absolute() else ROOT / path


def read_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def median(values: list[float]) -> float:
    return statistics.median(values) if values else 0.0


def runtime_value(summary: dict, prefix: str, **labels: str) -> float | None:
    for key, value in summary.items():
        if not key.startswith(prefix):
            continue
        if all(f"{name}={expected}" in key for name, expected in labels.items()):
            return float(value.get("last", 0.0))
    return None


def runtime_delta(summary: dict, prefix: str, **labels: str) -> float | None:
    for key, value in summary.items():
        if not key.startswith(prefix):
            continue
        if all(f"{name}={expected}" in key for name, expected in labels.items()):
            return float(value.get("last", 0.0)) - float(value.get("first", 0.0))
    return None


def report_metrics(report: dict) -> dict:
    runtime = report.get("runtimeMetrics") or {}
    summary = runtime.get("summary") or {}
    samples = runtime.get("samples") or []
    container_cpu = []
    container_memory = []
    for sample in samples:
        for name, container in (sample.get("containerResources") or {}).get("containers", {}).items():
            if "-app-" not in name and not name.endswith("-app"):
                continue
            if isinstance(container.get("cpuPercent"), (int, float)):
                container_cpu.append(float(container["cpuPercent"]))
            if isinstance(container.get("memoryUsageBytes"), (int, float)):
                container_memory.append(float(container["memoryUsageBytes"]))
    return {
        "telemetryStatus": runtime.get("status", "not_collected"),
        "sampleCount": runtime.get("sampleCount", 0),
        "redisLuaCalls": runtime_delta(summary, "redis_lua_calls_total", script="grab_filter"),
        "redisLuaFailures": runtime_delta(summary, "redis_lua_failure_total", script="grab_filter"),
        "redisLuaP50Ms": _seconds_to_ms(runtime_value(summary, "redis_lua_duration_seconds", script="grab_filter", quantile="0.5")),
        "redisLuaP95Ms": _seconds_to_ms(runtime_value(summary, "redis_lua_duration_seconds", script="grab_filter", quantile="0.95")),
        "redisLuaP99Ms": _seconds_to_ms(runtime_value(summary, "redis_lua_duration_seconds", script="grab_filter", quantile="0.99")),
        "redisLuaConcurrencyMax": _max_summary(summary, "redis_lua_concurrency_max"),
        "dbCasP50Ms": _seconds_to_ms(runtime_value(summary, "grab_db_cas_duration_seconds", quantile="0.5")),
        "dbCasP95Ms": _seconds_to_ms(runtime_value(summary, "grab_db_cas_duration_seconds", quantile="0.95")),
        "dbCasP99Ms": _seconds_to_ms(runtime_value(summary, "grab_db_cas_duration_seconds", quantile="0.99")),
        "hikariActiveMax": _max_summary(summary, "hikaricp_connections_active"),
        "hikariPendingMax": _max_summary(summary, "hikaricp_connections_pending"),
        "gcPauseSumSeconds": _max_summary(summary, "jvm_gc_pause_seconds_sum"),
        "containerCpuMaxPercent": max(container_cpu, default=None),
        "containerMemoryMaxBytes": max(container_memory, default=None),
        "dbCas": int(report.get("dbCasDelta", 0)),
        "redisFiltered": int(report.get("redisFilteredDelta", 0)),
        "winners": int(report.get("grabWinnersDelta", report.get("successes", 0))),
        "systemErrorRate": float(report.get("httpSystemErrorRate", 0.0)),
        "reconciliationPassed": report.get("reconciliation", {}).get("passed") is True,
        "winnerInvariantPassed": report.get("winnerInvariantPassed") is True,
    }


def _seconds_to_ms(value: float | None) -> float | None:
    return None if value is None else value * 1000.0


def _max_summary(summary: dict, prefix: str) -> float | None:
    values = [float(value.get("max", 0.0)) for key, value in summary.items() if key.startswith(prefix)]
    return max(values) if values else None


def format_number(value: float | int | None, digits: int = 2) -> str:
    return "not collected" if value is None else f"{value:.{digits}f}"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", action="append", required=True, help="run-index JSON produced by scripts/run_benchmarks.py; repeat to merge diagnostic scenarios")
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    parser.add_argument("--allow-incomplete", action="store_true")
    args = parser.parse_args()

    index_paths = [resolve_path(value) for value in args.index]
    entries = []
    for index_path in index_paths:
        index = read_json(index_path)
        for item in index.get("runs", []):
            path = resolve_path(item["path"])
            if not path.exists():
                continue
            report = read_json(path)
            benchmark = report.get("benchmark", {})
            if benchmark.get("warmup") is False:
                entries.append((path, report))

    groups = defaultdict(list)
    for path, report in entries:
        benchmark = report.get("benchmark", {})
        groups[(benchmark.get("scenario"), benchmark.get("prefilter"), benchmark.get("poolSize"))].append((path, report))

    rows = []
    complete = True
    for key, group in sorted(groups.items()):
        scenario, prefilter, pool_size = key
        reports = [report for _, report in sorted(group, key=lambda item: item[1].get("benchmark", {}).get("run", 0))]
        telemetry = [report_metrics(report) for report in reports]
        valid = len(reports) >= 5 and all(report.get("passed") is True for report in reports) and all(
            item["telemetryStatus"] == "complete" for item in telemetry
        )
        complete = complete and valid
        rows.append({
            "scenario": scenario,
            "prefilter": prefilter,
            "poolSize": pool_size,
            "measuredRuns": len(reports),
            "valid": valid,
            "rpsMedian": median([float(report.get("throughputRps", 0.0)) for report in reports]),
            "p99MedianMs": median([float((report.get("latencyMs") or {}).get("p99", 0.0)) for report in reports]),
            "luaCallsMedian": median([item["redisLuaCalls"] for item in telemetry if item["redisLuaCalls"] is not None]) if telemetry else None,
            "luaFailuresMax": max((item["redisLuaFailures"] for item in telemetry if item["redisLuaFailures"] is not None), default=None),
            "luaP50MedianMs": median([item["redisLuaP50Ms"] for item in telemetry if item["redisLuaP50Ms"] is not None]),
            "luaP95MedianMs": median([item["redisLuaP95Ms"] for item in telemetry if item["redisLuaP95Ms"] is not None]),
            "luaP99MedianMs": median([item["redisLuaP99Ms"] for item in telemetry if item["redisLuaP99Ms"] is not None]),
            "dbCasP99MedianMs": median([item["dbCasP99Ms"] for item in telemetry if item["dbCasP99Ms"] is not None]),
            "hikariPendingMax": max((item["hikariPendingMax"] for item in telemetry if item["hikariPendingMax"] is not None), default=None),
            "redisLuaConcurrencyMax": max((item["redisLuaConcurrencyMax"] for item in telemetry if item["redisLuaConcurrencyMax"] is not None), default=None),
            "containerCpuMaxPercent": max((item["containerCpuMaxPercent"] for item in telemetry if item["containerCpuMaxPercent"] is not None), default=None),
            "dbCasTotal": sum(item["dbCas"] for item in telemetry),
            "redisFilteredTotal": sum(item["redisFiltered"] for item in telemetry),
            "winnerTotal": sum(item["winners"] for item in telemetry),
            "systemErrorRateMax": max((item["systemErrorRate"] for item in telemetry), default=0.0),
            "reconciliationPasses": sum(1 for item in telemetry if item["reconciliationPassed"]),
            "winnerInvariantPasses": sum(1 for item in telemetry if item["winnerInvariantPassed"]),
            "sourceFiles": [path.name for path, _ in group],
        })

    status = "COMPLETE" if complete and rows else "PARTIAL"
    lines = [
        "# Redis Runtime Bottleneck Report",
        "",
        f"Status: **{status}**",
        "",
        f"Generated at (UTC): {datetime.now(timezone.utc).isoformat()}",
        "",
        "This diagnostic report is generated from the selected run index and its raw JSON files. It is separate from the formal acceptance matrix.",
        "",
        "## Scope",
        "",
        "The comparison keeps scenario, Hikari pool size, worker count, identity count, rate-limit setting and test data shape fixed; only the Redis prefilter mode changes within each pair. Each complete variant requires one warmup and five measured runs. Results remain local Windows Docker observations, not production SLOs.",
        "",
        "## A/B Results",
        "",
        "| Scenario | Prefilter | Runs | Valid | Median RPS | Median P99 ms | Lua calls | Lua failures max | Lua P50/P95/P99 ms | DB CAS P99 ms | Hikari pending max | Lua concurrency max | App CPU max % | DB CAS total | Redis filtered | 5xx max | Reconcile | Winners |",
        "| --- | --- | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in rows:
        lua_latency = "/".join(format_number(row[key]) for key in ("luaP50MedianMs", "luaP95MedianMs", "luaP99MedianMs"))
        lines.append(
            f"| {row['scenario']} | {row['prefilter']} | {row['measuredRuns']} | {'yes' if row['valid'] else 'no'} | "
            f"{row['rpsMedian']:.2f} | {row['p99MedianMs']:.2f} | {format_number(row['luaCallsMedian'], 0)} | "
            f"{format_number(row['luaFailuresMax'], 0)} | {lua_latency} | {format_number(row['dbCasP99MedianMs'])} | "
            f"{format_number(row['hikariPendingMax'])} | {format_number(row['redisLuaConcurrencyMax'])} | {format_number(row['containerCpuMaxPercent'])} | {row['dbCasTotal']} | "
            f"{row['redisFilteredTotal']} | {row['systemErrorRateMax']:.4f} | {row['reconciliationPasses']}/5 | {row['winnerInvariantPasses']}/5 |"
        )
    lines += [
        "",
        "## Pair Comparison",
        "",
    ]
    pairs = defaultdict(dict)
    for row in rows:
        pairs[row["scenario"]][row["prefilter"]] = row
    for scenario, pair in sorted(pairs.items()):
        on = pair.get("on")
        off = pair.get("off")
        if not on or not off:
            lines.append(f"- `{scenario}`: one Redis mode is missing; no A/B conclusion is emitted.")
            continue
        rps_change = ((on["rpsMedian"] / off["rpsMedian"]) - 1.0) * 100 if off["rpsMedian"] else 0.0
        p99_change = ((on["p99MedianMs"] / off["p99MedianMs"]) - 1.0) * 100 if off["p99MedianMs"] else 0.0
        lines.append(
            f"- `{scenario}`: Redis on versus off changed median RPS by {rps_change:+.2f}% "
            f"({on['rpsMedian']:.2f} vs {off['rpsMedian']:.2f}) and median P99 by {p99_change:+.2f}% "
            f"({on['p99MedianMs']:.2f} ms vs {off['p99MedianMs']:.2f} ms). "
            f"The Redis filter P99 was {format_number(on['luaP99MedianMs'])} ms, DB CAS P99 was {format_number(on['dbCasP99MedianMs'])} ms, "
            f"and Hikari pending peaked at {format_number(on['hikariPendingMax'])}. The pair retained DB CAS totals "
            f"{on['dbCasTotal']} vs {off['dbCasTotal']}, Redis-filtered totals {on['redisFilteredTotal']} vs {off['redisFilteredTotal']}, "
            f"HTTP 5xx max {on['systemErrorRateMax']:.4f}, and reconciliation {on['reconciliationPasses']}/5; these observations do not alone establish a single bottleneck."
        )
    lines += [
        "",
        "## Interpretation",
        "",
        "Lua call latency, DB CAS latency, Hikari pending connections, JVM/GC metrics and container CPU/memory are recorded in each raw JSON `runtimeMetrics` block. A missing scrape or unavailable Docker stats is retained as a collection status and is never converted into zero.",
        "",
        "This report identifies waiting-time candidates; it does not claim that Redis has been optimized. A positive or negative RPS result must be interpreted together with DB CAS reduction, single-winner correctness, HTTP system errors and reconciliation. The CPU column is for the App container only; other Compose services are not added to it.",
        "",
        "Run indexes: " + ", ".join(f"`{path}`" for path in index_paths) + ".",
        "",
        "## Raw Files",
        "",
    ]
    for row in rows:
        lines.append(f"- `{row['scenario']}-{row['prefilter']}`: " + ", ".join(f"`{name}`" for name in row["sourceFiles"]))
    output = resolve_path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"runtime_benchmark_report={output}")
    print(f"status={status}")
    if status != "COMPLETE" and not args.allow_incomplete:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
