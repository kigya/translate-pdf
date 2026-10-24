#!/usr/bin/env python3
"""Fail closed unless every listed owned native source has 100% LLVM coverage."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import Any

METRICS = ("lines", "functions", "regions", "branches")
LLVM_REQUIRED_METRICS = METRICS
JVM_REQUIRED_METRICS = ("instructions", "lines", "functions", "branches")
SOURCE_SUFFIXES = {".c", ".cc", ".cpp", ".cxx", ".h", ".hpp", ".m", ".mm", ".java", ".kt", ".swift"}
EXCLUDED_NATIVE_SOURCES = {
    "native/core.hpp": "public API declarations only; executable implementations are in core.cpp",
    "native/smoke.cpp": "native host-test executable, not shipped application code",
    "native/guard_test.cpp": "native guard-test executable, not shipped application code",
    "native/tests/core_detail_test.cpp": "handwritten native core host-regression harness; production core.cpp remains owned and measured",
    "native/tests/core_fault_test.cpp": "handwritten native core fault-injection harness; production core implementations remain owned and measured",
    "native/tests/jni_bridge_test.cpp": "handwritten JNI host-regression harness; production JNI bridge remains owned and measured",
    "native/tests/docx_fault_test.cpp": "handwritten DOCX ZIP-boundary fault harness linked to the measured production parser object; not shipped application code",
    "native/tests/jni_shim/android/bitmap.h": "test-only Android Bitmap boundary declaration for host JNI tests",
    "ios/TranslatePDF/TranslateCoreBridge.h": "Objective-C interface declarations only; executable implementation is in TranslateCoreBridge.mm",
}
EXCLUDED_NATIVE_SOURCES.update({'native/docx.hpp': 'DOCX API declarations only; implementations remain measured in native/docx.cpp', 'native/tests/docx_core_test.cpp': 'DOCX host regression/adversarial test harness, not shipped application code', 'native/vendor/miniz_zip.h': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.', 'native/vendor/pugiconfig.hpp': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.', 'native/vendor/miniz.c': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.', 'native/vendor/miniz_tinfl.h': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.', 'native/vendor/miniz_tdef.c': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.', 'native/vendor/miniz_export.h': 'Generated static-link export annotation header, no executable code; exact bytes audited in native/vendor/manifest.json.', 'native/vendor/pugixml.hpp': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.', 'native/vendor/miniz.h': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.', 'native/vendor/miniz_zip.c': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.', 'native/vendor/pugixml.cpp': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.', 'native/vendor/miniz_tinfl.c': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.', 'native/vendor/miniz_tdef.h': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.', 'native/vendor/miniz_common.h': 'Unmodified pinned third-party dependency; SHA-256 and license are audited in native/vendor/manifest.json.'})
DECLARATION_ONLY_NATIVE_SOURCES = {
    'native/core_detail.hpp': {
        "sha256": "d1f93c10855efcbb420a78cf07873911aa4864cfb836250817731d513d78e30e",
        "reason": "Reviewed internal type and function declarations only; default state is initialized by measured constructors in core.cpp.",
    },
    'native/source_layout.hpp': {
        "sha256": "2c4c00fa3643dd1b4cd947f1a1da19ee48608f8067e6547eccf73ff20eda3838",
        "reason": "Reviewed source-layout types and function declarations only; default fit state is initialized by the measured constructor in source_layout.cpp.",
    },
    "native/core_operations.hpp": {
        "sha256": "0c0ad739dfb70217b55025d99738f41d30638e1365acd1ee80176b02b2cfa76f",
        "reason": "Reviewed vendor-operation pointer declarations only; executable bindings remain measured in core.cpp.",
    },
}
GENERATED_NATIVE_ADAPTER_SOURCES = {
    "ios/TranslatePDF/PdfTranslatorBridge.m": {
        "sha256": "922c228fb636f0a154f32c493ab31df6ba9892ff9066c5ea91263561b3899c53",
        "classification": "vendor_macro_generated_objc_registration_adapter",
        "dependencies": [{
            "path": "node_modules/react-native/React/Base/RCTBridgeModule.h",
            "sha256": "c06bddc821d27359440dd48820ee9a164ee24e706a4aded4a83b016bde7533ba",
            "required_markers": [
                "#define RCT_EXTERN_REMAP_MODULE(js_name, objc_name, objc_supername)",
                "+(NSString *)moduleName",
                "RCTRegisterModule([objc_name class]);",
                "#define _RCT_EXTERN_REMAP_METHOD(js_name, method, is_blocking_synchronous_method)",
                "static RCTMethodInfo config = {#js_name, #method, is_blocking_synchronous_method};",
            ],
        }],
        "contract": {
            "path": "ios/tests/NativeCoverageTests.swift",
            "sha256": "6e72290745b5de587a55f58393a5cbc356db79e141b808475720ed0827e6bd58",
            "test": "testObjectiveCExportMetadataMatchesPublicBridgeContract",
            "expected_export_count": 30,
        },
        "reason": "The app-owned file is only an exact RN extern-macro registration and method-signature adapter; the pinned React Native macros emit the executable Objective-C getters, moduleName implementation, and registration constructor. The XCTest contract is source-bound and statically audited; this policy does not claim runtime execution or coverage counters.",
    },
    "ios/TranslatePDF/SceneDelegate.swift": {
        "sha256": "e801fc71b2f9e352811dd8b080f6fb08a7a0cb078c1015fc18b5d0f359dfd92e",
        "classification": "compiler_synthesized_empty_expo_scene_adapter",
        "dependencies": [{
            "path": "node_modules/expo/ios/Expo/ExpoAppSceneDelegate.swift",
            "sha256": "a39cfa10500282124e09a96e1ef9c61dfd8c2ac9ef69f3e2bbde959e75074eb6",
            "required_markers": [
                "open class ExpoAppSceneDelegate: UIResponder, UIWindowSceneDelegate {",
                "open func scene(",
                "connectionOptions.url",
                "forwarder.open(url:",
            ],
        }],
        "integration": {
            "path": "ios/TranslatePDF/Info.plist",
            "sha256": "1e41b6658663a6df392cf2ae72a89142fcdd8a5d786c58aae0545a7b48dd9e53",
            "required_markers": [
                "<key>UISceneDelegateClassName</key>",
                "<string>$(PRODUCT_MODULE_NAME).SceneDelegate</string>",
            ],
        },
        "reason": "The app-owned Swift source is an empty Expo superclass adapter; lifecycle and deep-link implementation stays in the hash-pinned Expo superclass. UIKit class resolution remains app-owned and is bound to the exact Info.plist wiring. The adapter's synthesized initialization/destruction is not reported as measured app coverage.",
    },
}
CPP_FUNCTION_BODY = re.compile(r"\)\s*(?:const\b\s*)?(?:noexcept\b(?:\s*\([^)]*\))?\s*)?(?:->[^;{}]+)?\s*\{")


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


def cpp_function_body_count(source_text: str) -> int:
    """Conservatively count C++ function-like bodies after removing comments/literals."""
    code = re.sub(r"/\*.*?\*/|//[^\n]*", "", source_text, flags=re.DOTALL)
    code = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'', "", code)
    return len(CPP_FUNCTION_BODY.findall(code))


def declaration_only_evidence(root: Path, source: str) -> dict[str, Any]:
    spec = DECLARATION_ONLY_NATIVE_SOURCES.get(source)
    if spec is None:
        raise ValueError(f"No declaration-only source audit exists for {source}")
    path = root / source
    actual_hash = sha256_file(path)
    if actual_hash != spec["sha256"]:
        raise ValueError(f"Declaration-only source changed and needs coverage review: {source}")
    text = path.read_text(encoding="utf-8")
    function_bodies = cpp_function_body_count(text)
    if function_bodies != 0:
        raise ValueError(f"Declaration-only source contains executable function bodies: {source}")
    return {
        "source": source,
        "classification": "declaration_only_zero_executable_functions",
        "source_sha256": actual_hash,
        "function_body_count": function_bodies,
        "reason": spec["reason"],
    }


def _verified_file_text(root: Path, relative_path: str, expected_hash: str, purpose: str) -> str:
    path = root / relative_path
    if not path.is_file():
        raise ValueError(f"Required {purpose} file is missing: {relative_path}")
    actual_hash = sha256_file(path)
    if actual_hash != expected_hash:
        raise ValueError(f"Required {purpose} file changed and needs coverage review: {relative_path}")
    return path.read_text(encoding="utf-8")


def _verify_objc_bridge_contract(root: Path, contract: dict[str, Any]) -> dict[str, Any]:
    text = _verified_file_text(root, contract["path"], contract["sha256"], "Objective-C metadata contract")
    method_name = contract["test"]
    start = text.find(f"func {method_name}() throws {{")
    if start < 0:
        raise ValueError(f"Bound metadata contract test is missing: {method_name}")
    end = text.find("\n  func ", start + 1)
    body = text[start:] if end < 0 else text[start:end]
    list_start = body.find("let expectedSignatures: Set<String> = [")
    list_end = body.find("\n    ]", list_start)
    if list_start < 0 or list_end < 0:
        raise ValueError(f"Bound metadata contract has no exact signature list: {method_name}")
    signature_block = body[list_start:list_end]
    signatures = re.findall(r'^\s+"[^"\n]+",?$', signature_block, flags=re.MULTILINE)
    expected = int(contract["expected_export_count"])
    if len(signatures) != expected:
        raise ValueError(f"Bound metadata contract must enumerate exactly {expected} signatures: {method_name}")
    required = (
        "class_copyMethodList(metaclass, &methodCount)",
        "getter(nativeClass as AnyObject, selector)",
        "XCTAssertEqual(observedSignatures, expectedSignatures)",
        'XCTAssertEqual(moduleName as? String, "PdfTranslator")',
    )
    if any(marker not in body for marker in required):
        raise ValueError(f"Bound metadata contract lost a required runtime assertion: {method_name}")
    return {
        "path": contract["path"],
        "sha256": contract["sha256"],
        "test": method_name,
        "expected_export_count": expected,
        "statically_verified_signature_count": len(signatures),
        "runtime_status": "not_claimed_by_static_classification",
    }


def generated_adapter_evidence(root: Path, source: str) -> dict[str, Any]:
    """Verify exact source/dependency/contract provenance for reviewed generated adapters."""
    spec = GENERATED_NATIVE_ADAPTER_SOURCES.get(source)
    if spec is None:
        raise ValueError(f"No generated-adapter source audit exists for {source}")
    source_text = _verified_file_text(root, source, spec["sha256"], "generated adapter source")
    if source == "ios/TranslatePDF/PdfTranslatorBridge.m":
        lines = [line.strip() for line in source_text.splitlines() if line.strip()]
        if (len(lines) != 33 or lines[0] != "#import <React/RCTBridgeModule.h>"
                or lines[1] != "@interface RCT_EXTERN_REMAP_MODULE(PdfTranslator, PdfTranslatorModule, NSObject)"
                or lines[-1] != "@end"
                or any(not re.fullmatch(r"RCT_EXTERN_METHOD\([^{};]+\)", line) for line in lines[2:-1])):
            raise ValueError(f"Objective-C generated adapter contains syntax outside the pinned macro contract: {source}")
        if any(token in source_text for token in ("@implementation", "{", "}", "#define")):
            raise ValueError(f"Objective-C generated adapter contains handwritten executable syntax: {source}")
        if len(lines[2:-1]) != int(spec["contract"]["expected_export_count"]):
            raise ValueError(f"Objective-C generated adapter export count changed: {source}")
        signatures = [re.match(r"RCT_EXTERN_METHOD\((\w+):", line).group(1) for line in lines[2:-1]]
        if len(signatures) != len(set(signatures)):
            raise ValueError(f"Objective-C generated adapter contains duplicate exports: {source}")
        contract = _verify_objc_bridge_contract(root, spec["contract"])
    elif source == "ios/TranslatePDF/SceneDelegate.swift":
        lines = [line.strip() for line in source_text.splitlines()
                 if line.strip() and not line.strip().startswith("///")]
        if lines != ["internal import Expo", "class SceneDelegate: ExpoAppSceneDelegate {}"]:
            raise ValueError(f"Scene adapter contains handwritten executable syntax: {source}")
        contract = None
    else:
        raise ValueError(f"Generated-adapter allowlist has no syntax validator for {source}")

    dependencies = []
    for dependency in spec["dependencies"]:
        dependency_text = _verified_file_text(root, dependency["path"], dependency["sha256"], "generated-code dependency")
        if any(marker not in dependency_text for marker in dependency["required_markers"]):
            raise ValueError(f"Pinned generated-code dependency contract changed: {dependency['path']}")
        dependencies.append({"path": dependency["path"], "sha256": dependency["sha256"],
                             "verified_markers": len(dependency["required_markers"])})

    integration = None
    if "integration" in spec:
        integration_spec = spec["integration"]
        integration_text = _verified_file_text(root, integration_spec["path"], integration_spec["sha256"],
                                                "scene adapter integration contract")
        if any(marker not in integration_text for marker in integration_spec["required_markers"]):
            raise ValueError(f"Scene adapter integration contract changed: {integration_spec['path']}")
        integration = {"path": integration_spec["path"], "sha256": integration_spec["sha256"],
                       "verified_markers": len(integration_spec["required_markers"])}

    return {
        "source": source,
        "classification": spec["classification"],
        "source_sha256": spec["sha256"],
        "allowed_syntax_verified": True,
        "dependencies": dependencies,
        "metadata_contract": contract,
        "integration_contract": integration,
        "reason": spec["reason"],
    }


def verify_generated_adapter_inventory(root: Path, owned: list[str]) -> dict[str, dict[str, Any]]:
    """Keep generated adapters inventoried while excluding only their generated bodies."""
    return {
        source: generated_adapter_evidence(root, source)
        for source in sorted(GENERATED_NATIVE_ADAPTER_SOURCES)
        if source in owned
    }


def verify_source_inventory(root: Path, owned: list[str]) -> dict[str, dict[str, Any]]:
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
    return {
        source: declaration_only_evidence(root, source)
        for source in sorted(DECLARATION_ONLY_NATIVE_SOURCES)
        if source in owned
    }


def load_llvm_files(path: Path) -> dict[str, dict[str, Any]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    files: dict[str, dict[str, Any]] = {}
    for unit in data.get("data", []):
        for item in unit.get("files", []):
            filename = item.get("filename")
            if filename:
                summary = dict(item.get("summary", {}))
                for metric in METRICS:
                    value = summary.get(metric)
                    if isinstance(value, dict):
                        value["provenance"] = "LLVM source mapping"
                # Swift's current LLVM export exposes line/region/function
                # mapping but no branch records. A zero denominator here means
                # unsupported instrumentation, not a proven branchless file.
                if Path(filename).suffix == ".swift":
                    summary.pop("branches", None)
                files[Path(filename).as_posix()] = summary
    return files


def load_jacoco_files(path: Path) -> dict[str, dict[str, Any]]:
    """Read source-level JaCoCo counters without inventing LLVM regions."""
    root = ET.parse(path).getroot()
    files: dict[str, dict[str, Any]] = {}
    for package in root.findall(".//package"):
        package_name = package.get("name", "")
        for source in package.findall("sourcefile"):
            filename = source.get("name")
            if not filename:
                continue
            relative = f"{package_name.replace('.', '/')}/{filename}" if package_name else filename
            metrics: dict[str, Any] = {}
            for counter in source.findall("counter"):
                kind = counter.get("type")
                missed = int(counter.get("missed", "0"))
                covered = int(counter.get("covered", "0"))
                metric = {"percent": 100.0 if missed + covered == 0 else 100.0 * covered / (missed + covered),
                          "covered": covered, "count": missed + covered,
                          "provenance": "JaCoCo " + str(kind)}
                if kind == "LINE":
                    metrics["lines"] = metric
                elif kind == "METHOD":
                    metrics["functions"] = metric
                elif kind == "BRANCH":
                    metrics["branches"] = metric
                elif kind == "INSTRUCTION":
                    metrics["instructions"] = metric
            # JaCoCo 0.8.15 XMLCoverageWriter omits zero-total counters. JVM
            # branch instrumentation is supported: an executable source with
            # no branch counter/line hits has zero branches, unlike Swift's
            # unsupported LLVM branch mapping. Never fill a contradictory XML.
            if ("branches" not in metrics and metrics.get("instructions", {}).get("count", 0) > 0
                    and not any(int(line.get("mb", "0")) + int(line.get("cb", "0")) > 0
                                for line in source.findall("line"))):
                metrics["branches"] = {"percent": 100.0, "covered": 0, "count": 0,
                                       "provenance": "JaCoCo XML omitted zero-total BRANCH counter"}
            files[relative] = metrics
    return files


def load_xccov_files(path: Path) -> dict[str, dict[str, Any]]:
    """Read Xcode's real test coverage for Swift/ObjC sources (line/function only)."""
    data = json.loads(path.read_text(encoding="utf-8"))
    files: dict[str, dict[str, Any]] = {}
    for target in data.get("targets", []):
        for item in target.get("files", []):
            filename = item.get("path")
            if not filename:
                continue
            line_count = int(item.get("executableLines", 0))
            function_items = item.get("functions", [])
            function_count = len(function_items)
            summary = {
                "lines": {
                    "covered": int(item.get("coveredLines", 0)),
                    "count": line_count,
                    "percent": 100.0 if line_count == 0 else 100.0 * int(item.get("coveredLines", 0)) / line_count,
                    "provenance": "Xcode xccov XCTest line coverage",
                },
                "functions": {
                    "covered": sum(1 for function in function_items if int(function.get("executionCount", 0)) > 0),
                    "count": function_count,
                    "percent": 100.0 if function_count == 0 else 100.0 * sum(
                        1 for function in function_items if int(function.get("executionCount", 0)) > 0
                    ) / function_count,
                    "provenance": "Xcode xccov XCTest function execution counts",
                },
            }
            files[Path(filename).as_posix()] = summary
    return files


