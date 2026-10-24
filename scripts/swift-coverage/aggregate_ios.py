#!/usr/bin/env python3
"""Combine intact hosted executions of one production measurement, never approve it."""
import argparse
import importlib.util
import json
from pathlib import Path

HERE=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location("aggregate_hosted_runner",HERE / "run_ios.py")
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)
coverage=runner.coverage


def aggregate(directories: list[Path]) -> dict:
    if not directories or len(set(p.resolve() for p in directories)) != len(directories):
        raise ValueError("Distinct completed hosted run directories are required")
    results=[];manifests={};images={};receipts=[];native=None;primary=None
    for directory in directories:
        directory=directory.resolve()
        def read(name): return json.loads((directory / name).read_text())
        provenance=read("provenance.json")
        for name,key in [("measurement/manifest.json","manifestSHA256"),("invocation.json","invocationSHA256"),
                         ("compile-attestation.json","compileAttestationSHA256"),
                         ("loaded-module-attestation.json","moduleAttestationSHA256"),("xcodebuild.log","xcodebuildLogSHA256")]:
            if coverage.sha(directory/name) != provenance[key]: raise ValueError("Hosted receipt drift: "+name)
        manifest=read("measurement/manifest.json");invocation=read("invocation.json")
        if invocation["runnerSHA256"]!=coverage.sha(HERE/"run_ios.py"):raise ValueError("Hosted runner drift")
        execution=manifest.get("execution")
        if not execution: raise ValueError("Missing export-driver execution identity")
        extras=manifest["extraOverlays"]
        if len(extras)!=1: raise ValueError("Expected only the audited export-driver overlay")
        driver=Path(extras[0]["source"]);copy=Path(extras[0]["transformed"])
        expected=runner.execution_identity(driver.read_text(),execution["filenames"],invocation["runID"])
        if execution != expected or coverage.sha(driver)!=invocation["driverOriginalSHA256"]:
            raise ValueError("Export-driver execution binding differs")
        if copy.read_text()!=runner.teardown_driver(driver.read_text(),execution["filenames"],invocation["runID"]) or coverage.sha(copy)!=invocation["driverTestCopySHA256"]:
            raise ValueError("Export driver is not the exact original tests plus audited teardown")
        actual=runner.compile_attestation(directory/"xcodebuild.log",manifest,invocation["sdk"])
        recorded=read("compile-attestation.json")
        if not actual["passed"] or recorded.get("xcodebuildExit")!=0 or any(recorded.get(k)!=v for k,v in actual.items()):
            raise ValueError("Actual hosted compile-job audit failed or changed")
        current_native=invocation["nativeBuildInputSHA256"]
        if native is None:native=current_native
        if current_native!=native:raise ValueError("Native build inputs differ between executions")
        for name,digest in current_native.items():
            if not Path(name).is_file() or coverage.sha(Path(name))!=digest:raise ValueError("Native build input drift: "+name)
        for image in read("loaded-module-attestation.json"):
            artifact=Path(image["artifact"])
            if coverage.sha(artifact)!=image["sha256"] or coverage.binary_uuid(artifact)!=image["uuid"]:
                raise ValueError("Loaded image snapshot differs")
            images[image["sha256"]]=artifact
        run_results=[]
        for name,digest in provenance["runtimeResultSHA256"].items():
            path=Path(name)
            if not path.is_relative_to(directory) or coverage.sha(path)!=digest:raise ValueError("Raw execution result drift")
            value=json.loads(path.read_text());identity=value["runID"]
            if identity in manifests:raise ValueError("Duplicate runtime run ID")
            if identity not in execution["runIDs"]:raise ValueError("Foreign runtime run ID")
            manifests[identity]=directory/"measurement/manifest.json";run_results.append(path)
        if len(run_results)!=len(runner.MODULE_FILES):raise ValueError("Incomplete hosted module exports")
        results+=run_results;primary=primary or directory/"measurement/manifest.json"
        receipts.append({"directory":str(directory),"provenanceSHA256":coverage.sha(directory/"provenance.json")})
    report=coverage.validate(primary,results,binary_artifacts=images,execution_manifests=manifests)
    return {"schemaVersion":1,"scope":"hosted-candidate-observations-not-original-coverage-approval",
            "applicationGateApproved":False,"semanticEquivalenceCertified":False,"runs":receipts,"report":report}


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run",type=Path,action="append",required=True)
    parser.add_argument("--output",type=Path,required=True)
    args=parser.parse_args();result=aggregate(args.run);coverage.write(args.output,result)
    print(json.dumps(result,indent=2))
