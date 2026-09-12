"""Create a checksum-verified MySQL logical backup from a named container."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--container", default=os.getenv("MYSQL_CONTAINER", "campus-errand-grab-mysql-1"))
    parser.add_argument("--database", default=os.getenv("DB_NAME", "campus_errand"))
    parser.add_argument("--output-dir", default="reports/disaster-recovery/backups")
    parser.add_argument("--password-env", default="DB_PASSWORD")
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = ROOT / output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    dump_path = output_dir / f"{args.database}-{stamp}.sql"
    password = os.getenv(args.password_env, "change-me")
    dump_args = [
        "mysqldump", "-uroot", "--single-transaction", "--routines", "--events", "--triggers",
        "--hex-blob", "--set-gtid-purged=OFF", args.database,
    ]
    environment = os.environ.copy()
    environment["MYSQL_PWD"] = password
    process = subprocess.run(
        ["docker", "exec", "-e", f"MYSQL_PWD={password}", args.container, *dump_args],
        cwd=ROOT, capture_output=True, check=False, env=environment,
    )
    if process.returncode != 0:
        raise SystemExit(f"mysqldump failed with exit code {process.returncode}: {process.stderr[-1000:].decode(errors='replace')}")
    dump_path.write_bytes(process.stdout)
    digest = hashlib.sha256(process.stdout).hexdigest()
    metadata = {
        "status": "PASS",
        "createdAt": utc_now(),
        "container": args.container,
        "database": args.database,
        "dump": str(dump_path.relative_to(ROOT)).replace("\\", "/"),
        "bytes": len(process.stdout),
        "sha256": digest,
        "options": ["single-transaction", "routines", "events", "triggers", "hex-blob"],
        "sensitiveValuesExcluded": True,
    }
    metadata_path = dump_path.with_suffix(".json")
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(f"backup={dump_path}")
    print(f"metadata={metadata_path}")
    print(f"sha256={digest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
