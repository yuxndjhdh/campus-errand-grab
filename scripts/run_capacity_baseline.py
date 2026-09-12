"""Run an isolated capacity baseline or emit a conservative prerequisite report.

Measured mode mutates a disposable application environment and requires distinct
service and load-generator host identifiers. Dry-run mode is read-only and records
why no capacity claim can be made on the current workstation.
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
CAPACITY_DIR = ROOT / "reports" / "capacity"
RAW_DIR = CAPACITY_DIR / "raw"
CHART_DIR = CAPACITY_DIR / "charts"


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def command_output(command: list[str]) -> str:
    if shutil.which(command[0]) is None:
        return "not installed"
    try:
        process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired) as exc:
        return f"unavailable: {exc}"
    output = "\n".join(part for part in (process.stdout, process.stderr) if part).strip()
    return output or f"exit code {process.returncode}"


def positive_ints(value: str) -> list[int]:
    try:
        values = [int(item.strip()) for item in value.split(",") if item.strip()]
    except ValueError as exc:
        raise argparse.ArgumentTypeError("levels must contain positive integers") from exc
    if not values or any(item < 1 for item in values):
        raise argparse.ArgumentTypeError("levels must contain positive integers")
    return values


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return {"error": str(exc)}
    return value if isinstance(value, dict) else {"error": "report is not an object"}


def measured_passed(report: dict[str, Any], p99_limit_ms: float) -> bool:
    latency = report.get("latencyMs") or {}
    return (
        report.get("processReturnCode", 0) == 0
        and report.get("reconciliation", {}).get("passed", True) is True
        and report.get("winnerInvariantPassed", True) is True
        and float(report.get("httpSystemErrorRate", 0.0)) == 0.0
        and float(latency.get("p99", float("inf"))) <= p99_limit_ms
    )


def svg(path: Path, title: str, labels: list[str], values: list[float], suffix: str) -> None:
    if values:
        maximum = max(values) or 1.0
        slot = 780 / len(values)
        bars = []
        for index, (label, value) in enumerate(zip(labels, values)):
            x = 90 + index * slot
            width = max(24.0, slot - 24.0)
            height = 260 * value / maximum
            y = 350 - height
            bars.append(
                f'<rect x="{x:.1f}" y="{y:.1f}" width="{width:.1f}" height="{height:.1f}" fill="#0f766e"/>'
                f'<text x="{x + width / 2:.1f}" y="378" text-anchor="middle" font-size="13">{label}</text>'
                f'<text x="{x + width / 2:.1f}" y="{max(24, y - 8):.1f}" text-anchor="middle" font-size="13">{value:.2f}{suffix}</text>'
            )
        body = "".join(bars)
    else:
        body = '<text x="480" y="220" text-anchor="middle" font-size="22">No isolated measurements</text>'
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        f'<svg xmlns="http://www.w3.org/2000/svg" width="960" height="440" viewBox="0 0 960 440">'
        f'<rect width="100%" height="100%" fill="white"/>'
        f'<text x="40" y="42" font-size="22">{title}</text>'
        f'<line x1="70" y1="350" x2="900" y2="350" stroke="#334155"/>{body}</svg>\n',
        encoding="utf-8",
    )


def write_environment(args: argparse.Namespace, status: str, reason: str) -> None:
    lines = [
        "# Capacity Baseline Environment",
        "",
        "This file records non-secret host facts and capacity-test prerequisites.",
        f"Status: **{status}**",
        f"Captured at (UTC): {utc_now()}",
        "",
        "## Isolation",
        "",
        f"- Service host identifier: {args.service_host_id or 'not provided'}",
        f"- Load-generator host identifier: {args.press_host_id or 'not provided'}",
        f"- Distinct identifiers supplied: {bool(args.service_host_id and args.press_host_id and args.service_host_id != args.press_host_id)}",
        f"- Decision: {reason}",
        "",
        "## Host Facts",
        "",
        f"- OS: {platform.platform()}",
        f"- Architecture: {platform.machine()}",
        f"- Logical CPUs: {os.cpu_count() or 'unknown'}",
        "",
        "docker version:",
        command_output(["docker", "version"]),
        "",
        "docker compose version:",
        command_output(["docker", "compose", "version"]),
        "",
        "## Fixed Test Contract",
        "",
        f"- Scenario: {args.phase}",
        f"- Load levels: {', '.join(str(item) for item in args.levels)}",
        f"- Measured repetitions per level: {args.runs}",
        f"- Warmup repetitions per level: {args.warmup_runs}",
        f"- P99 limit (target): {args.p99_limit_ms} ms",
        "- HTTP 5xx limit (target): 0",
        "- Correctness constraints: single winner and reconciliation pass",
        f"- Stability duration target: {args.stability_seconds} seconds",
        "",
        "A same-host Docker result is not a pre-production capacity baseline.",
    ]
    CAPACITY_DIR.mkdir(parents=True, exist_ok=True)
    (CAPACITY_DIR / "environment.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def write_report(args: argparse.Namespace, status: str, reason: str,
                 rows: list[dict[str, Any]], stability: dict[str, Any] | None) -> None:
    lines = [
        "# Capacity Baseline",
        "",
        f"Status: **{status}**",
        "",
        "Targets and measurements are kept separate. Failed levels remain in raw JSON and are not omitted.",
        "",
        "## Prerequisites",
        "",
        f"- {reason}",
        f"- Press/service isolation: {'provided' if args.service_host_id and args.press_host_id and args.service_host_id != args.press_host_id else 'NOT_RUN'}",
        f"- Fixed resources recorded: {'provided' if args.resources_recorded else 'NOT_RUN'}",
        "",
        "## Declared SLO Targets",
        "",
        f"- P99 <= {args.p99_limit_ms} ms",
        "- HTTP 5xx rate = 0",
        "- Single-winner invariant = true",
        "- Reconciliation = true",
        f"- Stability window = {args.stability_seconds} seconds",
        "",
        "## Measured Levels",
        "",
        "| Level | Repetitions | Passed | Median RPS | Median P99 ms | First violation |",
        "| ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    if rows:
        for row in rows:
            lines.append(
                f"| {row['level']} | {row['repetitions']} | {row['passedRepetitions']}/{row['repetitions']} | "
                f"{row['medianRps']} | {row['medianP99Ms']} | "
                f"{'yes' if row['firstViolation'] is not None else 'no'} |"
            )
    else:
        lines.append("| n/a | 0 | 0/0 | n/a | n/a | not measured |")
    lines += [
        "",
        "## Stability",
        "",
        f"- Result: {stability.get('status') if stability else 'NOT_RUN'}",
        f"- Duration seconds: {stability.get('durationSeconds') if stability else 'not measured'}",
        "",
        "## Conclusion",
        "",
    ]
    if status == "COMPLETE":
        stable = [row for row in rows if row["passed"]]
        best = max(stable, key=lambda item: float(item["medianRps"]))
        lines.append(
            f"- Maximum measured stable level: {best['level']} at median RPS {best['medianRps']}; "
            "scope is limited to the recorded isolated environment."
        )
    else:
        lines.append(
            "- No pre-production capacity claim is made because the required isolation, fixed-resource record, "
            "repeated levels, or stability window is incomplete."
        )
    lines += [
        "",
        "## Charts",
        "",
        "- reports/capacity/charts/capacity-rps.svg",
        "- reports/capacity/charts/capacity-p99.svg",
        "",
        "## Evidence",
        "",
        "- Raw JSON files are stored under reports/capacity/raw/.",
        "- This report does not replace the formal Redis A/B or payment replay evidence.",
    ]
    (CAPACITY_DIR / "capacity-baseline.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def run_level(args: argparse.Namespace, level: int, run_number: int, warmup: bool) -> dict[str, Any]:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    output = RAW_DIR / f"capacity-{args.phase}-level{level}-{'warmup' if warmup else 'run'}{run_number}-{stamp}.json"
    command = [
        sys.executable, str(ROOT / "scripts" / "load_test.py"),
        "--base", args.base, "--phase", args.phase, "--output", str(output),
        "--takers", str(args.takers), "--workers", str(args.workers),
    ]
    if args.phase == "burst":
        command.extend(["--clients", str(level)])
    elif args.phase == "mixed":
        command.extend(["--orders", str(args.orders), "--clients-per-order", str(level)])
    else:
        command.extend(["--duration-seconds", str(args.short_duration_seconds), "--clients-per-order", str(level)])
    process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=False)
    report = read_json(output) if output.exists() else {}
    report["capacityRun"] = {"level": level, "run": run_number, "warmup": warmup, "finishedAt": utc_now()}
    report["processReturnCode"] = process.returncode
    report["passed"] = measured_passed(report, args.p99_limit_ms)
    if process.stderr.strip():
        report["stderrTail"] = process.stderr[-1000:]
    output.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    return report


def aggregate(reports: list[dict[str, Any]], level: int, args: argparse.Namespace) -> dict[str, Any]:
    measured = [item for item in reports if item.get("capacityRun", {}).get("warmup") is False]
    rps = sorted(float(item.get("throughputRps", 0.0)) for item in measured)
    p99 = sorted(float((item.get("latencyMs") or {}).get("p99", 0.0)) for item in measured)
    passed = [item.get("passed") is True for item in measured]
    return {
        "level": level,
        "repetitions": len(measured),
        "passedRepetitions": sum(passed),
        "passed": len(measured) == args.runs and all(passed),
        "medianRps": round(rps[len(rps) // 2], 3) if rps else "n/a",
        "medianP99Ms": round(p99[len(p99) // 2], 3) if p99 else "n/a",
        "firstViolation": next((item.get("capacityRun", {}).get("run") for item in measured if not item.get("passed")), None),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument("--test-environment", action="store_true")
    parser.add_argument("--base", default=os.getenv("BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--phase", choices=["burst", "mixed", "sustained"], default="burst")
    parser.add_argument("--levels", type=positive_ints, default=[50, 100, 200])
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--warmup-runs", type=int, default=1)
    parser.add_argument("--takers", type=int, default=200)
    parser.add_argument("--workers", type=int, default=200)
    parser.add_argument("--orders", type=int, default=20)
    parser.add_argument("--short-duration-seconds", type=int, default=30)
    parser.add_argument("--stability-seconds", type=int, default=1800)
    parser.add_argument("--stability-clients-per-order", type=int, default=20)
    parser.add_argument("--p99-limit-ms", type=float, default=1000.0)
    parser.add_argument("--service-host-id", default=os.getenv("SERVICE_HOST_ID", ""))
    parser.add_argument("--press-host-id", default=os.getenv("PRESS_HOST_ID", ""))
    parser.add_argument("--resources-recorded", action="store_true")
    args = parser.parse_args()
    if args.runs < 5:
        parser.error("--runs must be at least 5")
    if args.warmup_runs < 0 or args.takers < 1 or args.workers < 1:
        parser.error("warmup-runs must be non-negative and takers/workers must be positive")
    if args.stability_seconds < 1800:
        parser.error("--stability-seconds must be at least 1800 seconds")
    if not args.dry_run and not args.test_environment:
        parser.error("measured mode requires --test-environment")
    if not args.dry_run and (not args.service_host_id or not args.press_host_id or args.service_host_id == args.press_host_id):
        parser.error("measured mode requires distinct service and load-generator host identifiers")

    CAPACITY_DIR.mkdir(parents=True, exist_ok=True)
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    CHART_DIR.mkdir(parents=True, exist_ok=True)
    if args.dry_run:
        reason = "Independent load-generator and service nodes were not supplied; no load was executed."
        write_environment(args, "NOT_RUN", reason)
        plan = {
            "status": "NOT_RUN",
            "generatedAt": utc_now(),
            "reason": reason,
            "levels": args.levels,
            "runsPerLevel": args.runs,
            "warmupRunsPerLevel": args.warmup_runs,
            "stabilitySeconds": args.stability_seconds,
            "sloTargets": {"p99Ms": args.p99_limit_ms, "http5xxRate": 0, "singleWinner": True, "reconciliation": True},
            "sensitiveValuesExcluded": True,
        }
        plan_path = RAW_DIR / f"capacity-dry-run-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
        plan_path.write_text(json.dumps(plan, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
        svg(CHART_DIR / "capacity-rps.svg", "Capacity median RPS", [], [], "")
        svg(CHART_DIR / "capacity-p99.svg", "Capacity median P99", [], [], " ms")
        write_report(args, "NOT_RUN", reason, [], None)
        print(f"capacity_report={CAPACITY_DIR / 'capacity-baseline.md'}")
        print("status=NOT_RUN")
        return 0

    reason = "Distinct service and load-generator identifiers supplied; measurements remain scoped to the recorded isolated environment."
    write_environment(args, "IN_PROGRESS", reason)
    all_reports: list[dict[str, Any]] = []
    rows: list[dict[str, Any]] = []
    for level in args.levels:
        for run_number in range(1, args.warmup_runs + 1):
            all_reports.append(run_level(args, level, run_number, True))
        measured = [run_level(args, level, run_number, False) for run_number in range(1, args.runs + 1)]
        all_reports.extend(measured)
        rows.append(aggregate(measured, level, args))
    stable = [row for row in rows if row["passed"]]
    stability = {"status": "NOT_RUN"}
    if stable:
        candidate = max(stable, key=lambda item: float(item["medianRps"]))
        stability_output = RAW_DIR / f"capacity-stability-level{candidate['level']}-{datetime.now().strftime('%Y%m%d-%H%M%S')}.json"
        stability_command = [
            sys.executable, str(ROOT / "scripts" / "load_test.py"), "--base", args.base,
            "--phase", "sustained", "--duration-seconds", str(args.stability_seconds),
            "--clients-per-order", str(args.stability_clients_per_order),
            "--takers", str(args.takers), "--workers", str(args.workers), "--output", str(stability_output),
        ]
        process = subprocess.run(stability_command, cwd=ROOT, capture_output=True, text=True, check=False)
        stability_report = read_json(stability_output) if stability_output.exists() else {}
        stability = {
            "status": "PASS" if process.returncode == 0 and measured_passed(stability_report, args.p99_limit_ms) else "FAIL",
            "durationSeconds": args.stability_seconds,
            "source": str(stability_output.relative_to(ROOT)).replace("\\", "/"),
        }
    status = "COMPLETE" if rows and all(row["passed"] for row in rows) and stability["status"] == "PASS" else "PARTIAL"
    write_environment(args, status, reason)
    labels = [str(row["level"]) for row in rows]
    svg(CHART_DIR / "capacity-rps.svg", "Capacity median RPS", labels, [float(row["medianRps"]) for row in rows if row["medianRps"] != "n/a"], "")
    svg(CHART_DIR / "capacity-p99.svg", "Capacity median P99", labels, [float(row["medianP99Ms"]) for row in rows if row["medianP99Ms"] != "n/a"], " ms")
    write_report(args, status, reason, rows, stability)
    print(f"capacity_report={CAPACITY_DIR / 'capacity-baseline.md'}")
    print(f"status={status}")
    return 0 if status == "COMPLETE" else 1


if __name__ == "__main__":
    raise SystemExit(main())
