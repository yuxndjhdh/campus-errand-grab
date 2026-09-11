"""Fail on credentials that should never enter source or configuration files."""

from __future__ import annotations

import argparse
import re
from pathlib import Path


TEXT_SUFFIXES = {".conf", ".env", ".java", ".json", ".md", ".properties", ".py", ".sh", ".sql", ".xml", ".yml", ".yaml"}
CONFIG_SUFFIXES = {".conf", ".env", ".properties", ".yml", ".yaml"}
IGNORED_PARTS = {".git", "target", "__pycache__", ".pytest_cache"}
PRIVATE_KEY = re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")
AWS_ACCESS_KEY = re.compile(r"\bAKIA[0-9A-Z]{16}\b")
ASSIGNMENT = re.compile(r"(?i)^\s*[A-Z0-9_.-]*(?:PASSWORD|SECRET|TOKEN)\s*[:=]\s*([\"']?)([^\s,;\"']{8,})")


def files(root: Path):
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix.lower() not in TEXT_SUFFIXES:
            continue
        if any(part in IGNORED_PARTS for part in path.parts):
            continue
        yield path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=".")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    findings: list[str] = []
    for path in files(root):
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        relative = path.relative_to(root)
        if PRIVATE_KEY.search(content):
            findings.append(f"{relative}: private key material")
        if AWS_ACCESS_KEY.search(content):
            findings.append(f"{relative}: AWS access key")
        if path.suffix.lower() in CONFIG_SUFFIXES:
            for line in content.splitlines():
                match = ASSIGNMENT.match(line)
                if not match:
                    continue
                value = match.group(2).lower()
                if value.startswith("${") or value.startswith("<") or any(
                        marker in value for marker in ("change-me", "replace-", "replace_with", "local-development", "test-secret")):
                    continue
                findings.append(f"{relative}: hard-coded credential assignment")
    if findings:
        print("repository safety scan failed:")
        print("\n".join(f"- {finding}" for finding in findings))
        return 1
    print("repository safety scan passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
