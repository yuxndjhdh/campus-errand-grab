"""Generate redacted local security-scan, SBOM, and scanner-availability evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]


def maven_executable() -> str:
    """Resolve Maven on Windows where the launcher is commonly mvn.cmd."""
    return shutil.which("mvn.cmd") or shutil.which("mvn") or "mvn"


def now() -> str:
    return datetime.now(timezone.utc).isoformat()


def command(command: list[str], timeout: int = 120) -> dict[str, Any]:
    try:
        process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=timeout, check=False)
        return {"available": True, "returnCode": process.returncode, "outputTail": (process.stdout + process.stderr)[-2000:]}
    except FileNotFoundError:
        return {"available": False, "returnCode": None, "outputTail": "tool not installed"}
    except subprocess.TimeoutExpired:
        return {"available": True, "returnCode": None, "outputTail": "command timed out"}


def write_json(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


def scanner_status(result: dict[str, Any]) -> str:
    if not result.get("available"):
        return "NOT_RUN"
    return "PASS" if result.get("returnCode") == 0 else "FAIL"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default="campus-errand-grab:local-check")
    parser.add_argument("--output-dir", default="reports/security")
    args = parser.parse_args()
    output_dir = Path(args.output_dir)
    if not output_dir.is_absolute():
        output_dir = ROOT / output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    safety = command(["python", "scripts/validate_repository_safety.py"])
    dependency_tree = command([maven_executable(), "-s", "mvn-settings.xml", "-B", "-DskipTests", "dependency:tree", "-DoutputType=text"], timeout=300)
    trivy_fs = command(["trivy", "fs", "--scanners", "vuln,secret", "--format", "json", "."], timeout=300)
    trivy_image = command(["trivy", "image", "--format", "json", args.image], timeout=300)
    docker_inspect = command(["docker", "image", "inspect", args.image])

    dependency_status = "PASS" if safety.get("returnCode") == 0 and dependency_tree.get("returnCode") == 0 else "FAIL"
    if scanner_status(trivy_fs) == "NOT_RUN" and dependency_status == "PASS":
        dependency_status = "PARTIAL"
    write_json(output_dir / "dependency-scan.json", {
        "status": dependency_status,
        "generatedAt": now(),
        "scanner": "Trivy filesystem scan in CI; Maven dependency tree is the local fallback inventory",
        "highCriticalGate": "CI blocks HIGH and CRITICAL findings with exit-code 1",
        "repositorySafety": safety,
        "mavenDependencyInventory": dependency_tree,
        "trivyFilesystem": trivy_fs,
        "trivyStatus": scanner_status(trivy_fs),
        "localScannerAvailability": shutil.which("trivy") is not None,
    })
    container_status = scanner_status(trivy_image)
    write_json(output_dir / "container-scan.json", {
        "status": container_status,
        "generatedAt": now(),
        "image": args.image,
        "scanner": "Trivy image scan in CI",
        "highCriticalGate": "CI blocks HIGH and CRITICAL findings with exit-code 1",
        "trivy": trivy_image,
        "trivyStatus": container_status,
        "imageInspect": docker_inspect,
        "localScannerAvailability": shutil.which("trivy") is not None,
    })

    pom = ET.parse(ROOT / "pom.xml").getroot()
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    components = []
    for dependency in pom.findall("m:dependencies/m:dependency", namespace):
        group = dependency.findtext("m:groupId", default="", namespaces=namespace)
        artifact = dependency.findtext("m:artifactId", default="", namespaces=namespace)
        version = dependency.findtext("m:version", default="managed-by-spring-boot", namespaces=namespace)
        scope = dependency.findtext("m:scope", default="compile", namespaces=namespace)
        components.append({"type": "library", "group": group, "name": artifact, "version": version, "scope": scope})
    sbom = {
        "status": "PARTIAL",
        "bomFormat": "CycloneDX",
        "specVersion": "1.5",
        "serialNumber": "urn:uuid:campus-errand-grab-local",
        "version": 1,
        "metadata": {"timestamp": now(), "component": {"type": "application", "name": "campus-errand-grab", "version": "0.0.1-SNAPSHOT"}},
        "components": components,
        "scope": "direct-pom-dependencies-only; CI anchore action publishes the complete build SBOM",
        "completeBuildSbom": "NOT_RUN_LOCAL_CI_GENERATED",
    }
    write_json(output_dir / "sbom.json", sbom)
    print(f"security_reports={output_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
