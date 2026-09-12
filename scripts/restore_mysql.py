"""Restore a logical dump into an explicitly named disposable database."""

from __future__ import annotations

import argparse
import os
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dump", required=True)
    parser.add_argument("--container", default=os.getenv("MYSQL_CONTAINER", "campus-errand-grab-mysql-1"))
    parser.add_argument("--database", default="campus_errand_restore")
    parser.add_argument("--replace", action="store_true", help="drop only the explicitly named restore database first")
    parser.add_argument("--test-environment", action="store_true", required=True)
    args = parser.parse_args()
    if args.database in {"campus_errand", "mysql", "sys", "information_schema", "performance_schema"}:
        parser.error("restore target must be a disposable non-production database")
    dump = Path(args.dump)
    if not dump.is_absolute():
        dump = ROOT / dump
    if not dump.exists():
        parser.error(f"dump does not exist: {dump}")
    password = os.getenv("DB_PASSWORD", "change-me")
    env = os.environ.copy()
    env["MYSQL_PWD"] = password
    mysql = ["docker", "exec", "-e", f"MYSQL_PWD={password}", args.container, "mysql", "-uroot", "-N", "-B"]
    if args.replace:
        subprocess.run(mysql + ["-e", f"DROP DATABASE IF EXISTS `{args.database}`; CREATE DATABASE `{args.database}`;"],
                       cwd=ROOT, env=env, check=True, capture_output=True)
    else:
        subprocess.run(mysql + ["-e", f"CREATE DATABASE IF NOT EXISTS `{args.database}`;"],
                       cwd=ROOT, env=env, check=True, capture_output=True)
    restore = subprocess.run(
        ["docker", "exec", "-i", "-e", f"MYSQL_PWD={password}", args.container,
         "mysql", "-uroot", args.database],
        cwd=ROOT, input=dump.read_bytes(), capture_output=True, check=False, env=env,
    )
    if restore.returncode != 0:
        raise SystemExit(f"mysql restore failed with exit code {restore.returncode}: {restore.stderr[-1000:].decode(errors='replace')}")
    print(f"restored_database={args.database}")
    print(f"dump={dump}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
