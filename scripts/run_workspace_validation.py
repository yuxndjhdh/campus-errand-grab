"""Run the local acceptance gates and retain a machine-readable validation record.

The command deliberately records the current worktree state and every subprocess
exit code. It is a local evidence collector; it does not inspect or claim remote CI.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
RUNTIME_DIR = ROOT / "reports" / "runtime"


def maven_executable() -> str:
    candidates = ["mvn.cmd", "mvn"] if os.name == "nt" else ["mvn"]
    for candidate in candidates:
        resolved = shutil.which(candidate)
        if resolved:
            return resolved
    return candidates[0]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def git_state() -> dict[str, Any]:
    result = subprocess.run(
        ["git", "status", "--short", "--branch"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return {"returnCode": result.returncode, "output": result.stdout.strip()}


def file_sha256(path: Path) -> str | None:
    if not path.exists():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run_command(name: str, command: list[str], log_path: Path) -> dict[str, Any]:
    started = utc_now()
    monotonic = time.monotonic()
    process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, check=False)
    output = "\n".join(part for part in (process.stdout, process.stderr) if part)
    log_path.write_text(output, encoding="utf-8")
    return {
        "name": name,
        "command": command,
        "startedAt": started,
        "finishedAt": utc_now(),
        "durationSeconds": round(time.monotonic() - monotonic, 3),
        "returnCode": process.returncode,
        "log": str(log_path.relative_to(ROOT)).replace("\\", "/"),
        "outputSha256": hashlib.sha256(output.encode("utf-8")).hexdigest(),
        "outputTail": output[-2000:],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--index", action="append", required=True,
                        help="diagnostic run-index JSON; repeat for the selected A/B indexes")
    parser.add_argument("--output", default="reports/runtime/current-workspace-validation.json")
    parser.add_argument("--skip-maven", action="store_true",
                        help="skip the long Maven gate when a current log already exists")
    args = parser.parse_args()

    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)
    RUNTIME_DIR.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    checks: list[dict[str, Any]] = []

    if not args.skip_maven:
        checks.append(run_command(
            "maven-clean-verify",
            [maven_executable(), "-s", "mvn-settings.xml", "clean", "verify"],
            RUNTIME_DIR / f"maven-verify-{stamp}.log",
        ))
    checks.extend([
        run_command("python-compileall", [sys.executable, "-m", "compileall", "-q", "scripts"],
                    RUNTIME_DIR / f"python-compileall-{stamp}.log"),
        run_command("documentation-consistency", [sys.executable, "scripts/validate_documentation_consistency.py"],
                    RUNTIME_DIR / f"documentation-consistency-{stamp}.log"),
        run_command("repository-safety", [sys.executable, "scripts/validate_repository_safety.py"],
                    RUNTIME_DIR / f"repository-safety-{stamp}.log"),
        run_command("compose-config", ["docker", "compose", "config"],
                    RUNTIME_DIR / f"compose-config-{stamp}.log"),
        run_command("docker-build", ["docker", "build", "--tag", "campus-errand-grab:local-check", "."],
                    RUNTIME_DIR / f"docker-build-{stamp}.log"),
    ])
    report_command = [sys.executable, "scripts/generate_runtime_benchmark_report.py"]
    for index in args.index:
        report_command.extend(["--index", index])
    report_command.extend(["--output", "reports/benchmarks/runtime-bottleneck-report.md"])
    checks.append(run_command(
        "runtime-benchmark-report",
        report_command,
        RUNTIME_DIR / f"runtime-benchmark-report-{stamp}.log",
    ))

    failed = [item["name"] for item in checks if item["returnCode"] != 0]
    result = {
        "status": "PASS" if not failed else "FAIL",
        "startedAt": checks[0]["startedAt"] if checks else utc_now(),
        "finishedAt": utc_now(),
        "worktree": git_state(),
        "checks": checks,
        "failedChecks": failed,
        "evidenceFiles": {
            "documentConsistency": {
                "path": "reports/document-consistency.json",
                "sha256": file_sha256(ROOT / "reports/document-consistency.json"),
            },
            "runtimeReport": {
                "path": "reports/benchmarks/runtime-bottleneck-report.md",
                "sha256": file_sha256(ROOT / "reports/benchmarks/runtime-bottleneck-report.md"),
            },
        },
        "remoteCiInspected": False,
    }
    output.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(f"workspace_validation={output}")
    print(f"status={result['status']}")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
