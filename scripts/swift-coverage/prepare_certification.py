#!/usr/bin/env python3
"""Freeze actual Swift job configuration and derive exact original-type hints.

This prepares test copies and compiler evidence only. It does not build/run an
app or grant semantic certification. All compiler actions use isolated caches.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("prepared_swift_certification", HERE / "certification.py")
cert = importlib.util.module_from_spec(spec); spec.loader.exec_module(cert)
spec = importlib.util.spec_from_file_location("prepared_chain_reads",HERE / "chain_reads.py")
chain_reads = importlib.util.module_from_spec(spec); spec.loader.exec_module(chain_reads)


def prepare(log: Path, output: Path) -> dict:
    output = output.resolve()
    if not output.is_relative_to(cert.coverage.ROOT / ".local-private"):
        raise ValueError("Preparation must remain in this project's private evidence directory")
    output.mkdir(parents=True, exist_ok=False)
    snapshot = output / "compile-log.txt"; snapshot.write_bytes(log.read_bytes())
    jobs = cert.read_jobs(snapshot, cert.coverage.ROOT / "ios", {"TranslateCore", "TranslatePDF"})
    version = subprocess.check_output([str(cert.COMPILER), "--version"], text=True)
    match = re.search(r"Apple Swift version (\d+)\.(\d+)(?:\.(\d+))?", version)
    if not match: raise ValueError("Unrecognized installed Swift compiler version")
    configs = {}
    owned = {str((cert.coverage.ROOT / value).resolve()) for value in cert.coverage.OWNED}
    for job in jobs:
        target = re.fullmatch(r"([^-]+)-apple-(ios)([0-9.]+)(?:-(simulator|macabi))?", job["target"])
        if not target: raise ValueError("Only the actual iOS application build configurations are supported")
        config = {"os": "iOS", "arch": target[1], "environment": target[4], "defines": job["swiftDefines"],
                  "languageVersion": [int(job["languageMode"])], "compilerVersion": [int(value or "0") for value in match.groups()]}
        for source in set(job["sources"]) & owned:
            if source in configs: raise ValueError("Owned file appears in multiple distinct actual modules")
            configs[source] = config
    if set(configs) != owned: raise ValueError("Actual compiler jobs omit owned production Swift files")
    initial = cert.coverage.prepare(output / "syntax-inventory", configurations_by_source=configs)
    configuration = cert.audit_configuration(initial, jobs)
    cert.coverage.write(output / "configuration-audit.json", configuration)
    if not configuration["passed"]: raise ValueError("Actual source configuration is unresolved: " + str(configuration["failures"]))
    observations = []
    for job in jobs:
        directory = output / (job["module"] + "-original"); directory.mkdir()
        arguments, changes = cert.replay_arguments(job, directory, None)
        invocation = cert.execute([*arguments, "-dump-ast"], Path(job["cwd"]), directory / "ast.stdout", directory / "ast.stderr")
        if invocation["exit"]: raise ValueError("Original typed compilation failed: " + str(directory))
        text, raw = cert.compiler_output(invocation, "ast")
        nodes = cert.typed_nodes(text); index = directory / "ast-index.json"; cert.coverage.write(index, nodes)
        observations.append({"module": job["module"], "invocation": invocation, "adjustments": changes,
                             "rawTypedAST": raw, "typedIndex": cert.artifact(index)})
        for source in initial["sources"]:
            if source["source"] not in job["sources"]: continue
            proof = cert.any_result_hints(source, nodes, index)
            if proof["typingHints"]:
                configs[source["source"]] = {**configs[source["source"]], "typingHints": proof["typingHints"], "typingHintsProof": proof}
            requests=source.get("chainReadRequests",[])
            reads=chain_reads.derive(Path(source["source"]),nodes,requests)
            if reads:
                configs[source["source"]]={**configs[source["source"]],"chainReadHints":reads,
                    "chainReadHintsProof":{"sourceSHA256":source["sourceSHA256"],"originalTypedAST":cert.artifact(index),"requests":requests}}
    for source in initial["sources"]:
        if cert.coverage.sha(Path(source["source"])) != source["sourceSHA256"]: raise ValueError("Original source changed during preparation")
    manifest = cert.coverage.prepare(output / "measurement", configurations_by_source=configs,
        runtime_injections={"TranslatePDF": cert.coverage.ROOT / "ios/TranslatePDF/AppDelegate.swift",
                            "TranslateCore": cert.coverage.ROOT / "ios/TranslatePDF/ModelCatalog.swift"})
    for source in manifest["sources"]:
        original = next(item for item in initial["sources"] if item["source"] == source["source"])
        if source["sourceSHA256"] != original["sourceSHA256"]: raise ValueError("Source changed before final prepared copy")
    result = {"schemaVersion": 1, "status": "prepared-from-actual-jobs-and-original-typing-not-certified",
              "manifest": cert.artifact(output / "measurement/manifest.json"), "preparer": cert.artifact(Path(__file__)),
              "compileLog": cert.artifact(snapshot), "originalTyping": observations,
              "candidateSites": len(manifest["registry"]), "applicationGateApproved": False}
    cert.coverage.write(output / "preparation.json", result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compile-log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = prepare(args.compile_log, args.output)
    print(json.dumps({key: result[key] for key in ("status", "manifest", "candidateSites", "applicationGateApproved")}, indent=2))
    return 0


if __name__ == "__main__": raise SystemExit(main())
