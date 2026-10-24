#!/usr/bin/env python3
"""Reconcile exact source decisions across required actual iOS build variants.

This preserves inactive-only sites and unresolved classifications. Configuration
inventory reconciliation is not transformation, runtime or coverage approval.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("union_certification_audit", HERE / "audit_certification.py")
auditor = importlib.util.module_from_spec(spec); spec.loader.exec_module(auditor)
cert = auditor.cert
REQUIRED = {"debug-simulator", "release-simulator", "release-device"}


def required_variant_matches(label: str, job: dict) -> bool:
    if label not in REQUIRED:return False
    target="arm64-apple-ios17.0"+("-simulator" if label.endswith("simulator") else "")
    return job.get("target")==target and ("DEBUG" in job.get("swiftDefines",[]))==label.startswith("debug-") and job.get("languageMode")=="5"



def identity(source: dict, site: dict) -> str:
    return cert.coverage.digest({"source": source["source"], "sourceSHA256": source["sourceSHA256"],
                                 "kind": site["kind"], "startUTF8": site["startUTF8"], "endUTF8": site["endUTF8"]})


def reconcile(paths: dict[str, Path], universe_path: Path) -> dict:
    failures = []; source_hashes = {}; variants = {}; union = {}; classifications = {}
    if set(paths) != REQUIRED: failures.append("Exactly the required Debug simulator, Release simulator and Release device variants are required")
    universe = json.loads(universe_path.read_text())
    if universe["registryDigest"] != cert.coverage.digest(cert.coverage.registry_payload(universe)):
        failures.append("Unconfigured source universe digest differs")
    source_universe = {}; universe_unsupported = []
    for source in universe["sources"]:
        if cert.coverage.sha(Path(source["source"])) != source["sourceSHA256"]: failures.append("Static source universe is stale")
        source_hashes[source["source"]] = source["sourceSHA256"]
        universe_unsupported.extend({"source": source["source"], **item} for item in source["unsupported"])
        if any(item["kind"] != "ifConfigDecl" for item in source["unsupported"]):
            failures.append("Unconfigured original source contains unsupported non-configuration syntax")
        for site in source["sites"]:
            key = identity(source, site)
            source_universe[key] = {"source": source["source"], "sourceSHA256": source["sourceSHA256"],
                                    **site, "classification": "unresolved-until-reconciled-with-all-required-jobs"}
    for label, path in paths.items():
        packet = json.loads(path.read_text())
        integrity = auditor.audit(path)
        if not integrity["artifactIntegrityPassed"]: failures.append(f"{label}: compiler evidence integrity failed")
        if packet["failures"]: failures.append(f"{label}: unresolved compiler collection failures")
        manifest = json.loads(Path(packet["manifest"]["path"]).read_text())
        jobs = json.loads(Path(packet["jobs"]["path"]).read_text())
        configuration = json.loads(Path(packet["configurationAudit"]["path"]).read_text())
        if not configuration["passed"]: failures.append(f"{label}: actual configuration audit failed")
        if {source["source"] for source in manifest["sources"]} != set(source_hashes): failures.append(f"{label}: exact owned-source inventory differs")
        for job in jobs:
            if not required_variant_matches(label,job):
                failures.append(f"{label}: actual target/definitions do not match the required variant")
        variants[label] = {"evidence": cert.artifact(path), "registryDigest": manifest["registryDigest"],
                           "actualJobs": [{key: job[key] for key in ("module", "target", "sdk", "languageMode", "swiftDefines", "argumentsDigest")} for job in jobs],
                           "sources": [{"source": source["source"], "configurationRegions": source["configurationRegions"]} for source in manifest["sources"]],
                           "candidateSiteCount": len(manifest["registry"])}
        for source in manifest["sources"]:
            if source_hashes.get(source["source"]) != source["sourceSHA256"]: failures.append(f"{label}: source changed between configurations")
            for item in source["classificationInventory"]:
                key = item["classificationID"]
                record = classifications.setdefault(key, {"record": item, "activeVariants": []})
                if record["record"] != item: failures.append("Classification drift between configurations")
                record["activeVariants"].append(label)
            for site in source["sites"]:
                key = identity(source, site)
                if key not in source_universe: failures.append(f"{label}: active site absent from original source universe")
                elif source_universe[key]["outcomes"] != site["outcomes"]: failures.append("Active outcomes differ from original source universe")
                record = union.setdefault(key, {"identity": key, "source": source["source"], "sourceSHA256": source["sourceSHA256"],
                                                "kind": site["kind"], "startUTF8": site["startUTF8"], "endUTF8": site["endUTF8"],
                                                "outcomes": site["outcomes"], "activeVariants": {}, "runtimeHits": None,
                                                "metricRole":site.get("metricRole","unmapped-blocker"), "metricRule":site.get("metricRule"),
                                                "coverageDisposition": "all-outcomes-retained-pending-certified-runtime-measurement"})
                if record["outcomes"] != site["outcomes"]: failures.append("Concrete source outcomes changed between configurations")
                if record["metricRole"] != site.get("metricRole","unmapped-blocker") or record["metricRule"] != site.get("metricRule"):
                    failures.append("Concrete metric role changed between configurations")
                if record["metricRole"] == "unmapped-blocker": failures.append("Unmapped metric role remains in active configuration")
                record["activeVariants"][label] = f'{source["namespace"]}:{site["id"]}'
    inactive = []
    for key, site in source_universe.items():
        if key in union: continue
        per_variant = {}
        for label, variant in variants.items():
            source = next((item for item in variant["sources"] if item["source"] == site["source"]), None)
            regions = [] if source is None else [region for region in source["configurationRegions"]
                                                if region["startUTF8"] <= site["startUTF8"] < site["endUTF8"] <= region["endUTF8"]]
            per_variant[label] = regions
            if not regions or any(region["state"] != "inactive" for region in regions):
                failures.append("Unreconciled original decision absent from active configuration union")
        inactive.append({**site, "inactiveRegionsByVariant": per_variant,
                         "classification": "inactive-in-recorded-build-variants-retained-for-independent-platform-scope-review",
                         "excludedFromOwnedCode": False})
    return {"schemaVersion": 1, "sourceUniverse": cert.artifact(universe_path), "reconciler": cert.artifact(Path(__file__)),
            "configurationInventoryReconciled": not failures, "failures": failures,
            "variants": variants, "requiredVariants": sorted(REQUIRED), "sourceSHA256": source_hashes,
            "sourceDecisionUnion": list(union.values()), "inactiveOnlySourceDecisions": inactive,
            "sourceUniverseUnsupported": universe_unsupported,
            "classifications": list(classifications.values()), "semanticEquivalenceCertified": False,
            "applicationGateApproved": False, "sourceBranchDenominator": None,
            "status": "configuration-inventory-only-pending-independent-semantic-platform-scope-and-runtime-proof"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", action="append", required=True, metavar="LABEL=EVIDENCE")
    parser.add_argument("--source-universe", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(); variants = {}
    for item in args.variant:
        label, path = item.split("=", 1)
        if label in variants: raise ValueError("Duplicate required configuration label")
        variants[label] = Path(path)
    report = reconcile(variants, args.source_universe)
    cert.coverage.write(args.output, report)
    print(json.dumps({key: report[key] for key in ("status", "configurationInventoryReconciled", "failures", "applicationGateApproved")}, indent=2))
    return 0 if report["configurationInventoryReconciled"] else 1


if __name__ == "__main__": raise SystemExit(main())
