"""Capture benchmark environment facts without inventing performance results."""

from __future__ import annotations

import argparse
import os
import platform
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "reports" / "benchmarks" / "environment.md"


def command_output(*command: str) -> str:
    executable = shutil.which(command[0])
    if executable is None:
        return "not installed"
    try:
        result = subprocess.run(
            [executable, *command[1:]],
            cwd=ROOT,
            capture_output=True,
            text=True,
            timeout=30,
            check=False,
        )
    except (OSError, subprocess.TimeoutExpired) as exc:
        return f"unavailable: {exc}"
    output = "\n".join(part for part in (result.stdout, result.stderr) if part).strip()
    return output or f"command exited with status {result.returncode}"


def configured(name: str, default: str) -> str:
    return os.getenv(name, default)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default=str(DEFAULT_OUTPUT))
    parser.add_argument("--base", default=os.getenv("BASE_URL", "http://127.0.0.1:8080"))
    parser.add_argument("--formal-status", default="NOT RUN")
    parser.add_argument("--runs", type=int, default=5)
    parser.add_argument("--warmup-runs", type=int, default=1)
    parser.add_argument("--prefilter-modes", default="on,off")
    parser.add_argument("--pool-sizes", default=os.getenv("DB_POOL_SIZE", "20"))
    parser.add_argument("--takers", type=int, default=200)
    parser.add_argument("--workers", type=int, default=200)
    parser.add_argument("--claim-ttl-seconds", type=int, default=3600)
    parser.add_argument("--rate-limit-enabled", default=os.getenv("RATE_LIMIT_ENABLED", "false"))
    args = parser.parse_args()

    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)

    lines = [
        "# Benchmark Environment",
        "",
        "This file records environment facts only. It does not contain benchmark measurements.",
        f"Formal acceptance matrix status: {args.formal_status}.",
        "",
        f"Captured at (UTC): {datetime.now(timezone.utc).isoformat()}",
        f"Benchmark base URL: {args.base}",
        "",
        "## Host",
        "",
        f"- OS: {platform.platform()}",
        f"- Architecture: {platform.machine()}",
        f"- Logical CPUs: {os.cpu_count() or 'unknown'}",
        f"- Python: {sys.version.splitlines()[0]}",
        "",
        "## Tool Versions",
        "",
        "```text",
        f"java -version:\n{command_output('java', '-version')}",
        f"mvn -version:\n{command_output('mvn', '-version')}",
        f"docker version:\n{command_output('docker', 'version')}",
        f"docker compose version:\n{command_output('docker', 'compose', 'version')}",
        f"k6 version:\n{command_output('k6', 'version')}",
        "```",
        "",
        "## Application Configuration",
        "",
        "Only non-secret settings are recorded below. Passwords and tokens are intentionally omitted.",
        "",
        f"- Database name: {configured('DB_NAME', 'campus_errand')} (host-side setting)",
        f"- Hikari maximum pool size: {configured('DB_POOL_SIZE', '20')}",
        f"- Formal measured runs per matrix variant: {args.runs}",
        f"- Formal warmup runs per matrix variant: {args.warmup_runs}",
        f"- Redis prefilter comparison modes: {args.prefilter_modes}",
        f"- Benchmark identity count: {args.takers}",
        f"- Benchmark worker count: {args.workers}",
        f"- Benchmark claim TTL (seconds): {args.claim_ttl_seconds}",
        f"- Redis timeout: {configured('REDIS_TIMEOUT', '200ms')}",
        f"- Redis prefilter enabled: {configured('REDIS_PREFILTER_ENABLED', 'true')}",
        f"- Grab rate limiting enabled: {args.rate_limit_enabled}",
        f"- Grab rate limit per user: {configured('RATE_LIMIT_PER_USER', '30')}",
        f"- Grab rate limit per order: {configured('RATE_LIMIT_PER_ORDER', '200')}",
        f"- Grab rate limit window (seconds): {configured('RATE_LIMIT_WINDOW_SECONDS', '10')}",
        "",
        "## Collection Notes",
        "",
        "- This snapshot was captured for the parameters above; pair it with the raw JSON and generated formal report.",
        "- A missing Docker or k6 installation is recorded as unavailable; it is not treated as a benchmark result.",
    ]
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"environment_report={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
