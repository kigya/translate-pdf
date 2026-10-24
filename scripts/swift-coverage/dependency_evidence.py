#!/usr/bin/env python3
"""Bind compiler dependency-scan inputs for original/transformed Swift jobs.

The scanner is the pinned compiler and uses the recorded module configuration.
Its graph is evidence for independent closure review, not app or hit provenance.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("dependency_certification", HERE / "certification.py")
cert = importlib.util.module_from_spec(spec); spec.loader.exec_module(cert)
audit_spec = importlib.util.spec_from_file_location("dependency_source_evidence_audit", HERE / "audit_certification.py")
auditor = importlib.util.module_from_spec(audit_spec); audit_spec.loader.exec_module(auditor)

LIST_INPUTS = {"sourceFiles", "fileDependencies", "bridgingSourceFiles"}
SCALAR_INPUTS = {"moduleMapPath", "swiftInterfacePath", "compiledModulePath", "bridgingHeader", "moduleInterfacePath", "libraryPath", "executablePath"}


def graph_inputs(graph: dict, cwd: Path, expected_module: str) -> dict:
    if graph.get("mainModuleName") != expected_module or not isinstance(graph.get("modules"), list) or not graph["modules"]:
        raise ValueError("Missing/mismatched actual compiler dependency graph")
    candidates = {}; declared_outputs = []; main_sources = []

    def add(value: object, kind: str) -> None:
        if not isinstance(value, str) or not value: raise ValueError("Malformed declared compiler dependency path")
        path = Path(value); path = (path if path.is_absolute() else cwd / path).resolve()
        candidates.setdefault(str(path), set()).add(kind)

    def walk(value: object, module_kind: str) -> None:
        if isinstance(value, dict):
            for key, child in value.items():
                if key in LIST_INPUTS:
                    if not isinstance(child, list): raise ValueError("Malformed declared input list")
                    for item in child: add(item, key)
                elif key in SCALAR_INPUTS and child is not None:
                    if key in {'libraryPath','executablePath'} and child=='':
                        other=value.get('executablePath' if key=='libraryPath' else 'libraryPath')
                        if not isinstance(value.get('moduleName'),str) or not value['moduleName'] or not isinstance(other,str) or not other:
                            raise ValueError('Empty macro path without an exact alternate executable/library')
                    else:add(child,key)
                elif key == "modulePath" and isinstance(child, str):
                    path = Path(child); path = (path if path.is_absolute() else cwd / path).resolve()
                    if module_kind == "swiftPrebuiltExternal": add(child, "prebuilt-modulePath")
                    elif path.is_file(): add(child, "existing-modulePath")
                    else: declared_outputs.append(str(path))
                walk(child, module_kind)
        elif isinstance(value, list):
            for child in value: walk(child, module_kind)
    modules = graph["modules"]
    if len(modules) % 2: raise ValueError("Malformed compiler module identifier/body pairs")
    for index in range(0,len(modules),2):
        identifier, body = modules[index:index+2]
        if not isinstance(identifier,dict) or len(identifier) != 1 or not isinstance(body,dict):
            raise ValueError("Malformed compiler module identifier/body pair")
        kind, name = next(iter(identifier.items()))
        if kind not in {"swift","clang","swiftPrebuiltExternal"} or not isinstance(name,str) or not name:
            raise ValueError("Unsupported compiler dependency module kind")
        if kind == "swiftPrebuiltExternal" and not isinstance(body.get("modulePath"),str):
            raise ValueError("Prebuilt external module has no observed module path")
        if kind == 'swift' and name == expected_module:
            if main_sources: raise ValueError("Duplicate main module dependency body")
            values=body.get('sourceFiles')
            if not isinstance(values,list) or not values:raise ValueError("Main module has no declared source inventory")
            for value in values:
                if not isinstance(value,str) or not value:raise ValueError("Malformed main module source")
                path=Path(value);main_sources.append(str((path if path.is_absolute() else cwd/path).resolve()))
        walk(body, kind)
    if not candidates: raise ValueError("Dependency graph contains no bound input files")
    inputs = []; missing = []
    for filename, kinds in sorted(candidates.items()):
        path = Path(filename)
        if not path.is_file(): missing.append({"path": filename, "inputKinds": sorted(kinds)})
        else: inputs.append({**cert.artifact(path), "inputKinds": sorted(kinds)})
    if not main_sources:raise ValueError("Missing main module dependency body")
    return {"mainModuleSources":sorted(main_sources),"inputs": inputs, "missingDeclaredInputs": missing, "plannedModuleOutputs": sorted(set(declared_outputs)),
            "meaning": "Declared input paths and existing module artifacts only; planned compiler outputs are never treated as observed inputs"}


def dependency_output(invocation: dict) -> tuple[dict,dict,int]:
    """The multi-primary driver can emit repeated identical JSON to stderr."""
    found=[];decoder=json.JSONDecoder()
    for stream in ['stdout','stderr']:
        path=Path(invocation[stream]['path']);raw=path.read_text();offset=0;graphs=[]
        if not raw.strip():continue
        while offset<len(raw):
            while offset<len(raw) and raw[offset].isspace():offset+=1
            if offset==len(raw):break
            graph,end=decoder.raw_decode(raw,offset)
            if not isinstance(graph,dict) or 'mainModuleName' not in graph:raise ValueError('Unknown dependency output object')
            graphs.append(graph);offset=end
        if not graphs or any(graph!=graphs[0] for graph in graphs):raise ValueError('Nonidentical repeated dependency graphs')
        found.append((graphs[0],cert.artifact(path),len(graphs)))
    if len(found)!=1:raise ValueError('Missing/ambiguous actual dependency graph stream')
    return found[0]


def collect(evidence_path: Path, output: Path) -> dict:
    output = output.resolve()
    if not output.is_relative_to(cert.coverage.ROOT / ".local-private"): raise ValueError("Dependency evidence must remain project local")
    output.mkdir(parents=True, exist_ok=False)
    integrity = auditor.audit(evidence_path, recompute=False)
    cert.coverage.write(output / "input-integrity-audit.json", integrity)
    if not integrity["artifactIntegrityPassed"]: raise ValueError("Typed/SIL input evidence is stale or inconsistent")
    packet = json.loads(evidence_path.read_text())
    manifest = json.loads(Path(packet["manifest"]["path"]).read_text())
    jobs = json.loads(Path(packet["jobs"]["path"]).read_text())
    if cert.coverage.sha(cert.COMPILER.resolve()) != packet["compiler"]["sha256"]: raise ValueError("Compiler changed since typed/SIL capture")
    for source in manifest["sources"]:
        if cert.coverage.sha(Path(source["source"])) != source["sourceSHA256"] or cert.coverage.sha(Path(source["transformed"])) != source["transformedSHA256"]:
            raise ValueError("Typed/SIL source snapshot changed")
    failures = []; records = []
    for job in jobs:
        for variant in ("original", "transformed"):
            directory = output / job["module"] / variant; directory.mkdir(parents=True)
            overlay = Path(packet["manifest"]["path"]).parent / "overlay.json" if variant == "transformed" else None
            arguments, changes = cert.replay_arguments(job, directory, overlay)
            invocation = cert.execute([*arguments, "-scan-dependencies"], Path(job["cwd"]), directory / "graph.stdout", directory / "graph.stderr")
            record = {"module": job["module"], "variant": variant, "invocation": invocation, "adjustments": changes}
            if invocation["exit"]:
                failures.append(f'{job["module"]}/{variant}: dependency scanner failed')
            else:
                graph,raw_graph,emissions=dependency_output(invocation)
                record['rawGraph']=raw_graph;record['identicalGraphEmissionCount']=emissions
                record["declaredInputs"] = graph_inputs(graph, Path(job["cwd"]), job["module"])
                if record["declaredInputs"]["missingDeclaredInputs"]: failures.append(f'{job["module"]}/{variant}: declared dependency inputs missing')
                declared_sources = set(record["declaredInputs"]["mainModuleSources"])
                accepted = set(job["sources"])
                if overlay is not None:
                    accepted |= {source["transformed"] for source in manifest["sources"] if source["source"] in job["sources"]}
                if not declared_sources or not declared_sources <= accepted or any(not ({source} | {item["transformed"] for item in manifest["sources"] if item["source"]==source}) & declared_sources for source in job["sources"]): failures.append(f'{job["module"]}/{variant}: scanner source inventory differs from actual job/VFS')
            records.append(record)
    for record in records:
        for item in record.get("declaredInputs", {}).get("inputs", []):
            if cert.coverage.sha(Path(item["path"])) != item["sha256"]: failures.append("Dependency input changed during scanning: " + item["path"])
    result = {"schemaVersion": 1, "typedSILEvidence": cert.artifact(evidence_path), "collector": cert.artifact(Path(__file__)),
              "compiler": packet["compiler"], "failures": failures, "jobs": records,
              "dependencyInputsBound": not failures, "dependencyClosureIndependentlyCertified": False,
              "applicationGateApproved": False, "status": "actual-scanner-input-evidence-pending-independent-closure-review"}
    cert.coverage.write(output / "dependency-evidence.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = collect(args.evidence, args.output)
    print(json.dumps({key: result[key] for key in ("status", "dependencyInputsBound", "failures", "applicationGateApproved")}, indent=2))
    return 0 if result["dependencyInputsBound"] else 1


if __name__ == "__main__": raise SystemExit(main())
