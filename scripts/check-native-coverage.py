#!/usr/bin/env python3
"""Fail closed unless every listed owned native source has 100% LLVM coverage."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any

METRICS = ("lines", "functions", "regions", "branches")
SOURCE_SUFFIXES = {".c", ".cc", ".cpp", ".cxx", ".h", ".hpp", ".m", ".mm", ".java", ".kt", ".swift"}
EXCLUDED_NATIVE_SOURCES = {
    "native/core.hpp": "public API declarations only; executable implementations are in core.cpp",
    "native/smoke.cpp": "native host-test executable, not shipped application code",
    "native/guard_test.cpp": "native guard-test executable, not shipped application code",
    "ios/TranslatePDF/TranslateCoreBridge.h": "Objective-C interface declarations only; executable implementation is in TranslateCoreBridge.mm",
}


def load_owned_sources(path: Path) -> list[str]:
    sources = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line and not line.startswith("#"):
            sources.append(line)
    if not sources:
        raise ValueError(f"Owned source list is empty: {path}")
    if len(sources) != len(set(sources)):
        raise ValueError(f"Owned source list contains duplicates: {path}")
    return sources


def verify_source_inventory(root: Path, owned: list[str]) -> None:
    discovered = set()
    for directory in (root / "native", root / "android/app/src/main/java", root / "ios/TranslatePDF"):
        if not directory.is_dir():
            raise ValueError(f"Owned source directory is missing: {directory.relative_to(root)}")
        for path in directory.rglob("*"):
            if path.is_file() and path.suffix in SOURCE_SUFFIXES:
                discovered.add(path.relative_to(root).as_posix())
    exclusions = set(EXCLUDED_NATIVE_SOURCES)
    unknown_exclusions = exclusions - discovered
    if unknown_exclusions:
        raise ValueError(f"Exact source exclusions no longer exist: {', '.join(sorted(unknown_exclusions))}")
    expected = discovered - exclusions
    missing = expected - set(owned)
    stale = set(owned) - expected
    if missing or stale:
        parts = []
        if missing:
            parts.append("unlisted owned source files: " + ", ".join(sorted(missing)))
        if stale:
            parts.append("owned-list entries are not production sources: " + ", ".join(sorted(stale)))
        raise ValueError("; ".join(parts))


def load_llvm_files(path: Path) -> dict[str, dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    files: dict[str, dict[str, Any]] = {}
    for unit in data.get("data", []):
        for item in unit.get("files", []):
            filename = item.get("filename")
            if filename:
                files[Path(filename).as_posix()] = item.get("summary", {})
    return files


def find_coverage(source: str, files: dict[str, dict[str, Any]]) -> dict[str, Any] | None:
    matches = [summary for filename, summary in files.items()
               if filename == source or filename.endswith("/" + source)]
    if len(matches) > 1:
        raise ValueError(f"Ambiguous coverage entries for owned source {source}")
    return matches[0] if matches else None


def analyze(sources: list[str], files: dict[str, dict[str, Any]]) -> dict[str, Any]:
    results = []
    for source in sources:
        summary = find_coverage(source, files)
        if summary is None:
            results.append({"source": source, "status": "unmeasured", "metrics": {}})
            continue
        metrics = {}
        errors = []
        for metric in METRICS:
            value = summary.get(metric)
            if not isinstance(value, dict) or "percent" not in value:
                errors.append(f"missing {metric} coverage data")
                continue
            percent = float(value["percent"])
            count = int(value.get("count", 0))
            covered = int(value.get("covered", 0))
            metrics[metric] = {"percent": percent, "covered": covered, "count": count}
            if count == 0 and metric != "branches":
                errors.append(f"no measurable {metric}")
            elif covered != count:
                errors.append(f"{metric} coverage {percent:g}%")
        results.append({
            "source": source,
            "status": "passed" if not errors else "failed",
            "metrics": metrics,
            "errors": errors,
        })
    return {
        "gate": "strict-native-owned-coverage",
        "required_percent": 100,
        "metrics": list(METRICS),
        "owned_source_count": len(sources),
        "measured_source_count": sum(result["status"] != "unmeasured" for result in results),
        "passed_source_count": sum(result["status"] == "passed" for result in results),
        "unmeasured_sources": [result["source"] for result in results if result["status"] == "unmeasured"],
        "failed_sources": [result["source"] for result in results if result["status"] == "failed"],
        "results": results,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owned-sources", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--llvm-json", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    try:
        sources = load_owned_sources(args.owned_sources)
        verify_source_inventory(args.repo_root.resolve(), sources)
        result = analyze(sources, load_llvm_files(args.llvm_json))
        result["exact_exclusions"] = [
            {"source": source, "reason": reason}
            for source, reason in sorted(EXCLUDED_NATIVE_SOURCES.items())
        ]
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Native coverage gate could not read its inputs: {exc}", file=sys.stderr)
        return 2

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for item in result["results"]:
        if item["status"] == "unmeasured":
            print(f"UNMEASURED {item['source']}")
        elif item["status"] == "failed":
            print(f"FAILED {item['source']}: {'; '.join(item['errors'])}")
        else:
            print(f"PASS {item['source']}: all native coverage metrics at 100%")
    print("Native owned coverage: %d/%d files passed; %d unmeasured; %d below 100%%" % (
        result["passed_source_count"], result["owned_source_count"],
        len(result["unmeasured_sources"]), len(result["failed_sources"])))
    return 0 if result["passed_source_count"] == result["owned_source_count"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
