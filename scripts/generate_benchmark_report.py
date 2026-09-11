"""Generate traceable benchmark summaries, SVG charts, and the formal report."""

from __future__ import annotations

import argparse
import csv
import html
import json
import statistics
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = ROOT / "reports" / "benchmarks" / "raw"
REPORT_DIR = ROOT / "reports" / "benchmarks"
CHART_DIR = REPORT_DIR / "charts"
MATRIX = (
    ("hot-500", "on"), ("hot-500", "off"),
    ("hot-1000", "on"), ("hot-1000", "off"),
    ("mixed-1000x10", "on"), ("mixed-1000x10", "off"),
    ("mixed-1000x20", "on"), ("mixed-1000x20", "off"),
    ("mixed-1000x50", "on"), ("mixed-1000x50", "off"),
)


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError(f"{path} is not a JSON object")
    return value


def latest_index() -> Path | None:
    indexes = sorted(RAW_DIR.glob("run-index-*.json"), key=lambda path: path.stat().st_mtime)
    return indexes[-1] if indexes else None


def matrix_paths(index: Path | None) -> list[Path]:
    if index:
        entries = read_json(index).get("runs", [])
        paths = []
        for entry in entries:
            path = Path(str(entry.get("path", "")))
            if not path.is_absolute():
                path = ROOT / path
            if path.exists():
                paths.append(path)
        return paths
    return sorted(RAW_DIR.glob("*.json"), key=lambda path: path.name)


def values(reports: list[dict[str, Any]], field: str) -> list[float]:
    result = []
    for report in reports:
        value = report.get(field)
        if isinstance(value, (int, float)):
            result.append(float(value))
    return result


def median_or_zero(items: list[float]) -> float:
    return statistics.median(items) if items else 0.0


def source_link(path: Path) -> str:
    return f"[raw/{path.name}](raw/{path.name})"


