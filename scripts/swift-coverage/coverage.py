#!/usr/bin/env python3
"""Experimental Swift source-branch supplement. Does not alter the native gate.

`prepare` creates test copies + a VFS overlay, never edits original Swift files.
`validate` fails closed on unsupported syntax, missing outcomes or provenance drift.
Current output still needs independent typed-equivalence review before app use.
"""
from __future__ import annotations
import argparse
import copy
import re
import hashlib
import json
import os
from pathlib import Path
import subprocess
import struct
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
OWNED = tuple(f"ios/TranslatePDF/{name}.swift" for name in (
    "AppDelegate", "SceneDelegate", "ModelCatalog", "ModelDownloadCoordinator",
    "PdfTranslatorModule", "TranslatorStore"))
DEVELOPER = "/Applications/Xcode.app/Contents/Developer"


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def binary_uuid(path: Path) -> str:
    raw = path.read_bytes()
    if len(raw) < 32 or struct.unpack_from("<I", raw)[0] != 0xfeedfacf:
        raise ValueError("Expected thin Mach-O 64-bit binary")
    offset = 32
    for _ in range(struct.unpack_from("<I", raw, 16)[0]):
        if offset + 8 > len(raw): raise ValueError("Truncated Mach-O load commands")
        command, size = struct.unpack_from("<II", raw, offset)
        if size < 8 or offset + size > len(raw): raise ValueError("Invalid Mach-O command size")
        if command == 0x1b and size == 24: return raw[offset+8:offset+24].hex()
        offset += size
    raise ValueError("Mach-O LC_UUID missing")