def find_coverage(source: str, files: dict[str, dict[str, Any]]) -> dict[str, Any] | None:
    matches = [summary for filename, summary in files.items()
               if filename == source or filename.endswith("/" + source) or source.endswith("/" + filename)]
    # JaCoCo reports Java package paths, while Android Kotlin sources can live
    # under a legacy filesystem directory that differs from their declared
    # package. Resolve that mismatch only by a unique exact source basename.
    # Ambiguous basenames remain a hard error rather than guessing.
    if not matches:
        source_name = Path(source).name
        matches = [summary for filename, summary in files.items()
                   if Path(filename).name == source_name]
    if len(matches) > 1:
        raise ValueError(f"Ambiguous coverage entries for owned source {source}")
    return matches[0] if matches else None


def analyze(sources: list[str], files: dict[str, dict[str, Any]],
            jacoco_files: dict[str, dict[str, Any]] | None = None,
            xccov_files: dict[str, dict[str, Any]] | None = None,
            declaration_only_sources: dict[str, dict[str, Any]] | None = None,
            generated_adapter_sources: dict[str, dict[str, Any]] | None = None) -> dict[str, Any]:
    jacoco_files = jacoco_files or {}
    xccov_files = xccov_files or {}
    declaration_only_sources = declaration_only_sources or {}
    generated_adapter_sources = generated_adapter_sources or {}
    results = []
    for source in sources:
        summary = find_coverage(source, files)
        jacoco_summary = find_coverage(source, jacoco_files)
        xccov_summary = find_coverage(source, xccov_files)
        if source in generated_adapter_sources:
            results.append({
                "source": source,
                "status": "excluded_generated",
                "metrics": {},
                "required_metrics": [],
                "errors": [],
                "generated_source_audit": generated_adapter_sources[source],
            })
            continue
        if source in declaration_only_sources:
            observed = summary or {}
            executable_counts = {
                metric: int(observed.get(metric, {}).get("count", 0))
                for metric in LLVM_REQUIRED_METRICS
            }
            unexpected = {metric: count for metric, count in executable_counts.items() if count != 0}
            errors = (["LLVM reports executable counters for a source audited as declaration-only: "
                       + ", ".join(f"{metric}={count}" for metric, count in sorted(unexpected.items()))]
                      if unexpected else [])
            metrics = {
                metric: {"percent": 100.0, "covered": 0, "count": 0,
                         "provenance": "source-audited declaration-only; zero LLVM executable counters"}
                for metric in LLVM_REQUIRED_METRICS
            }
            results.append({
                "source": source,
                "status": "failed" if errors else "passed",
                "metrics": metrics,
                "required_metrics": list(LLVM_REQUIRED_METRICS),
                "errors": errors,
                "source_audit": declaration_only_sources[source],
            })
            continue
        if summary is None and jacoco_summary is None and xccov_summary is None:
            results.append({"source": source, "status": "unmeasured", "metrics": {}})
            continue
        summary = summary or {}
        jacoco_summary = jacoco_summary or {}
        xccov_summary = xccov_summary or {}
        metrics = {}
        errors = []
        is_jvm_source = source.startswith("android/") and Path(source).suffix in {".java", ".kt"}
        required_metrics = JVM_REQUIRED_METRICS if is_jvm_source else LLVM_REQUIRED_METRICS
        for metric in required_metrics:
            # xccov is the XCTest source of record for iOS app/pod targets;
            # LLVM host tests and JaCoCo remain authoritative for their sources.
            value = ((jacoco_summary.get(metric) or summary.get(metric) or xccov_summary.get(metric))
                     if is_jvm_source else (xccov_summary.get(metric) or summary.get(metric) or jacoco_summary.get(metric)))
            if not isinstance(value, dict) or "percent" not in value:
                errors.append(f"missing {metric} coverage data")
                continue
            percent = float(value["percent"])
            count = int(value.get("count", 0))
            covered = int(value.get("covered", 0))
            metrics[metric] = {"percent": percent, "covered": covered, "count": count,
                               "provenance": value.get("provenance", "LLVM source mapping")}
            if count == 0 and metric != "branches":
                errors.append(f"no measurable {metric}")
            elif covered != count:
                errors.append(f"{metric} coverage {percent:g}%")
        results.append({
            "source": source,
            "status": "passed" if not errors else "failed",
            "metrics": ({**metrics, "regions": {
                "status": "not_applicable",
                "reason": "JaCoCo does not report LLVM regions; JVM coverage is gated on instructions, lines, methods, and branches.",
                "provenance": "tool unsupported for JVM source",
            }} if is_jvm_source else metrics),
            "required_metrics": list(required_metrics),
            "errors": errors,
        })
    return {
        "gate": "strict-native-owned-coverage",
        "required_percent": 100,
        "metrics": list(METRICS) + ["instructions"],
        "metric_policies": {"LLVM": list(LLVM_REQUIRED_METRICS), "JaCoCo JVM": list(JVM_REQUIRED_METRICS)},
        "owned_source_count": len(sources),
        "owned_executable_source_count": len(sources) - len(generated_adapter_sources),
        "excluded_generated_source_count": len(generated_adapter_sources),
        "measured_source_count": sum(result["status"] not in {"unmeasured", "excluded_generated"} for result in results),
        "passed_source_count": sum(result["status"] == "passed" for result in results),
        "unmeasured_sources": [result["source"] for result in results if result["status"] == "unmeasured"],
        "excluded_generated_sources": [result["source"] for result in results if result["status"] == "excluded_generated"],
        "failed_sources": [result["source"] for result in results if result["status"] == "failed"],
        "results": results,
    }


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--owned-sources", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, default=Path.cwd())
    parser.add_argument("--llvm-json", type=Path, action="append", required=True,
                        help="LLVM export JSON; repeat for distinct native toolchain outputs")
    parser.add_argument("--jacoco-xml", type=Path, help="Optional JaCoCo XML report; missing LLVM-only metrics still fail closed")
    parser.add_argument("--xccov-json", type=Path, action="append", default=[],
                        help="Xcode xccov XCTest JSON; supplies measured lines/functions only, not LLVM branches/regions")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    try:
        sources = load_owned_sources(args.owned_sources)
        declaration_only = verify_source_inventory(args.repo_root.resolve(), sources)
        generated_adapters = verify_generated_adapter_inventory(args.repo_root.resolve(), sources)
        llvm_files: dict[str, dict[str, Any]] = {}
        for llvm_path in args.llvm_json:
            duplicate_sources = set(llvm_files).intersection(load_llvm_files(llvm_path))
            if duplicate_sources:
                raise ValueError("Multiple LLVM inputs report the same source; merge raw profiles first: "
                                 + ", ".join(sorted(duplicate_sources)))
            llvm_files.update(load_llvm_files(llvm_path))
        xccov_files: dict[str, dict[str, Any]] = {}
        for xccov_path in args.xccov_json:
            duplicate_sources = set(xccov_files).intersection(load_xccov_files(xccov_path))
            if duplicate_sources:
                raise ValueError("Multiple xccov inputs report the same source; merge XCTest result bundles first: "
                                 + ", ".join(sorted(duplicate_sources)))
            xccov_files.update(load_xccov_files(xccov_path))
        result = analyze(sources, llvm_files,
                         load_jacoco_files(args.jacoco_xml) if args.jacoco_xml else None,
                         xccov_files, declaration_only, generated_adapters)
        result["tool_inputs"] = {
            "llvm_json": [{"path": str(path), "sha256": sha256_file(path)} for path in args.llvm_json],
            "jacoco_xml": ({"path": str(args.jacoco_xml), "sha256": sha256_file(args.jacoco_xml)}
                           if args.jacoco_xml else None),
            "xccov_json": [{"path": str(path), "sha256": sha256_file(path)} for path in args.xccov_json],
        }
        for item in result["results"]:
            item["source_sha256"] = sha256_file(args.repo_root / item["source"])
        result["exact_exclusions"] = [
            {"source": source, "reason": reason}
            for source, reason in sorted(EXCLUDED_NATIVE_SOURCES.items())
        ]
        result["declaration_only_sources"] = list(declaration_only.values())
        result["generated_adapter_sources"] = list(generated_adapters.values())
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(f"Native coverage gate could not read its inputs: {exc}", file=sys.stderr)
        return 2

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    for item in result["results"]:
        if item["status"] == "unmeasured":
            print(f"UNMEASURED {item['source']}")
        elif item["status"] == "excluded_generated":
            print(f"EXCLUDED GENERATED {item['source']}: {item['generated_source_audit']['classification']}")
        elif item["status"] == "failed":
            print(f"FAILED {item['source']}: {'; '.join(item['errors'])}")
        else:
            print(f"PASS {item['source']}: all native coverage metrics at 100%")
    print("Native owned coverage: %d/%d files passed; %d unmeasured; %d below 100%%" % (
        result["passed_source_count"], result["owned_source_count"],
        len(result["unmeasured_sources"]), len(result["failed_sources"])))
    return 0 if result["passed_source_count"] == result["owned_executable_source_count"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
