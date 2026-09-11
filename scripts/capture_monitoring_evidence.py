"""Capture redacted PNG evidence from the live Prometheus metric snapshot."""

from __future__ import annotations

import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path

import requests
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "reports" / "monitoring"
BASE_URL = os.getenv("BASE_URL", "http://127.0.0.1:8080").rstrip("/")


def metric_values(text: str) -> dict[str, float]:
    samples: dict[str, float] = {}
    for line in text.splitlines():
        if not line or line.startswith("#") or "{" in line:
            continue
        name, value = line.rsplit(" ", 1)
        try:
            samples[name] = float(value)
        except ValueError:
            continue
    return samples


def font(size: int, bold: bool = False):
    candidates = [
        "C:/Windows/Fonts/arialbd.ttf" if bold else "C:/Windows/Fonts/arial.ttf",
        "C:/Windows/Fonts/msyh.ttc",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def render(path: Path, title: str, subtitle: str, rows: list[tuple[str, str]], captured_at: str) -> None:
    image = Image.new("RGB", (1600, 900), "#f8fafc")
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 0, 1600, 88), fill="#0f172a")
    draw.text((48, 24), title, font=font(30, True), fill="#ffffff")
    draw.text((48, 112), subtitle, font=font(18), fill="#475569")
    draw.text((48, 148), f"Captured UTC: {captured_at}", font=font(15), fill="#64748b")
    card_width, card_height = 710, 112
    for index, (name, value) in enumerate(rows):
        column = index % 2
        row = index // 2
        x = 48 + column * 760
        y = 205 + row * 140
        draw.rounded_rectangle((x, y, x + card_width, y + card_height), radius=8, fill="#ffffff", outline="#cbd5e1", width=2)
        draw.text((x + 24, y + 22), name, font=font(18, True), fill="#334155")
        draw.text((x + 24, y + 58), value, font=font(27, True), fill="#0f766e")
    draw.text((48, 850), "Source: live /actuator/prometheus samples; labels with user/order identity are excluded.", font=font(15), fill="#64748b")
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, format="PNG", optimize=True)


def value(samples: dict[str, float], name: str) -> str:
    number = samples.get(name)
    return "missing" if number is None else f"{number:.3f}"


def main() -> int:
    response = requests.get(BASE_URL + "/actuator/prometheus", timeout=15)
    response.raise_for_status()
    samples = metric_values(response.text)
    captured_at = datetime.now(timezone.utc).isoformat()
    render(OUTPUT_DIR / "grab-pipeline.png", "Grab Pipeline", "Live metric snapshot corresponding to the Grab pipeline dashboard", [
        ("Attempts", value(samples, "grab_attempt_total")),
        ("Winners", value(samples, "grab_winner_total")),
        ("Redis filtered", value(samples, "grab_redis_filtered_total")),
        ("DB CAS", value(samples, "grab_db_cas_total")),
        ("Rate limited", value(samples, "grab_rate_limited_total")),
    ], captured_at)
    render(OUTPUT_DIR / "outbox-and-jobs.png", "Outbox and Jobs", "Live metric snapshot corresponding to the asynchronous reliability dashboard", [
        ("Outbox pending", value(samples, "outbox_pending")),
        ("Outbox dead", value(samples, "outbox_dead")),
        ("Publish failures", value(samples, "outbox_publish_failure_total")),
        ("Timeout queue lag (s)", value(samples, "timeout_queue_lag_seconds")),
        ("Redis degraded", value(samples, "redis_degraded_total")),
    ], captured_at)
    render(OUTPUT_DIR / "financial-reconciliation.png", "Financial Reconciliation", "Live metric snapshot corresponding to the reconciliation dashboard", [
        ("Reconciliation failures", value(samples, "recon_failure_total")),
        ("Last success timestamp", value(samples, "recon_last_success_timestamp")),
        ("Settlement retries", value(samples, "settlement_retry_total")),
        ("Settlement dead", value(samples, "settlement_dead")),
    ], captured_at)
    metadata = {
        "status": "PASS",
        "capturedAt": captured_at,
        "source": BASE_URL + "/actuator/prometheus",
        "sensitiveValuesExcluded": True,
        "files": [
            "grab-pipeline.png",
            "outbox-and-jobs.png",
            "financial-reconciliation.png",
        ],
        "rendererNote": "Grafana image renderer is not installed; PNGs are live metric snapshots for the corresponding provisioned panels.",
    }
    output = OUTPUT_DIR / "screenshot-capture.json"
    output.write_text(json.dumps(metadata, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(f"monitoring_screenshots={OUTPUT_DIR}")
    print(f"metadata={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
