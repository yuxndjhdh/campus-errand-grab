"""Run repeated HTTP benchmark scenarios and store traceable raw JSON results.

The command mutates the application and may recreate the Compose app container, so
it requires an explicit disposable-environment flag. It records failures as JSON and
returns non-zero if any warmup or measured run fails.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import requests


ROOT = Path(__file__).resolve().parents[1]
RAW_DIR = ROOT / "reports" / "benchmarks" / "raw"
SCENARIOS: dict[str, dict[str, int | str]] = {
    "hot-500": {"phase": "burst", "clients": 500},
    "hot-1000": {"phase": "burst", "clients": 1000},
    "mixed-1000x10": {"phase": "mixed", "orders": 1000, "clients_per_order": 10},
    "mixed-1000x20": {"phase": "mixed", "orders": 1000, "clients_per_order": 20},
    "mixed-1000x50": {"phase": "mixed", "orders": 1000, "clients_per_order": 50},
}


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def parse_csv(value: str) -> list[str]:
    values = [item.strip() for item in value.split(",") if item.strip()]
    if not values:
        raise argparse.ArgumentTypeError("value must contain at least one item")
    return values


def wait_ready(base: str, timeout: float = 120) -> None:
    deadline = time.monotonic() + timeout
    last_error = ""
    while time.monotonic() < deadline:
        try:
            response = requests.get(base + "/actuator/health/readiness", timeout=5)
            if response.status_code == 200 and response.json().get("status") == "UP":
                return
            last_error = f"HTTP {response.status_code}: {response.text[:300]}"
        except requests.RequestException as exc:
            last_error = str(exc)
        time.sleep(2)
    raise TimeoutError(f"application did not become ready: {last_error}")


def compose(compose_file: str, env_updates: dict[str, str], *arguments: str) -> str:
    command = ["docker", "compose", "-f", compose_file, *arguments]
    environment = os.environ.copy()
    environment.update(env_updates)
    try:
        result = subprocess.run(
            command,
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            check=True,
        )
    except subprocess.CalledProcessError as exc:
        details = (exc.stderr or exc.stdout or "").strip()
        raise RuntimeError(f"{' '.join(command)} failed: {details}") from exc
    return result.stdout.strip()


def configure_runtime(args: argparse.Namespace, prefilter: str, pool_size: str) -> None:
    if prefilter not in {"on", "off"}:
        wait_ready(args.base)
        return
    compose(
        args.compose_file,
        {
            "REDIS_PREFILTER_ENABLED": "true" if prefilter == "on" else "false",
            "DB_POOL_SIZE": pool_size,
        },
        "up",
        "-d",
        "--build",
        "app",
    )
    wait_ready(args.base)


def scenario_command(args: argparse.Namespace, definition: dict[str, int | str], output: Path) -> list[str]:
    command = [
        sys.executable,
        str(ROOT / "scripts" / "load_test.py"),
        "--base",
        args.base,
        "--phase",
        str(definition["phase"]),
        "--output",
        str(output),
        "--takers",
        str(args.takers),
        "--workers",
        str(args.workers),
    ]
    if definition["phase"] == "burst":
        command.extend(["--clients", str(definition["clients"])])
    else:
        command.extend([
            "--orders",
            str(definition["orders"]),
            "--clients-per-order",
            str(definition["clients_per_order"]),
        ])
    return command


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeError(f"benchmark did not produce valid JSON at {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise RuntimeError(f"benchmark report at {path} is not a JSON object")
    return value


def run_one(
    args: argparse.Namespace,
    scenario_name: str,
    definition: dict[str, int | str],
    prefilter: str,
    pool_size: str,
    run_number: int,
    warmup: bool,
) -> tuple[Path, bool]:
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    prefix = "warmup" if warmup else "run"
    output = RAW_DIR / f"{scenario_name}-{prefilter}-pool{pool_size}-{prefix}{run_number}-{stamp}.json"
    command = scenario_command(args, definition, output)
    started = utc_now()
    process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=False)
    finished = utc_now()

    if output.exists():
        try:
            report = read_json(output)
        except RuntimeError as exc:
            report = {"scenario": scenario_name, "error": str(exc)}
    else:
        report = {}
    passed = (
        bool(report)
        and process.returncode == 0
        and report.get("reconciliation", {}).get("passed", True)
        and report.get("winnerInvariantPassed", True)
    )
    report["benchmark"] = {
        "scenario": scenario_name,
        "prefilter": prefilter,
        "poolSize": int(pool_size),
        "run": run_number,
        "warmup": warmup,
        "startedAt": started,
        "finishedAt": finished,
        "command": command,
    }
    report["processReturnCode"] = process.returncode
    report["passed"] = bool(passed)
    if process.stderr.strip():
        report["stderrTail"] = process.stderr[-2000:]
    if not passed and process.returncode == 0:
        report["error"] = "load completed but a benchmark acceptance invariant did not pass"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(f"{'PASS' if passed else 'FAIL'} {output}")
    return output, bool(passed)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default=os.getenv("BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--compose-file", default=os.getenv("COMPOSE_FILE", "compose.yml"))
    parser.add_argument("--test-environment", action="store_true")
    parser.add_argument("--scenario", choices=["all", *SCENARIOS], default="all")
    parser.add_argument("--runs", type=int, default=5, help="measured runs per variant; must be at least 5")
    parser.add_argument("--warmup-runs", type=int, default=1)
    parser.add_argument("--prefilter-modes", type=parse_csv, default=["on", "off"])
    parser.add_argument("--pool-sizes", type=parse_csv, default=["20"])
    parser.add_argument("--takers", type=int, default=1,
                        help="identities rotated by load_test; set high enough to avoid rate limiting")
    parser.add_argument("--workers", type=int, default=1000,
                        help="maximum Python worker threads for multi-order runs")
    args = parser.parse_args()

    if not args.test_environment:
        parser.error("refusing to mutate the application without --test-environment")
    if args.runs < 5:
        parser.error("--runs must be at least 5 for an acceptance benchmark")
    if args.warmup_runs < 0 or args.takers < 1 or args.workers < 1:
        parser.error("--warmup-runs must be non-negative; --takers and --workers must be positive")
    if any(mode not in {"on", "off", "current"} for mode in args.prefilter_modes):
        parser.error("--prefilter-modes accepts only on, off, or current")
    if any(not item.isdigit() or int(item) < 1 for item in args.pool_sizes):
        parser.error("--pool-sizes must contain positive integers")

    selected = list(SCENARIOS) if args.scenario == "all" else [args.scenario]
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    index: dict[str, Any] = {"startedAt": utc_now(), "runs": [], "passed": True}
    for mode in args.prefilter_modes:
        for pool_size in args.pool_sizes:
            configure_runtime(args, mode, pool_size)
            for scenario_name in selected:
                definition = SCENARIOS[scenario_name]
                for run_number in range(1, args.warmup_runs + 1):
                    path, passed = run_one(args, scenario_name, definition, mode, pool_size, run_number, True)
                    index["runs"].append({"path": str(path), "passed": passed, "warmup": True})
                    index["passed"] = index["passed"] and passed
                for run_number in range(1, args.runs + 1):
                    path, passed = run_one(args, scenario_name, definition, mode, pool_size, run_number, False)
                    index["runs"].append({"path": str(path), "passed": passed, "warmup": False})
                    index["passed"] = index["passed"] and passed
    index["finishedAt"] = utc_now()
    index_path = RAW_DIR / f"run-index-{datetime.now().strftime('%Y%m%d-%H%M%S-%f')}.json"
    index_path.write_text(json.dumps(index, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(f"benchmark_index={index_path}")
    return 0 if index["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
