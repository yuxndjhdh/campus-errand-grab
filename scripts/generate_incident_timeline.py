"""Convert monitoring trigger/recovery timestamps into incident timing evidence."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def parse(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", default="reports/monitoring/monitoring-validation.json")
    parser.add_argument("--output", default="reports/observability/incident-timeline-monitoring.json")
    args = parser.parse_args()
    input_path = Path(args.input)
    if not input_path.is_absolute():
        input_path = ROOT / input_path
    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    source = json.loads(input_path.read_text(encoding="utf-8"))
    incidents = []
    for item in source.get("alerts", []):
        injected = parse(item["injectedAt"])
        fired = parse(item["fired"]["observedAt"])
        repaired = parse(item["repairedAt"])
        resolved = parse(item["resolved"]["observedAt"])
        incidents.append({
            "alert": item["alert"],
            "injectedAt": item["injectedAt"],
            "detectedAt": item["fired"]["observedAt"],
            "repairedAt": item["repairedAt"],
            "recoveredAt": item["resolved"]["observedAt"],
            "mttdSeconds": round((fired - injected).total_seconds(), 3),
            "mttrSeconds": round((resolved - injected).total_seconds(), 3),
            "repairToRecoverySeconds": round((resolved - repaired).total_seconds(), 3),
            "notificationStatus": "NOT_RUN_EXTERNAL_RECEIVER",
        })
    result = {
        "status": "PASS" if source.get("status") == "PASS" and incidents else "FAIL",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "source": str(input_path.relative_to(ROOT)).replace("\\", "/"),
        "normalWindowFalsePositives": "not measured; this is a fault-injection-only window",
        "incidents": incidents,
        "externalNotification": "not validated in current Compose environment",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(f"incident_timeline={output}")
    print(f"status={result['status']}")
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
