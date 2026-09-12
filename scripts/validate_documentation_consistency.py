"""Validate quantified project claims against local evidence and scope rules.

This check is intentionally conservative: planned targets are reported separately
from achieved results, while achieved numeric claims need a nearby repository or
test artifact reference. It never treats an external CI run as local evidence.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "reports" / "document-consistency.json"
DOCUMENTS = [
    ROOT / "README.md",
    ROOT / "HANDOFF.md",
    ROOT / "docs" / "project-resume.md",
    ROOT / "docs" / "quantified-impact-optimization-plan.md",
]
SUPPORTING_DOCUMENTS = [
    ROOT / "docs" / "project-validation-and-ci-follow-up.md",
    ROOT / "docs" / "outstanding-work-plan.md",
    ROOT / "docs" / "remaining-validation-and-benchmark-tasks.md",
]
EVIDENCE_PATH_RE = re.compile(
    r"`([^`]+)`|\]\(([^)#]+)(?:#[^)]+)?\)|\[([^]]+)\]\(([^)#]+)(?:#[^)]+)?\)"
)
QUANTIFIED_CLAIM_RE = re.compile(
    r"(?i)(?:\bRPS\b|\bP(?:50|95|99)\b|HTTP\s*5xx|\bCAS\b|中位(?:数)?|吞吐|延迟|并发|过滤率|覆盖率|"
    r"成功率|恢复耗时|\bRTO\b|\bRPO\b|持续负载|唯一赢家|资金对账)"
)
PLANNED_CONTEXT_RE = re.compile(
    r"(?i)(?:目标|计划|待扫描|下一轮|验收目标|预计|建议|完成后|模板|尚未完成|首轮要求|不应直接|暂缓|设计目标|至少|每轮必须|需要生成)"
)
EVIDENCE_CONTEXT_RE = re.compile(
    r"(?i)(?:证据|source:|reports/|target/|scripts/|\.github/|github actions run|原始 json|测试报告|ci 记录)"
)
HISTORICAL_CI_CONTEXT_RE = re.compile(
    r"(?i)(?:历史|外部|不代表当前|未再次执行|未重新推送|需.*核验|不等于当前)"
)
CI_RUN_RE = re.compile(r"\b(?:Run|run)\s+[`#]?([0-9]{6,})")
STALE_CI_RE = re.compile(r"(?i)(?:当前改动|当前代码|本轮改动).{0,24}(?:已通过|通过).{0,24}(?:远程|github actions|CI)")
PRODUCTION_CLAIM_RES = [
    re.compile(r"(?i)生产(?:容量|吞吐|支持).{0,24}\d"),
    re.compile(r"(?i)系统\s*100\s*%?\s*可用"),
    re.compile(r"(?i)Redis.{0,16}(?:翻倍|所有场景.{0,8}(?:提升|改善))"),
    re.compile(r"(?i)避免线上资金损失"),
    re.compile(r"(?i)零故障"),
]
AUTHORITATIVE_VALUES = {
    "380.03": "hot-500 Redis on median RPS",
    "351.95": "hot-500 Redis off median RPS",
    "333.84": "hot-1000 Redis on median RPS",
    "361.69": "hot-1000 Redis off median RPS",
    "572.92": "mixed-1000x10 Redis on median RPS",
    "587.49": "mixed-1000x10 Redis off median RPS",
    "89.97%": "mixed-1000x10 database CAS reduction",
    "99.90%": "hot-1000 database CAS reduction",
    "317.35": "sustained-load RPS",
    "95,220": "sustained-load grab attempts",
    "4,761": "sustained-load order/settlement count",
}


def relative_evidence_path(value: str) -> Path | None:
    candidate = value.strip().replace("\\", "/")
    if candidate.startswith(("http://", "https://", "codex://")):
        return None
    if candidate.startswith("raw/") or candidate.startswith("charts/") or candidate in {"summary.json", "summary.csv"}:
        return ROOT / "reports" / "benchmarks" / candidate
    if candidate.startswith("reports/") or candidate.startswith("target/") or candidate.startswith("scripts/"):
        return ROOT / candidate
    if candidate.startswith(".github/") or candidate.startswith("docs/") or candidate.startswith("src/"):
        return ROOT / candidate
    if candidate in {"README.md", "HANDOFF.md", "pom.xml", "compose.yml", "compose.production.yml"}:
        return ROOT / candidate
    return None


def evidence_paths(text: str) -> list[str]:
    paths = []
    for match in EVIDENCE_PATH_RE.finditer(text):
        for value in match.groups():
            if value and relative_evidence_path(value):
                paths.append(value.strip())
                break
    return paths


def is_planned(window: str) -> bool:
    return bool(PLANNED_CONTEXT_RE.search(window))


def has_evidence(window: str) -> bool:
    return bool(EVIDENCE_CONTEXT_RE.search(window))


def is_negative_scope_statement(window: str) -> bool:
    return bool(re.search(r"(?i)(?:不写|不应|不能|不代表|不是|不等于|禁止|暂不)", window))


def inspect_document(path: Path, authoritative_text: str) -> dict:
    if not path.exists():
        return {"path": str(path.relative_to(ROOT)), "missing": True, "claims": 0, "covered": 0, "findings": ["document missing"]}
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    claims = []
    findings = []
    covered = 0
    in_code_fence = False
    for index, line in enumerate(lines):
        if line.strip().startswith("```"):
            in_code_fence = not in_code_fence
            continue
        has_metric_number = bool(re.search(
            r"(?:\d[\d,.]*\s*(?:%|RPS|ms|毫秒|秒|次|个|条|人|客户端|订单|测试)|"
            r"(?:RPS|P(?:50|95|99)|HTTP\s*5xx|CAS|并发|中位|吞吐|延迟|过滤率|覆盖率|成功率|恢复耗时|RTO|RPO|持续负载|唯一赢家|资金对账).{0,20}\d)",
            line,
            re.IGNORECASE,
        ))
        if in_code_fence or not has_metric_number or not QUANTIFIED_CLAIM_RE.search(line):
            continue
        if "天" in line and "～" in line:
            continue
        current_heading = ""
        for previous in reversed(lines[:index]):
            if previous.startswith("#"):
                current_heading = previous
                break
        if any(term in current_heading for term in ("做什么工作", "如何量化", "验收证据", "验收标准")):
            continue
        start = max(0, index - 2)
        end = min(len(lines), index + 5)
        window = "\n".join(lines[start:end])
        if is_planned(window) or is_negative_scope_statement(window):
            continue
        claim = {"line": index + 1, "text": line.strip(), "evidenceNearby": has_evidence(window)}
        claims.append(claim)
        if claim["evidenceNearby"]:
            covered += 1
        else:
            findings.append(f"line {index + 1}: quantified claim has no nearby evidence reference")

    for index, line in enumerate(lines):
        if STALE_CI_RE.search(line) and not re.search(r"(?i)(?:删除|清理|过期|禁止)", line):
            findings.append(f"line {index + 1}: CI wording may present an unverified remote result as current")
        for run in CI_RUN_RE.finditer(line):
            window = "\n".join(lines[max(0, index - 3):min(len(lines), index + 4)])
            if not HISTORICAL_CI_CONTEXT_RE.search(window) and not has_evidence(window):
                findings.append(f"line {index + 1}: external CI Run {run.group(1)} lacks historical/current-worktree boundary")
        if any(pattern.search(line) for pattern in PRODUCTION_CLAIM_RES) and not is_negative_scope_statement(
                "\n".join(lines[max(0, index - 2):min(len(lines), index + 3)])):
            findings.append(f"line {index + 1}: local result is phrased as a production claim")

    for index, line in enumerate(lines):
        for value, description in AUTHORITATIVE_VALUES.items():
            if value in line and value.replace(",", "") not in authoritative_text.replace(",", ""):
                findings.append(f"line {index + 1}: {description} value {value} is absent from authoritative benchmark evidence")

    return {
        "path": str(path.relative_to(ROOT)),
        "missing": False,
        "claims": len(claims),
        "covered": covered,
        "coverage": covered / len(claims) if claims else 1.0,
        "claimsDetail": claims,
        "findings": findings,
        "evidencePaths": sorted(set(evidence_paths(text))),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default=str(OUTPUT))
    args = parser.parse_args()

    report_path = ROOT / "reports" / "benchmarks" / "benchmark-report.md"
    authoritative_text = report_path.read_text(encoding="utf-8") if report_path.exists() else ""
    document_reports = [inspect_document(path, authoritative_text) for path in DOCUMENTS]
    supporting_reports = [inspect_document(path, authoritative_text) for path in SUPPORTING_DOCUMENTS]
    missing_evidence = []
    for document in document_reports:
        for value in document.get("evidencePaths", []):
            resolved = relative_evidence_path(value)
            if resolved and not (any(resolved.parent.glob(resolved.name)) if any(char in resolved.name for char in "*?[") else resolved.exists()):
                missing_evidence.append({"document": document["path"], "reference": value})
    findings = [
        {"document": document["path"], "finding": finding}
        for document in document_reports
        for finding in document.get("findings", [])
    ]
    findings.extend({"document": item["document"], "finding": f"evidence path does not exist: {item['reference']}"}
                    for item in missing_evidence)
    claim_count = sum(document.get("claims", 0) for document in document_reports)
    covered_count = sum(document.get("covered", 0) for document in document_reports)
    result = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "passed": not findings and covered_count == claim_count,
        "staleStatusCount": sum(1 for item in findings if "CI wording" in item["finding"] or "external CI Run" in item["finding"]),
        "productionClaimCount": sum(1 for item in findings if "production claim" in item["finding"]),
        "quantifiedClaims": claim_count,
        "evidenceCovered": covered_count,
        "evidenceCoverage": covered_count / claim_count if claim_count else 1.0,
        "findings": findings,
        "documents": document_reports,
        "supportingDocuments": supporting_reports,
    }
    output = Path(args.output)
    if not output.is_absolute():
        output = ROOT / output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("passed", "staleStatusCount", "productionClaimCount", "quantifiedClaims", "evidenceCovered", "evidenceCoverage")}, ensure_ascii=True))
    if findings or covered_count != claim_count:
        for item in findings:
            print(f"FAIL {item['document']}: {item['finding']}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
