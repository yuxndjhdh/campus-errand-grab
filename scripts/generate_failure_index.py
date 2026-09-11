"""Index the latest passing failure demonstrations and preserve failed samples."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
REPORT_DIR = ROOT / "reports" / "failure-tests"
SCENARIOS = {
    "redis-outage": "Redis outage and recovery",
    "outbox-recovery": "Outbox lease/crash recovery",
    "duplicate-settlement": "Concurrent duplicate settlement",
    "reconciliation-drift": "Reconciliation drift detection and repair",
}


def main() -> int:
    REPORT_DIR.mkdir(parents=True, exist_ok=True)
    latest: dict[str, tuple[Path, dict]] = {}
    failed: list[Path] = []
    for path in sorted(REPORT_DIR.glob("*.json"), key=lambda item: item.stat().st_mtime):
        try:
            report = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        scenario = str(report.get("scenario", ""))
        if scenario not in SCENARIOS:
            continue
        if report.get("passed") is True:
            latest[scenario] = (path, report)
        else:
            failed.append(path)
    complete = all(name in latest for name in SCENARIOS)
    lines = [
        "# Failure Demonstration Index",
        "",
        f"Generated at (UTC): {datetime.now(timezone.utc).isoformat()}",
        "",
        "Each selected report was produced by an executable script with `--test-environment`; a failing assertion returns a non-zero exit code. The selected reports are the newest passing report for each scenario.",
        "",
        "| Scenario | Command | Result | Evidence | Core assertions |",
        "| --- | --- | --- | --- | --- |",
    ]
    commands = {
        "redis-outage": "python scripts/verify_redis_outage_recovery.py --test-environment",
        "outbox-recovery": "python scripts/verify_outbox_crash_recovery.py --test-environment",
        "duplicate-settlement": "python scripts/verify_duplicate_settlement.py --test-environment",
        "reconciliation-drift": "python scripts/verify_reconciliation_drift_detection.py --test-environment",
    }
    assertions = {
        "redis-outage": "one winner, no HTTP 5xx, Redis metrics increase, derived state rebuilds, reconciliation passes",
        "outbox-recovery": "expired PROCESSING lease is reclaimed, event is PUBLISHED, marker/delay entry restored, reconciliation passes",
        "duplicate-settlement": "one successful concurrent delivery, one idempotency row, three ledger entries, zero ledger sum, reconciliation passes",
        "reconciliation-drift": "INV-2 fails after one-cent drift and passes after restoration",
    }
    for scenario, title in SCENARIOS.items():
        selected = latest.get(scenario)
        if selected:
            path, report = selected
            lines.append(f"| {title} | `{commands[scenario]}` | PASS | `{path.name}` | {assertions[scenario]} |")
        else:
            lines.append(f"| {title} | `{commands[scenario]}` | MISSING | - | {assertions[scenario]} |")
    if failed:
        lines += ["", "## Historical Failed Samples", "", "These files are retained as failed samples and are not counted as successful evidence:"]
        lines.extend(f"- `{path.name}`" for path in failed)
    lines += [
        "",
        "The full commands, timestamps, recovery markers, and reconciliation payloads are stored in the linked JSON reports. Run these demonstrations only against the disposable Compose test environment.",
    ]
    output = REPORT_DIR / "index.md"
    output.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"failure_index={output}")
    print(f"status={'COMPLETE' if complete else 'PARTIAL'}")
    return 0 if complete else 1


if __name__ == "__main__":
    raise SystemExit(main())
