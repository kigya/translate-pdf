#!/usr/bin/env python3
"""Run existing production Swift host tests against original and VFS test copies.

Uses a fresh private output directory. Never changes production/test sources or
normalizes candidate branch hits into passing application coverage.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("swift_branch_tool", HERE / "coverage.py")
coverage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(coverage)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    output = (args.output or coverage.ROOT / ".local-private/swift-coverage-host" / str(uuid.uuid4())).resolve()
    output.mkdir(parents=True, exist_ok=False)
    environment = {**os.environ, "DEVELOPER_DIR": coverage.DEVELOPER}
    environment.pop("SDKROOT",None)
    sources = [coverage.ROOT / "ios/TranslatePDF" / f"{name}.swift" for name in (
        "ModelCatalog", "ModelDownloadCoordinator", "TranslatorStore")]
    driver_source = coverage.ROOT / "ios/tests/TranslatorStorePipelineTest.swift"
    original_driver_sha = coverage.sha(driver_source)
    driver = driver_source.read_text()
    match = re.search(r'^    print\("PASS \d+ TranslatorStore pipeline/catalog/language tests"\)$',driver,re.MULTILINE)
    if not match: raise ValueError("Existing host driver completion marker changed; review driver injection")
    for source in sources:
        if re.search(r"\bBRANCH_COVERAGE\b",source.read_text()): raise ValueError("Driver-only define now affects production configuration")
    driver = driver.replace(match.group(0),match.group(0)+'\n    #if BRANCH_COVERAGE\n    try __FBCov.export(to:CommandLine.arguments[1],runID:CommandLine.arguments[2])\n    #endif',1)
    (output / "Driver.swift").write_text(driver)
    target_info = json.loads(coverage.run(["xcrun","swiftc","-print-target-info"]))
    compiler = coverage.run(["xcrun","swiftc","--version"])
    version = re.search(r"Apple Swift version (\d+)\.(\d+)(?:\.(\d+))?",compiler)
    if not version: raise ValueError("Unknown Apple Swift version format")
    triple = target_info["target"]["triple"]
    if "macos" not in triple: raise ValueError("Host corpus requires the verified macOS target")
    config = {"os":"macOS","arch":triple.split("-")[0],"environment":None,
              "defines":["BRANCH_COVERAGE"],"languageVersion":[5],
              "compilerVersion":[int(value or "0") for value in version.groups()]}
    manifest = coverage.prepare(output / "instrumented",sources,config)
    commands = []
    observations = []
    run_id = str(uuid.uuid4())
    for transformed in [False,True]:
        name = "transformed" if transformed else "original"
        binary = output / f"{name}-run"
        command = ["xcrun","swiftc","-j","1","-swift-version","5","-Onone","-g","-target",triple,
                   "-module-cache-path",str(output / "host-module-cache"),
                   "-profile-generate","-profile-coverage-mapping"]
        if transformed: command += ["-D","BRANCH_COVERAGE","-vfsoverlay",str(output / "instrumented/overlay.json"),str(output / "instrumented/BranchRuntime.swift")]
        command += [str(path) for path in sources] + [str(output / "Driver.swift"),"-o",str(binary)]
        result = subprocess.run(command,env=environment,text=True,capture_output=True)
        (output / f"{name}-compile.log").write_text(result.stdout+result.stderr)
        commands.append({"argv":command,"exit":result.returncode})
        coverage.write(output / "commands.json",commands)
        if result.returncode: raise RuntimeError(f"{name} compilation failed; see {output}")
        run_command = [str(binary),str(output / "runtime-results.json"),run_id]
        result = subprocess.run(run_command,env={**environment,"LLVM_PROFILE_FILE":str(output / f"{name}.profraw")},text=True,capture_output=True)
        (output / f"{name}-run.log").write_text(result.stdout+result.stderr)
        commands.append({"argv":run_command,"exit":result.returncode})
        coverage.write(output / "commands.json",commands)
        if result.returncode: raise RuntimeError(f"{name} tests failed; see {output}")
        observations.append(result.stdout)
    if observations[0] != observations[1]: raise ValueError("Original/transformed observable test output differs")
    if coverage.sha(driver_source) != original_driver_sha: raise ValueError("Original driver changed during probe")
    for source in manifest["sources"]:
        if coverage.sha(Path(source["source"])) != source["sourceSHA256"]: raise ValueError("Production source changed during probe")
    merge = ["xcrun","llvm-profdata","merge","-sparse",str(output / "original.profraw"),"-o",str(output / "original.profdata")]
    coverage.run(merge); commands.append({"argv":merge,"exit":0})
    export = ["xcrun","llvm-cov","export",str(output / "original-run"),"-instr-profile="+str(output / "original.profdata")]
    (output / "original-llvm.json").write_text(coverage.run(export)); commands.append({"argv":export,"exit":0})
    coverage.write(output / "commands.json",commands)
    report = coverage.validate(output / "instrumented/manifest.json",[output / "runtime-results.json"])
    coverage.write(output / "gate.json",report)
    provenance = {"sourceManifestSHA256":coverage.sha(output / "instrumented/manifest.json"),
                  "driverOriginalSHA256":original_driver_sha,"driverTestCopySHA256":coverage.sha(output / "Driver.swift"),
                  "runnerSHA256":coverage.sha(Path(__file__)),"configuration":config,"targetInfo":target_info,
                  "commandsSHA256":coverage.sha(output / "commands.json"),"runID":run_id,
                  "runtimeResultsSHA256":coverage.sha(output / "runtime-results.json"),
                  "originalLLVMSHA256":coverage.sha(output / "original-llvm.json"),
                  "originalBinarySHA256":coverage.sha(output / "original-run"),
                  "transformedBinarySHA256":coverage.sha(output / "transformed-run"),
                  "observableOutputMatched":True}
    coverage.write(output / "provenance.json",provenance)
    print(output)
    print(json.dumps({key:value for key,value in report.items() if key != "missing"},indent=2))
    # Return probe success separately from the intentionally red application gate.
    return 0


if __name__ == "__main__": raise SystemExit(main())