def svg_chart(path: Path, title: str, labels: list[str], series: dict[str, list[float]], suffix: str = "") -> None:
    width, height = 1180, 560
    left, top, right, bottom = 90, 70, 40, 100
    plot_width, plot_height = width - left - right, height - top - bottom
    all_values = [value for items in series.values() for value in items]
    maximum = max(all_values or [1])
    maximum = maximum * 1.15 if maximum > 0 else 1
    colors = ["#0f766e", "#d97706", "#2563eb", "#9333ea"]
    group_width = plot_width / max(1, len(labels))
    bar_width = min(58, group_width / max(1, len(series)) * 0.72)
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">',
        '<rect width="100%" height="100%" fill="#ffffff"/>',
        f'<text x="{left}" y="36" font-family="Arial,sans-serif" font-size="22" font-weight="700" fill="#111827">{html.escape(title)}</text>',
        f'<line x1="{left}" y1="{top + plot_height}" x2="{width - right}" y2="{top + plot_height}" stroke="#374151"/>',
        f'<line x1="{left}" y1="{top}" x2="{left}" y2="{top + plot_height}" stroke="#374151"/>',
        f'<text x="{width - right - 10}" y="{top + plot_height + 38}" text-anchor="end" font-family="Arial,sans-serif" font-size="13" fill="#4b5563">{html.escape(suffix)}</text>',
    ]
    for tick in range(5):
        value = maximum * tick / 4
        y = top + plot_height - plot_height * tick / 4
        parts.append(f'<line x1="{left}" y1="{y:.1f}" x2="{width - right}" y2="{y:.1f}" stroke="#e5e7eb"/>')
        parts.append(f'<text x="{left - 10}" y="{y + 5:.1f}" text-anchor="end" font-family="Arial,sans-serif" font-size="12" fill="#6b7280">{value:.0f}</text>')
    for index, label in enumerate(labels):
        center = left + group_width * (index + 0.5)
        parts.append(f'<text x="{center:.1f}" y="{top + plot_height + 24}" text-anchor="middle" font-family="Arial,sans-serif" font-size="12" fill="#374151">{html.escape(label)}</text>')
        for series_index, (name, items) in enumerate(series.items()):
            value = items[index] if index < len(items) else 0
            x = center - len(series) * bar_width / 2 + series_index * bar_width
            bar_height = plot_height * value / maximum if maximum else 0
            y = top + plot_height - bar_height
            parts.append(f'<rect x="{x:.1f}" y="{y:.1f}" width="{bar_width - 3:.1f}" height="{bar_height:.1f}" fill="{colors[series_index % len(colors)]}" rx="2"/>')
            parts.append(f'<text x="{x + (bar_width - 3) / 2:.1f}" y="{max(top + 13, y - 5):.1f}" text-anchor="middle" font-family="Arial,sans-serif" font-size="11" fill="#111827">{value:.1f}</text>')
    legend_x = left
    for index, name in enumerate(series):
        x = legend_x + index * 180
        parts.append(f'<rect x="{x}" y="{height - 42}" width="12" height="12" fill="{colors[index % len(colors)]}"/>')
        parts.append(f'<text x="{x + 18}" y="{height - 31}" font-family="Arial,sans-serif" font-size="13" fill="#374151">{html.escape(name)}</text>')
    parts.append("</svg>")
    path.write_text("\n".join(parts) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", help="run-index JSON; defaults to the newest index")
    parser.add_argument("--allow-incomplete", action="store_true")
    args = parser.parse_args()
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    CHART_DIR.mkdir(parents=True, exist_ok=True)
    index = Path(args.index) if args.index else latest_index()
    if index and not index.is_absolute():
        index = ROOT / index
    selected_paths = matrix_paths(index)
    selected = [read_json(path) for path in selected_paths]
    groups: dict[tuple[str, str], list[tuple[Path, dict[str, Any]]]] = defaultdict(list)
    for path, report in zip(selected_paths, selected):
        benchmark = report.get("benchmark", {})
        if benchmark.get("scenario") in {name for name, _ in MATRIX} and benchmark.get("prefilter") in {"on", "off"}:
            groups[(str(benchmark["scenario"]), str(benchmark["prefilter"]))].append((path, report))

    matrix_complete = True
    matrix_rows: list[dict[str, Any]] = []
    for scenario, mode in MATRIX:
        entries = groups.get((scenario, mode), [])
        measured = [item for item in entries if not item[1].get("benchmark", {}).get("warmup")]
        warmups = [item for item in entries if item[1].get("benchmark", {}).get("warmup")]
        measured.sort(key=lambda item: int(item[1].get("benchmark", {}).get("run", 0)))
        valid = len(warmups) >= 1 and len(measured) >= 5 and all(item[1].get("passed") is True for item in measured[:5])
        matrix_complete = matrix_complete and valid
        reports = [item[1] for item in measured[:5] if item[1].get("passed") is True]
        row = {
            "scenario": scenario,
            "prefilter": mode,
            "poolSize": reports[0].get("benchmark", {}).get("poolSize", "") if reports else "",
            "warmups": len(warmups),
            "measuredRuns": len(measured),
            "valid": valid,
            "rpsMedian": median_or_zero(values(reports, "throughputRps")),
            "rpsMin": min(values(reports, "throughputRps"), default=0),
            "rpsMax": max(values(reports, "throughputRps"), default=0),
            "p50MedianMs": median_or_zero(values([r.get("latencyMs", {}) for r in reports], "p50")),
            "p95MedianMs": median_or_zero(values([r.get("latencyMs", {}) for r in reports], "p95")),
            "p99MedianMs": median_or_zero(values([r.get("latencyMs", {}) for r in reports], "p99")),
            "maxLatencyMs": max(values([r.get("latencyMs", {}) for r in reports], "max"), default=0),
            "systemErrorRateMax": max(values(reports, "httpSystemErrorRate"), default=0),
            "reconciliationPasses": sum(1 for r in reports if r.get("reconciliation", {}).get("passed") is True),
            "sourceFiles": [item[0].name for item in measured[:5]],
        }
        row["dbCasTotal"] = sum(int(r.get("dbCasDelta", 0)) for r in reports)
        row["redisFilteredTotal"] = sum(int(r.get("redisFilteredDelta", 0)) for r in reports)
        row["filterRate"] = (
            row["redisFilteredTotal"] / (row["dbCasTotal"] + row["redisFilteredTotal"])
            if row["dbCasTotal"] + row["redisFilteredTotal"] else 0
        )
        matrix_rows.append(row)

    special: dict[str, tuple[Path, dict[str, Any]] | None] = {}
    for name in ("sustained", "redis-fault", "settlement-retry"):
        candidates = []
        for path in sorted(RAW_DIR.glob(f"{name}-*.json"), key=lambda item: item.stat().st_mtime, reverse=True):
            report = read_json(path)
            if report.get("passed") is True:
                candidates.append((path, report))
        special[name] = candidates[0] if candidates else None
    sustained_complete = special["sustained"] is not None
    redis_fault_complete = special["redis-fault"] is not None
    settlement_retry_complete = special["settlement-retry"] is not None
    complete = matrix_complete and sustained_complete and redis_fault_complete and settlement_retry_complete
    status = "COMPLETE" if complete else "PARTIAL"
    if not complete and not args.allow_incomplete:
        raise SystemExit(f"benchmark evidence incomplete: matrix={matrix_complete}, sustained={sustained_complete}, redis_fault={redis_fault_complete}, settlement_retry={settlement_retry_complete}")

    csv_path = REPORT_DIR / "summary.csv"
    columns = ["scenario", "prefilter", "poolSize", "warmups", "measuredRuns", "valid", "rpsMedian", "rpsMin", "rpsMax", "p50MedianMs", "p95MedianMs", "p99MedianMs", "maxLatencyMs", "systemErrorRateMax", "reconciliationPasses", "dbCasTotal", "redisFilteredTotal", "filterRate"]
    with csv_path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        writer.writerows({key: row.get(key, "") for key in columns} for row in matrix_rows)
    (REPORT_DIR / "summary.json").write_text(json.dumps({"status": status, "matrix": matrix_rows}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")

    labels = [f"{row['scenario']}\n{row['prefilter']}" for row in matrix_rows]
    svg_chart(CHART_DIR / "rps-by-scenario.svg", "Median throughput by benchmark variant", labels, {
        "RPS": [row["rpsMedian"] for row in matrix_rows],
    }, "requests per second")
    svg_chart(CHART_DIR / "latency-p99-by-scenario.svg", "Median P99 latency by benchmark variant", labels, {
        "P99 ms": [row["p99MedianMs"] for row in matrix_rows],
    }, "milliseconds")
    hot_rows = [row for row in matrix_rows if row["scenario"].startswith("hot-")]
    svg_chart(CHART_DIR / "prefilter-database-pressure.svg", "Hot-order database CAS versus Redis filtering", [row["scenario"] + " " + row["prefilter"] for row in hot_rows], {
        "DB CAS": [row["dbCasTotal"] for row in hot_rows],
        "Redis filtered": [row["redisFilteredTotal"] for row in hot_rows],
    }, "accepted/rejected grab attempts")
    if special["sustained"]:
        sustained_report = special["sustained"][1]
        svg_chart(CHART_DIR / "sustained-load.svg", "Sustained load evidence", ["sustained"], {
            "RPS": [float(sustained_report.get("throughputRps", 0))],
            "orders": [float(sustained_report.get("orders", 0))],
            "settled": [float(sustained_report.get("settled", 0))],
        }, "recorded values")

    failed_raw = []
    for path in sorted(RAW_DIR.glob("*.json")):
        try:
            report = read_json(path)
        except Exception:
            continue
        if (
            report.get("passed") is False
            or report.get("reconciliation", {}).get("passed") is False
            or report.get("winnerInvariantPassed") is False
        ):
            failed_raw.append(path)

    lines = [
        "# Formal Benchmark Report",
        "",
        f"Status: **{status}**",
        "",
        f"Generated at (UTC): {datetime.now(timezone.utc).isoformat()}",
        "",
        "This report is generated from JSON files in `reports/benchmarks/raw/`. Values below are calculated from recorded responses; no estimated performance numbers are used.",
        "",
        "## Scope",
        "",
        "The acceptance matrix uses 500 and 1000 contenders for a single hot order, plus 1000 orders with 10, 20, and 50 contenders per order. Each Redis prefilter variant has one warmup and five measured runs with Hikari pool size 20. Rate limiting was disabled for the benchmark identities so business rejection and system error rates remain separable.",
        "",
        "## Matrix Completeness",
        "",
        "| Scenario | Prefilter | Warmups | Measured | Valid | Median RPS | Median P99 (ms) | Reconciliation |",
        "| --- | --- | ---: | ---: | --- | ---: | ---: | ---: |",
    ]
    for row in matrix_rows:
        lines.append(f"| {row['scenario']} | {row['prefilter']} | {row['warmups']} | {row['measuredRuns']} | {'yes' if row['valid'] else 'no'} | {row['rpsMedian']:.2f} | {row['p99MedianMs']:.2f} | {row['reconciliationPasses']}/5 |")
    lines += [
        "",
        "## Results",
        "",
        "| Scenario | Prefilter | RPS min / median / max | P50 / P95 / P99 median (ms) | Max system error rate | DB CAS | Redis filtered | Filter rate |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in matrix_rows:
        lines.append(f"| {row['scenario']} | {row['prefilter']} | {row['rpsMin']:.2f} / {row['rpsMedian']:.2f} / {row['rpsMax']:.2f} | {row['p50MedianMs']:.2f} / {row['p95MedianMs']:.2f} / {row['p99MedianMs']:.2f} | {row['systemErrorRateMax']:.4f} | {row['dbCasTotal']} | {row['redisFilteredTotal']} | {row['filterRate']:.2%} |")
    lines += [
        "",
        "The business rejection counts and HTTP status distributions remain in each linked raw JSON report. HTTP 5xx responses are counted separately in `httpSystemErrorRate`; 409 responses are business contention outcomes.",
        "",
        "## Sustained Load",
        "",
    ]
    if special["sustained"]:
        path, report = special["sustained"]
        lines += [
            f"The sustained run lasted {report.get('durationSeconds')} seconds, processed {report.get('contenders')} grab attempts across {report.get('orders')} orders, settled {report.get('settled')} orders, and recorded {report.get('throughputRps', 0):.2f} RPS. Its winner invariant and five reconciliation invariants passed. Source: {source_link(path)}.",
        ]
    else:
        lines.append("No passing sustained-load raw report is present yet.")
    lines += ["", "## Redis Fault", ""]
    if special["redis-fault"]:
        path, report = special["redis-fault"]
        metrics = report.get("metricsBefore", {})
        during = report.get("metricsDuring", {})
        lines += [
            f"During the Redis-stop grab race, {report.get('clients')} clients produced exactly {report.get('winnerCount')} winner, with {report.get('httpSystemErrorRate', 0):.4f} HTTP system error rate. `redis_degraded_total` changed from {metrics.get('redisDegraded')} to {during.get('redisDegraded')}; `grab_db_cas_total` changed from {metrics.get('dbCas')} to {during.get('dbCas')}. Recovery and final reconciliation passed. Source: {source_link(path)}.",
        ]
    else:
        lines.append("No passing Redis-fault benchmark raw report is present yet.")
    lines += ["", "## Settlement Retry", ""]
    if special["settlement-retry"]:
        path, report = special["settlement-retry"]
        lines.append(
            f"A temporary role conflict forced the first settlement attempt to remain `DELIVERED`; the scheduled retry then reached `SETTLED`, incremented `settlement_retry_total` from {report.get('retryMetricBefore')} to {report.get('retryMetricAfter')}, and passed reconciliation. Source: {source_link(path)}."
        )
    else:
        lines.append("No passing settlement-retry raw report is present yet.")
    lines += [
        "",
        "## Charts and Traceability",
        "",
        "Charts are generated from the same raw JSON selected by the newest `run-index-*.json` plus the latest passing special-scenario reports:",
        "",
        "- [RPS chart](charts/rps-by-scenario.svg)",
        "- [P99 latency chart](charts/latency-p99-by-scenario.svg)",
        "- [Prefilter database-pressure chart](charts/prefilter-database-pressure.svg)",
        "- [Sustained-load chart](charts/sustained-load.svg)",
        "- [Machine-readable summary](summary.json)",
        "- [CSV summary](summary.csv)",
        "",
        f"Raw matrix index: {source_link(index) if index else 'not available'}.",
        "",
        "## Caveats",
        "",
        "These results describe the recorded Windows Docker Desktop environment and the configured pool size. They are capacity observations for these scenarios, not a universal system limit. Historical failed attempts remain in the raw directory and are intentionally excluded from the formal matrix selection.",
    ]
    if failed_raw:
        lines += ["", "## Historical Failed Samples", "", "The following raw files have `passed: false` and are excluded from the formal results:"]
        lines.extend(f"- {source_link(path)}" for path in failed_raw)
    (REPORT_DIR / "benchmark-report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"benchmark_report={REPORT_DIR / 'benchmark-report.md'}")
    print(f"status={status}")
    return 0 if complete else 1


if __name__ == "__main__":
    raise SystemExit(main())
