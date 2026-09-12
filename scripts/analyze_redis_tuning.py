"""Analyze Redis tuning indexes without hiding failed or noisy runs.

This report is intentionally descriptive. It does not choose a winner when the
observed difference is smaller than the measured run-to-run variation.
"""

from __future__ import annotations

import argparse
import json
import math
import statistics
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]


def resolve(value: str | Path) -> Path:
    path = Path(value)
    return path if path.is_absolute() else ROOT / path


def read(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def stats(values: list[float]) -> dict[str, float | None]:
    if not values:
        return {"min": None, "median": None, "max": None, "stdev": None}
    return {
        "min": min(values),
        "median": statistics.median(values),
        "max": max(values),
        "stdev": statistics.stdev(values) if len(values) > 1 else 0.0,
    }


def value(report: dict[str, Any], key: str, default: float = 0.0) -> float:
    raw = report.get(key, default)
    return float(raw) if isinstance(raw, (int, float)) else default


def p99(report: dict[str, Any]) -> float:
    return value(report.get("latencyMs", {}), "p99")


def runtime_peak(report: dict[str, Any], prefix: str) -> float | None:
    summary = (report.get("runtimeMetrics") or {}).get("summary") or {}
    values = [float(item.get("max", 0.0)) for key, item in summary.items() if key.startswith(prefix)]
    return max(values) if values else None


def svg_bar(path: Path, title: str, labels: list[str], values: list[float], suffix: str) -> None:
    width, height = 900, 420
    max_value = max(values, default=1.0) or 1.0
    bar_width = max(40, (width - 120) // max(1, len(values)) - 16)
    bars = []
    for index, (label, current) in enumerate(zip(labels, values)):
        x = 70 + index * ((width - 100) // max(1, len(values)))
        bar_height = 260 * current / max_value
        y = 330 - bar_height
        bars.append(
            f'<rect x="{x}" y="{y:.1f}" width="{bar_width}" height="{bar_height:.1f}" fill="#2563eb"/>'
            f'<text x="{x + bar_width / 2:.1f}" y="355" text-anchor="middle" font-size="14">{label}</text>'
            f'<text x="{x + bar_width / 2:.1f}" y="{max(20, y - 8):.1f}" text-anchor="middle" font-size="13">{current:.2f}{suffix}</text>'
        )
    svg = f'''<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
<rect width="100%" height="100%" fill="white"/>
<text x="40" y="42" font-size="22" font-family="sans-serif">{title}</text>
<line x1="60" y1="330" x2="860" y2="330" stroke="#334155"/>
{''.join(bars)}
</svg>
'''
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(svg, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", action="append", required=True)
    parser.add_argument("--output-md", default="reports/benchmarks/redis-tuning-report.md")
    parser.add_argument("--output-json", default="reports/benchmarks/raw/redis-tuning-analysis.json")
    parser.add_argument("--charts-dir", default="reports/benchmarks/charts")
    args = parser.parse_args()

    entries: list[tuple[Path, dict[str, Any]]] = []
    for index_value in args.index:
        index_path = resolve(index_value)
        index = read(index_path)
        for item in index.get("runs", []):
            report_path = resolve(item["path"])
            if report_path.exists():
                report = read(report_path)
                if not report.get("benchmark", {}).get("warmup", item.get("warmup", False)):
                    entries.append((report_path, report))

    groups: dict[tuple[str, str, int], list[tuple[Path, dict[str, Any]]]] = defaultdict(list)
    for path, report in entries:
        benchmark = report.get("benchmark", {})
        groups[(str(benchmark.get("scenario", "unknown")), str(benchmark.get("prefilter", "unknown")), int(benchmark.get("poolSize", 0)))].append((path, report))

    rows: list[dict[str, Any]] = []
    for (scenario, prefilter, pool_size), group in sorted(groups.items()):
        rps_values = [value(report, "throughputRps") for _, report in group]
        p99_values = [p99(report) for _, report in group]
        cpu_values = [peak for _, report in group if (peak := runtime_peak(report, "system_cpu_usage")) is not None]
        passed = [report.get("passed") is True for _, report in group]
        rows.append({
            "scenario": scenario,
            "prefilter": prefilter,
            "poolSize": pool_size,
            "runs": len(group),
            "passedRuns": sum(passed),
            "failedRuns": len(group) - sum(passed),
            "rps": stats(rps_values),
            "p99Ms": stats(p99_values),
            "appCpuPercent": stats(cpu_values),
            "dbCas": stats([value(report, "dbCasDelta") for _, report in group]),
            "luaP99Ms": stats([
                float(((report.get("runtimeMetrics") or {}).get("summary") or {}).get(
                    "redis_lua_duration_seconds[script=grab_filter,quantile=0.99]", {}
                ).get("last", 0.0)) * 1000.0
                for _, report in group
            ]),
            "sourceFiles": [path.name for path, _ in group],
        })

    pairs: dict[str, dict[str, dict[str, Any]]] = defaultdict(dict)
    for row in rows:
        pairs[row["scenario"]][row["prefilter"]] = row
    conclusions = []
    for scenario, pair in sorted(pairs.items()):
        on, off = pair.get("on"), pair.get("off")
        if not on or not off:
            conclusions.append({"scenario": scenario, "conclusion": "incomplete_pair"})
            continue
        on_rps, off_rps = on["rps"]["median"], off["rps"]["median"]
        on_p99, off_p99 = on["p99Ms"]["median"], off["p99Ms"]["median"]
        pooled_noise = max(on["rps"]["stdev"] or 0.0, off["rps"]["stdev"] or 0.0)
        delta = ((on_rps / off_rps) - 1.0) * 100 if off_rps else 0.0
        p99_delta = ((on_p99 / off_p99) - 1.0) * 100 if off_p99 else 0.0
        conclusion = "no_stable_improvement_observed" if abs(on_rps - off_rps) <= pooled_noise else (
            "redis_on_higher_rps" if delta > 0 else "redis_on_lower_rps"
        )
        conclusions.append({
            "scenario": scenario,
            "rpsDeltaPercent": delta,
            "p99DeltaPercent": p99_delta,
            "maxRpsStdev": pooled_noise,
            "conclusion": conclusion,
            "correctnessPreserved": all(row["passedRuns"] == row["runs"] for row in (on, off)),
        })

    result = {
        "status": "COMPLETE" if rows else "PARTIAL",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "indexes": args.index,
        "rows": rows,
        "conclusions": conclusions,
        "failedSamplesRetained": True,
    }
    output_json = resolve(args.output_json)
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# Redis Tuning Analysis",
        "",
        f"Status: **{result['status']}**",
        "",
        "This report includes min/median/max/stdev for every selected measured run and retains failed samples. The conclusion is local Windows Docker evidence, not a production capacity claim.",
        "",
        "| Scenario | Mode | Runs | Passed | RPS min/median/max | P99 min/median/max ms | RPS stdev | DB CAS median | App CPU max % |",
        "| --- | --- | ---: | ---: | --- | --- | ---: | ---: | ---: |",
    ]
    for row in rows:
        def fmt(metric: str, digits: int = 2) -> str:
            item = row[metric]
            return "/".join("n/a" if item[key] is None else f"{item[key]:.{digits}f}" for key in ("min", "median", "max"))
        lines.append(
            f"| {row['scenario']} | {row['prefilter']} | {row['runs']} | {row['passedRuns']}/{row['runs']} | "
            f"{fmt('rps')} | {fmt('p99Ms')} | {row['rps']['stdev']:.2f} | {row['dbCas']['median']:.0f} | "
            f"{row['appCpuPercent']['max'] if row['appCpuPercent']['max'] is not None else 'n/a'} |"
        )
    lines += ["", "## Conclusions", ""]
    for conclusion in conclusions:
        lines.append(f"- `{conclusion['scenario']}`: `{conclusion['conclusion']}`; RPS delta {conclusion.get('rpsDeltaPercent', 0.0):+.2f}%, P99 delta {conclusion.get('p99DeltaPercent', 0.0):+.2f}%; correctness preserved={conclusion.get('correctnessPreserved', False)}.")
    lines += ["", "## Charts", "", "Charts are descriptive and generated from the same raw JSON inputs.", ""]
    charts_dir = resolve(args.charts_dir)
    for scenario in sorted(pairs):
        pair = pairs[scenario]
        labels, rps, p99_values = [], [], []
        for mode in ("on", "off"):
            if mode in pair:
                labels.append(mode)
                rps.append(float(pair[mode]["rps"]["median"] or 0.0))
                p99_values.append(float(pair[mode]["p99Ms"]["median"] or 0.0))
        if labels:
            rps_path = charts_dir / f"redis-tuning-{scenario}-rps.svg"
            p99_path = charts_dir / f"redis-tuning-{scenario}-p99.svg"
            svg_bar(rps_path, f"{scenario} median RPS", labels, rps, "")
            svg_bar(p99_path, f"{scenario} median P99", labels, p99_values, " ms")
            lines.extend([f"- `{rps_path.relative_to(ROOT).as_posix()}`", f"- `{p99_path.relative_to(ROOT).as_posix()}`"])
    output_md = resolve(args.output_md)
    output_md.parent.mkdir(parents=True, exist_ok=True)
    output_md.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"redis_tuning_json={output_json}")
    print(f"redis_tuning_report={output_md}")
    return 0 if rows else 1


if __name__ == "__main__":
    raise SystemExit(main())
