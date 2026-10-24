#!/usr/bin/env python3
"""Audit compiler review evidence without issuing semantic/coverage approval.

An independent report may be attached for provenance. Its prose or JSON approval
field never overrides compiler differences, unresolved obligations or zero hits.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("swift_certification_collector", HERE / "certification.py")
cert = importlib.util.module_from_spec(spec); spec.loader.exec_module(cert)


def audit(path: Path, independent_review: Path | None = None, recompute: bool = True) -> dict:
    packet = json.loads(path.read_text()); failures = []; verified = {}; manifest = None

    def check(reference: dict, label: str) -> None:
        filename, expected = reference.get("path"), reference.get("sha256")
        if not isinstance(filename, str) or not isinstance(expected, str):
            failures.append("Malformed artifact binding: " + label); return
        item = Path(filename)
        if not item.is_file(): failures.append("Missing evidence artifact: " + label); return
        identity = str(item.resolve())
        if identity not in verified: verified[identity] = cert.coverage.sha(item)
        if verified[identity] != expected: failures.append("Evidence artifact drift: " + label)

    def walk(value: object, label: str) -> None:
        if isinstance(value, dict):
            if "path" in value and "sha256" in value: check(value, label)
            for key, item in value.items(): walk(item, label + "/" + str(key))
        elif isinstance(value, list):
            for index, item in enumerate(value): walk(item, label + "/" + str(index))

    walk(packet, "packet")
    if packet.get("applicationGateApproved") is not False or packet.get("semanticEquivalenceCertified") is not False:
        failures.append("Evidence packet must not self-certify semantic equivalence or application coverage")
    if packet.get("counterStatus") != "denominator-only-zero-initialization-no-runtime-hits-collected":
        failures.append("Compiler review packet cannot contain measured-runtime claims")
    try:
        manifest = json.loads(Path(packet["manifest"]["path"]).read_text())
        walk(manifest,"manifest")
        if packet["registryDigest"] != manifest["registryDigest"] or manifest["registryDigest"] != cert.coverage.digest(cert.coverage.registry_payload(manifest)):
            failures.append("Manifest/registry binding differs")
        if packet["collector"] != cert.artifact(HERE / "certification.py"):
            failures.append("Collector implementation differs from captured evidence")
        expected_zeros = {site: {outcome: 0 for outcome in outcomes} for site, outcomes in manifest["registry"].items()}
        if packet["candidateOutcomes"] != expected_zeros:
            failures.append("Declared source outcomes were removed, changed or assigned unmeasured hits")
        classifications = [item for source in manifest["sources"] for item in source["classificationInventory"]]
        unsupported = [item for source in manifest["sources"] for item in source["unsupported"]]
        if packet["classifications"] != classifications or packet["unsupported"] != unsupported:
            failures.append("Unsupported/invariant/ownership inventory differs")
        by_site = {}
        for item in packet["siteObligations"]:
            if item["site"] in by_site: failures.append("Duplicate concrete typed site")
            by_site[item["site"]] = item
        if set(by_site) != set(manifest["registry"]): failures.append("Missing/unexpected concrete typed sites")
        for source in manifest["sources"]:
            for key, checksum in (("source", "sourceSHA256"), ("transformed", "transformedSHA256"), ("ast", "astSHA256")):
                check({"path": source[key], "sha256": source[checksum]}, key)
            ast = json.loads(Path(source["ast"]).read_text())
            if any(source.get(key) != value for key, value in ast.items()): failures.append("AST manifest does not match concrete source record")
            cert.validate_source_map(source)
            for node in source["sites"]:
                identity = f'{source["namespace"]}:{node["id"]}'
                item = by_site.get(identity)
                if not item: continue
                family = cert.FAMILIES.get(node["kind"])
                if any(item.get(key) != value for key, value in {
                    "kind": node["kind"], "source": source["source"], "sourceSHA256": source["sourceSHA256"],
                    "startUTF8": node["startUTF8"], "endUTF8": node["endUTF8"], "outcomes": node["outcomes"], "family": family,
                    "requiredProofs": cert.OBLIGATIONS.get(family, ["unsupported-transformation-family"]),
                }.items()): failures.append("Concrete site identity/obligation drift: " + identity)
                if not item.get("status", "").startswith(("pending-", "blocked-")):
                    failures.append("Concrete site cannot self-approve pending proof: " + identity)
        jobs = json.loads(Path(packet["jobs"]["path"]).read_text())
        for job in jobs:
            if job["argumentsDigest"] != cert.coverage.digest(job["arguments"]): failures.append("Actual job argument digest differs")
            for filename, checksum in job["responseFiles"].items(): check({"path": filename, "sha256": checksum}, "compiler-response")
        reconstructed = cert.read_jobs(Path(packet["compileLog"]["path"]), cert.coverage.ROOT / "ios", {job["module"] for job in jobs})
        if jobs != reconstructed: failures.append("Frozen compiler log/actual jobs differ")
        actual_config = cert.audit_configuration(manifest, jobs)
        if actual_config != json.loads(Path(packet["configurationAudit"]["path"]).read_text()):
            failures.append("Active configuration audit differs from actual compiler arguments")
        module_names = [item["module"] for item in packet["modules"]]
        if len(module_names) != len(set(module_names)) or set(module_names) != {job["module"] for job in jobs}:
            failures.append("Compiler module inventory differs from actual jobs")
        for module in packet["modules"]:
            matching = [job for job in jobs if job["module"] == module["module"]]
            if len(matching) != 1:
                failures.append("Compiler module has no unique actual job"); continue
            job = matching[0]
            if module["argumentsDigest"] != job["argumentsDigest"]: failures.append("Compiler module argument digest differs")
            if set(module["variants"]) != {"original","transformed"}: failures.append("Compiler variant inventory differs")
            for variant_name, variant in module["variants"].items():
                if variant_name not in {"original","transformed"}: continue
                directory = path.resolve().parent / module["module"] / variant_name
                overlay = Path(packet["manifest"]["path"]).parent / "overlay.json" if variant_name == "transformed" else None
                arguments, adjustments = cert.replay_arguments(job, directory, overlay)
                if variant["adjustments"] != adjustments: failures.append("Compiler action adjustments differ: " + module["module"])
                if set(variant["actions"]) != {"ast","sil"}: failures.append("Compiler action inventory differs: " + module["module"])
                for name, action in variant["actions"].items():
                    if name not in {"ast","sil"}: continue
                    trace = directory / "sil-loaded-modules.json"
                    flags = [] if name == "ast" else ["-emit-loaded-module-trace-path",str(trace),"-Xllvm","-sil-print-debuginfo"]
                    expected_argv = [str(cert.COMPILER),*arguments,*flags,"-dump-ast" if name == "ast" else "-emit-silgen"]
                    if action["argv"] != expected_argv: failures.append("Compiler action command differs: " + module["module"] + "/" + variant_name + "/" + name)
                    if action["cwd"] != job["cwd"]: failures.append("Compiler action cwd differs: " + module["module"])
                    if type(action["exit"]) is not int or action["exit"] != 0: failures.append("Compiler action failed: " + module["module"])
                    for key, filename in (("stdout",name+".txt"),("stderr",name+".stderr"),("index",name+"-index.json")):
                        if Path(action[key]["path"]).resolve() != directory / filename: failures.append("Compiler action output path differs: " + module["module"] + "/" + key)
                    if action["rawAnalysis"] not in [action["stdout"],action["stderr"]]: failures.append("Raw compiler analysis is not a captured output stream")
                    if name == "sil":
                        if Path(action["loadedModuleTrace"]["path"]).resolve() != trace: failures.append("Loaded module trace output differs")
                        module_trace = json.loads(trace.read_text())
                        if module_trace.get("name") != module["module"] or module_trace.get("arch") != job["target"].split("-")[0] or module_trace.get("languageMode") != job["languageMode"]:
                            failures.append("Loaded module trace configuration differs")
                        expected_inputs = [cert.artifact(Path(filename)) for filename in sorted(set(module_trace["swiftmodules"]))]
                        if action["loadedSwiftModuleInputs"] != expected_inputs: failures.append("Loaded Swift module input bindings differ from actual trace")
            if not recompute: continue
            original = module["variants"]["original"]["actions"]
            transformed = module["variants"]["transformed"]["actions"]
            original_ast = cert.typed_nodes(Path(original["ast"]["rawAnalysis"]["path"]).read_text())
            transformed_ast = cert.typed_nodes(Path(transformed["ast"]["rawAnalysis"]["path"]).read_text())
            original_sil = cert.sil_locations(Path(original["sil"]["rawAnalysis"]["path"]).read_text())
            transformed_sil = cert.sil_locations(Path(transformed["sil"]["rawAnalysis"]["path"]).read_text())
            for source in manifest["sources"]:
                if source["source"] not in job["sources"]: continue
                sites = cert.compare_typed_sites(source, original_ast, transformed_ast)
                cert.add_sil_comparisons(source, sites, original_sil, transformed_sil)
                for item in sites:
                    if item != by_site.get(item["site"]): failures.append("Concrete compiler comparison differs: " + item["site"])
    except (ValueError, OSError, KeyError, TypeError) as error:
        failures.append("Incomplete/malformed compiler evidence: " + str(error))
    review = cert.artifact(independent_review) if independent_review is not None else None
    return {"schemaVersion": 1, "evidence": cert.artifact(path), "auditor": cert.artifact(Path(__file__)),
            "artifactIntegrityPassed": not failures, "failures": failures, "verifiedArtifactCount": len(verified),
            "independentReview": review, "reviewMeaning": "Attachment records provenance only and grants no approval",
            "semanticEquivalenceCertified": False, "applicationGateApproved": False,
            "status": "integrity-audited-pending-independent-semantic-and-active-configuration-proof",
            "requiredNextEvidence": ["complete-concrete-typed-and-SIL-equivalence-proofs",
                                     "source-specific-invariant-and-ownership-proofs",
                                     "all-required-build-configurations-and-imported-dependencies",
                                     "independent-review-of-exact-evidence", "actual-bound-runtime-outcome-measurement"]}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--independent-review", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    report = audit(args.evidence, args.independent_review)
    cert.coverage.write(args.output, report)
    print(json.dumps(report, indent=2))
    return 0 if report["artifactIntegrityPassed"] else 1


if __name__ == "__main__": raise SystemExit(main())