def digest(value: object) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def write(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


def run(command: list[str]) -> str:
    env = {**os.environ, "DEVELOPER_DIR": DEVELOPER}
    return subprocess.run(command, env=env, check=True, text=True, capture_output=True).stdout


def build(output: Path) -> Path:
    output.mkdir(parents=True, exist_ok=True)
    host = Path(DEVELOPER) / "Toolchains/XcodeDefault.xctoolchain/usr/lib/swift/host"
    binary = output / "swift-branch-instrument"
    command = ["xcrun", "swiftc", "-swift-version", "5", "-I", str(host), "-L", str(host),
               "-Xlinker", "-rpath", "-Xlinker", str(host), str(HERE / "Instrument.swift"), "-o", str(binary)]
    run(command)
    write(output / "compiler.json", {"command": command, "version": run(["xcrun", "swiftc", "--version"]),
          "toolSourceSHA256": sha(HERE / "Instrument.swift"), "toolBinarySHA256": sha(binary)})
    return binary


def render_runtime(registry: dict, registry_digest: str, module: str = "") -> str:
    literal = "[" + ",".join(json.dumps(key)+":"+json.dumps(value) for key,value in sorted(registry.items())) + "]" if registry else "[:]"
    return (HERE / "Runtime.swift.in").read_text().replace("@@REGISTRY@@",literal).replace("@@DIGEST@@",registry_digest).replace("@@MODULE@@",module)


def overlay_value(sources: list[dict], extra: list[dict] | None = None) -> dict:
    return {"version":0,"case-sensitive":True,"roots":[{"type":"file","name":source["source"],"external-contents":source["transformed"]} for source in sources + (extra or [])]}


def registry_payload(manifest: dict) -> dict:
    # Injected runtime contains this digest: normalize its derived final SHA to
    # the already-bound transformed-body SHA, avoiding a circular hash contract.
    payload = copy.deepcopy({key:value for key,value in manifest.items() if key != "registryDigest"})
    for source in payload["sources"]:
        source["transformedSHA256"] = source["transformedBodySHA256"]
    return payload


def runtime_suffix(registry: dict, registry_digest: str, module: str) -> str:
    return "\n\n// BEGIN TEST-ONLY SWIFT BRANCH RUNTIME\n" + render_runtime(registry,registry_digest,module) + "\n// END TEST-ONLY SWIFT BRANCH RUNTIME\n"


def prepare(output: Path, files: list[Path] | None = None, configuration: dict | None = None, runtime_injections: dict[str,Path] | None = None, extra_overlays: dict[Path,Path] | None = None) -> dict:
    output = output.resolve(); binary = build(output)
    if files is None:
        discovered = {p.relative_to(ROOT).as_posix() for p in (ROOT / "ios/TranslatePDF").rglob("*.swift")}
        if discovered != set(OWNED):
            raise ValueError(f"Owned Swift inventory drift: missing={discovered-set(OWNED)}, stale={set(OWNED)-discovered}")
    sources = [p.resolve() for p in (files or [ROOT / f for f in OWNED])]
    if len(sources) != len(set(sources)):
        raise ValueError("Duplicate source in inventory")
    injections = {}
    for module,path in (runtime_injections or {}).items():
        if not re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*",module): raise ValueError("Invalid injected Swift module identifier")
        resolved = path.resolve()
        if resolved not in sources or resolved in injections: raise ValueError("Runtime must be injected once per module into a distinct prepared source")
        injections[resolved] = module
    extra_records = []
    for original,test_copy in (extra_overlays or {}).items():
        original = original.resolve(); test_copy = test_copy.resolve()
        if original in sources or original == test_copy: raise ValueError("Extra overlay must be a distinct non-production test input")
        extra_records.append({"source":str(original),"sourceSHA256":sha(original),"transformed":str(test_copy),"transformedSHA256":sha(test_copy)})
    records = []; registry = {}; roots = []
    config_path = output / "configuration.json"
    if configuration is not None:
        if not isinstance(configuration,dict): raise ValueError("Configuration must be an object")
        write(config_path,configuration)
    for index, source in enumerate(sources):
        namespace = "s" + hashlib.sha256(str(source).encode()).hexdigest()[:16]
        transformed = output / f"{index}-{source.name}"
        ast = output / f"{index}-ast.json"
        original_sha = sha(source)
        run([str(binary), str(source), str(transformed), str(ast), namespace, str(config_path) if configuration is not None else "-"])
        if sha(source) != original_sha: raise ValueError(f"Source changed during instrumentation: {source}")
        parsed = json.loads(ast.read_text())
        original_bytes = source.read_bytes()
        for node in parsed["sites"] + parsed["unsupported"] + parsed["reviewRequired"] + parsed["structural"]:
            offset = node["startUTF8"]
            node["line"] = original_bytes[:offset].count(b"\n") + 1
            node["columnUTF8"] = offset - original_bytes.rfind(b"\n",0,offset)
        classification = []
        for node in parsed["unsupported"] + parsed["reviewRequired"] + parsed["structural"]:
            if node["kind"] in {"forceUnwrapExpr","asExpr","tryExpr","token","optionalChainingExpr","condition"}:
                item = {**node,"source":str(source),"sourceSHA256":original_sha,
                        "sourceText":original_bytes[node["startUTF8"]:node["endUTF8"]].decode(),
                        "reviewStatus":"pending","classification":(
                            "structural-constant-loop" if node in parsed["structural"] else
                            "ownership-context" if node["kind"] == "token" and original_bytes[node["startUTF8"]:node["endUTF8"]] == b"weak" else
                            "intrinsic-precondition-candidate" if node["kind"] in {"forceUnwrapExpr","asExpr","tryExpr"} else
                            "owned-source-decision-unresolved")}
                item["classificationID"] = digest({"sourceSHA256":original_sha,"kind":node["kind"],"start":node["startUTF8"],"end":node["endUTF8"]})
                classification.append(item)
        for node in parsed["sites"]:
            expression = original_bytes[node["startUTF8"]:node["endUTF8"]].decode()
            if node["kind"] in {"condition","logical-left","logical-right","ternary-condition"} and expression in {"true","false"}:
                item = {**node,"source":str(source),"sourceSHA256":original_sha,"sourceText":expression,
                        "reviewStatus":"pending","classification":None,"reason":"Literal Bool has no runtime-varying value; independent denominator classification required"}
                item["classificationID"] = digest({"sourceSHA256":original_sha,"kind":node["kind"],"start":node["startUTF8"],"end":node["endUTF8"]})
                classification.append(item)
        parsed["classificationInventory"] = classification
        write(ast,parsed)
        for site in parsed["sites"]:
            registry[f'{namespace}:{site["id"]}'] = site["outcomes"]
        records.append({"source": str(source), "sourceSHA256": original_sha,
                        "transformed": str(transformed), "transformedSHA256": sha(transformed),
                        "transformedBodySHA256":sha(transformed),"runtimeInjectedModule":injections.get(source),
                        "namespace": namespace, "ast": str(ast), "astSHA256": sha(ast), **parsed})
        roots.append({"type": "file", "name": str(source), "external-contents": str(transformed)})
    payload = {"schemaVersion": 1, "sources": records, "registry": registry,
               "extraOverlays":extra_records,
               "compiler": json.loads((output / "compiler.json").read_text()),
               "toolDriverSHA256": sha(Path(__file__).resolve()),
               "runtimeTemplateSHA256": sha(HERE / "Runtime.swift.in"),
               "configuration": configuration,
               "semanticStatus": "requires-independent-typed-equivalence-review",
               "configurationStatus": "unresolved-if-conditional-compilation-present",
               "limitations": ["Candidate AST sites include inactive configurations until independently resolved",
                               "Compiler acceptance alone does not establish overload/ownership equivalence",
                               "Does not replace original LLVM lines/functions/regions",
                               "Application binary UUID / compile job attestation must be supplied by integration"]}
    payload["registryDigest"] = digest(registry_payload(payload))
    for source in records:
        if source["runtimeInjectedModule"]:
            transformed = Path(source["transformed"])
            transformed.write_text(transformed.read_text()+runtime_suffix(registry,payload["registryDigest"],source["runtimeInjectedModule"]))
            source["transformedSHA256"] = sha(transformed)
    (output / "BranchRuntime.swift").write_text(render_runtime(registry,payload["registryDigest"]))
    write(output / "overlay.json",overlay_value(records,extra_records))
    write(output / "manifest.json", payload)
    write(output / "classification-inventory.json", {"registryDigest":payload["registryDigest"],
          "sites":[item for record in records for item in record["classificationInventory"]],
          "status":"pending-independent-review","rule":"No hits or blanket N/A are generated from classifications"})
    return payload


def validate(manifest_path: Path, result_paths: list[Path], fixture_only: bool = False, binary_artifacts: dict[str,Path] | None = None) -> dict:
    manifest = json.loads(manifest_path.read_text()); failures = []
    expected_digest = manifest.get("registryDigest")
    unsigned = registry_payload(manifest)
    if expected_digest != digest(unsigned): failures.append("Manifest digest mismatch")
    if manifest.get("toolDriverSHA256") != sha(Path(__file__).resolve()): failures.append("Tool driver drift")
    if manifest.get("runtimeTemplateSHA256") != sha(HERE / "Runtime.swift.in"): failures.append("Runtime template drift")
    if manifest["compiler"].get("toolSourceSHA256") != sha(HERE / "Instrument.swift"): failures.append("Instrumenter source drift")
    instrumenter = Path(manifest["compiler"]["command"][-1])
    if not instrumenter.is_file() or sha(instrumenter) != manifest["compiler"].get("toolBinarySHA256"): failures.append("Instrumenter binary drift")
    artifact_root = instrumenter.parent
    runtime_file = artifact_root / "BranchRuntime.swift"
    if not runtime_file.is_file() or runtime_file.read_text() != render_runtime(manifest["registry"],expected_digest): failures.append("Generated runtime drift")
    overlay_file = artifact_root / "overlay.json"
    expected_overlay = json.dumps(overlay_value(manifest["sources"],manifest["extraOverlays"]),indent=2,sort_keys=True)+"\n"
    if not overlay_file.is_file() or overlay_file.read_text() != expected_overlay: failures.append("VFS overlay drift")
    if not fixture_only: failures.append("Independent typed-equivalence / active-target / compile-job attestation is not implemented; app gate remains blocked")
    expected = manifest["registry"]
    if not expected: failures.append("Empty registry is not 100% coverage")
    totals = {site: {outcome: 0 for outcome in outcomes} for site, outcomes in expected.items()}
    for source in manifest["sources"]:
        for file_key, hash_key in [("source", "sourceSHA256"), ("transformed", "transformedSHA256"), ("ast", "astSHA256")]:
            path = Path(source[file_key])
            if not path.is_file() or sha(path) != source[hash_key]: failures.append(f"Source/artifact drift: {path}")
        transformed = Path(source["transformed"])
        if transformed.is_file():
            contents = transformed.read_text()
            if source["runtimeInjectedModule"]:
                suffix = runtime_suffix(expected,expected_digest,source["runtimeInjectedModule"])
                if not contents.endswith(suffix): failures.append(f"Injected runtime drift: {transformed}")
                else: contents = contents[:-len(suffix)]
            if hashlib.sha256(contents.encode()).hexdigest() != source["transformedBodySHA256"]: failures.append(f"Transformed body drift: {transformed}")
        if source["unsupported"]: failures.append(f'{source["source"]}: {len(source["unsupported"])} unsupported source constructs')
        if source["reviewRequired"]: failures.append(f'{source["source"]}: {len(source["reviewRequired"])} pending invariant/ownership classifications')
    for source in manifest["extraOverlays"]:
        for key,hash_key in [("source","sourceSHA256"),("transformed","transformedSHA256")]:
            path = Path(source[key])
            if not path.is_file() or sha(path) != source[hash_key]: failures.append(f"Extra test overlay drift: {path}")
    seen = set()
    if not result_paths: failures.append("No runtime results")
    for path in result_paths:
        result = json.loads(path.read_text())
        if result.get("registryDigest") != expected_digest: failures.append(f"Registry mismatch: {path}")
        if result.get("invalid") is not False: failures.append(f"Recorder error: {path}")
        if not isinstance(result.get("runtimeOS"),str) or not result["runtimeOS"]: failures.append(f"Missing actual runtime OS: {path}")
        identity = result.get("runID")
        if not identity or identity in seen: failures.append(f"Missing/duplicate run identity: {path}")
        seen.add(identity)
        recorded_binary = result.get("binaryPath", "")
        binary = Path((binary_artifacts or {}).get(recorded_binary,recorded_binary))
        if result.get("binaryRole") != "loaded-instrumented-image" or not result.get("recorderModule"): failures.append(f"Missing loaded-module provenance: {path}")
        if not binary.is_file() or sha(binary) != result.get("binarySHA256"): failures.append(f"Binary drift: {path}")
        if binary.is_file():
            try:
                if binary_uuid(binary) != result.get("binaryUUID"): failures.append(f"Binary UUID mismatch: {path}")
            except ValueError as error: failures.append(str(error))
        hits = result.get("hits", {})
        if set(hits) != set(expected): failures.append(f"Registry site mismatch: {path}"); continue
        for site, outcomes in totals.items():
            if set(hits[site]) != set(outcomes): failures.append(f"Outcome mismatch: {site}"); continue
            for outcome in outcomes:
                value = hits[site][outcome]
                if type(value) is not int or value < 0: failures.append(f"Invalid count: {site}/{outcome}")
                else: totals[site][outcome] += value
    missing = [f"{site}/{outcome}" for site, outcomes in totals.items() for outcome, hits in outcomes.items() if not hits]
    if missing: failures.append(f"{len(missing)} uncovered outcomes")
    report = {"passed": not failures, "scope": "fixture-only" if fixture_only else "application",
              "failures": failures, "missing": missing, "registryDigest": expected_digest}
    covered = sum(v > 0 for o in totals.values() for v in o.values())
    count = sum(len(o) for o in totals.values())
    if fixture_only: report.update(covered=covered, count=count)
    else: report.update(coveredCandidateOutcomes=covered, candidateOutcomeCount=count, sourceBranchDenominator=None)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("prepare"); p.add_argument("--output", type=Path, required=True); p.add_argument("files", type=Path, nargs="*"); p.add_argument("--configuration", type=Path); p.add_argument("--inject-runtime",action="append",default=[],metavar="MODULE=SOURCE")
    p = sub.add_parser("validate"); p.add_argument("--manifest", type=Path, required=True); p.add_argument("--output", type=Path, required=True); p.add_argument("results", type=Path, nargs="*")
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            injections = {}
            for item in args.inject_runtime:
                module,source = item.split("=",1)
                if module in injections: raise ValueError("Duplicate runtime module")
                injections[module] = Path(source)
            manifest = prepare(args.output, args.files or None, json.loads(args.configuration.read_text()) if args.configuration else None,injections)
            print(f'Prepared {len(manifest["sources"])} sources, {len(manifest["registry"])} candidate sites; no acceptance claimed')
            return 0
        report = validate(args.manifest, args.results); write(args.output, report); print(json.dumps(report, indent=2)); return 0 if report["passed"] else 1
    except (OSError, ValueError, subprocess.CalledProcessError, KeyError) as error:
        print(f"Swift branch tool failed: {error}", file=sys.stderr)
        if isinstance(error, subprocess.CalledProcessError): print(error.stderr, file=sys.stderr)
        return 2

if __name__ == "__main__": raise SystemExit(main())
