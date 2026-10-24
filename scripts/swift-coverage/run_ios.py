#!/usr/bin/env python3
"""Isolated test-only VFS branch probes in both real hosted iOS Swift modules.

No project/source mutation, simulator boot/reset or package install occurs. The
existing booted simulator must be explicitly selected after coordinating its use.
Actual source equivalence/invariant/complete coverage gates remain independent.
"""
from __future__ import annotations
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import time
import uuid

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("hosted_swift_coverage",HERE / "coverage.py")
coverage = importlib.util.module_from_spec(spec);spec.loader.exec_module(coverage)
MODULE_FILES = {
    "TranslatePDF":{"AppDelegate.swift","SceneDelegate.swift"},
    "TranslateCore":{"ModelCatalog.swift","ModelDownloadCoordinator.swift","PdfTranslatorModule.swift","TranslatorStore.swift","DocxPreviewRenderer.swift","MyMemoryTranslator.swift"},
}
# Finite, reviewed original-driver identity. These seven methods genuinely require
# iOS 26 continued-processing APIs; any source edit needs a new review.
AVAILABILITY_REVIEWED_DRIVER_SHA256 = "6e72290745b5de587a55f58393a5cbc356db79e141b808475720ed0827e6bd58"
AVAILABILITY_REVIEWED_TESTS = (
    "testContinuedProcessingOwnerMovesToRecreatedModuleWithoutRetainingOldOwner",
    "testContinuedProcessingSubmitFailureDoesNotClaimBackgroundOwnership",
    "testContinuedProcessingCallbackOnlyAttachesToAnAlreadyOwnedActiveRun",
    "testContinuedProcessingAdmissionExpirationContractRejectsStaleGenerationAfterPauseResume",
    "testQueuedPauseAndCancelAreAcknowledgedBeforeWorkerReturnsWithoutLeakingOwner",
    "testFailedStopPersistenceRetriesThroughWorkerStopGateWithoutRestartingInference",
    "testQueuedStopRetryCannotEnterSameJobsNewRunAfterItsTokenIsInvalidated",
)

def unavailable_test_arguments(runtime_id: str, original_driver: str, skip_continued: bool) -> list[str]:
    """Omit only the seven reviewed iOS-26-only tests on an older identified runtime."""
    if not skip_continued:
        return []
    runtime = re.fullmatch(r"com\.apple\.CoreSimulator\.SimRuntime\.iOS-(\d+)(?:-\d+)*", runtime_id)
    if runtime is None or int(runtime.group(1)) >= 26:
        raise ValueError("The continued-processing test may be omitted only on an identified pre-iOS-26 runtime")
    if hashlib.sha256(original_driver.encode()).hexdigest() != AVAILABILITY_REVIEWED_DRIVER_SHA256:
        raise ValueError("Unavailable-test selection requires the exact independently reviewed original driver")
    for name in AVAILABILITY_REVIEWED_TESTS:
        if not re.search(r"@available\(iOS\s+26\.0\s*,\s*\*\)\s+func\s+" + name + r"\(", original_driver):
            raise ValueError("The original continued-processing test must retain its iOS 26 availability annotation")
    return ["-skip-testing:TranslatePDFNativeCoverageTests/NativeCoverageTests/" + name
            for name in AVAILABILITY_REVIEWED_TESTS]


def execute(command: list[str], log: Path | None = None) -> subprocess.CompletedProcess:
    environment = {**os.environ,"DEVELOPER_DIR":coverage.DEVELOPER,
                   "RCT_NO_LAUNCH_PACKAGER":"1","NODE_BINARY":"/opt/homebrew/opt/node@22/bin/node",
                   "PATH":"/opt/homebrew/opt/node@22/bin:"+os.environ.get("PATH","")}
    if log is not None:
        with log.open("w") as output:
            result = subprocess.run(command,env=environment,text=True,stdout=output,stderr=subprocess.STDOUT)
        return result
    return subprocess.run(command,env=environment,text=True,capture_output=True)


def checked(command: list[str]) -> str:
    result = execute(command)
    if result.returncode: raise RuntimeError(f"Command failed: {command}: {result.stderr}")
    return result.stdout


def execution_identity(original: str, filenames: dict[str,str], run_id: str) -> dict:
    payload = {"driverOriginalSHA256":hashlib.sha256(original.encode()).hexdigest(), "filenames":filenames,
               "runIDs":[run_id+":"+module for module in filenames]}
    return {**payload,"digest":coverage.digest(payload)}


def teardown_driver(original: str, filenames: dict[str,str], run_id: str) -> str:
    if "__FBCov" in original: raise ValueError("Original tests must not call recorder/probe helpers")
    if re.search(r"class\s+func\s+tearDown",original): raise ValueError("Existing class teardown requires reviewed composition")
    marker = "final class NativeCoverageTests: XCTestCase {"
    if original.count(marker) != 1: raise ValueError("Native test class declaration changed")
    calls = []
    execution = execution_identity(original, filenames, run_id)
    for module,filename in filenames.items():
        calls.append(f'''      try {module}.__FBCov.export(to:directory.appendingPathComponent({json.dumps(filename)}).path,runID:{json.dumps(run_id+':'+module)},executionDigest:{json.dumps(execution['digest'])})''')
    method = '''
  override class func tearDown() {
    defer { super.tearDown() }
    do {
      let directory = FileManager.default.temporaryDirectory
''' + "\n".join(calls) + '''
    } catch {
      XCTFail("Source-branch recorder export failed: \\(error)")
    }
  }
'''
    transformed = original.replace(marker,marker+method,1)
    helpers = re.findall(r"__FBCov\.([A-Za-z_0-9]+)",transformed)
    if helpers != ["export"]*len(filenames): raise ValueError("Test driver may call only export, never manufacture hits")
    return transformed


def expanded_arguments(tokens: list[str], cwd: Path, responses: dict[str,str], depth: int = 0) -> list[str]:
    if depth > 5: raise ValueError("Nested compiler response limit")
    result = []
    for token in tokens:
        if token.startswith("@"):
            path = Path(token[1:]);path = path if path.is_absolute() else cwd / path
            if path.is_file():
                responses[str(path.resolve())] = coverage.sha(path)
                result.extend(expanded_arguments(shlex.split(path.read_text()),cwd,responses,depth+1));continue
        result.append(token)
    return result


def option(tokens: list[str], key: str) -> str | None:
    for index,token in enumerate(tokens[:-1]):
        if token == key: return tokens[index+1]
    return None


def swift_definitions(tokens: list[str]) -> list[str]:
    result = []; index = 0
    while index < len(tokens):
        token = tokens[index]
        if token in {"-Xcc", "-Xfrontend", "-Xlinker", "-Xllvm"}:
            if index + 1 >= len(tokens): raise ValueError("Truncated compiler forwarding option")
            index += 2; continue
        if token == "-D":
            if index + 1 >= len(tokens): raise ValueError("Truncated Swift definition")
            result.append(tokens[index + 1]); index += 2; continue
        if token.startswith("-D") and len(token) > 2: result.append(token[2:])
        index += 1
    return sorted(set(result))


def prepared_configurations(path: Path, compiler_version: str) -> dict[str,dict]:
    """Reuse original-typing preparation, never a self-declared certificate."""
    manifest = json.loads(path.read_text())
    if manifest.get("registryDigest") != coverage.digest(coverage.registry_payload(manifest)):
        raise ValueError("Prepared configuration manifest digest differs")
    if manifest["compiler"]["version"] != compiler_version:
        raise ValueError("Prepared configuration compiler differs")
    expected = {str((coverage.ROOT / name).resolve()) for name in coverage.OWNED}
    if {source["source"] for source in manifest["sources"]} != expected:
        raise ValueError("Prepared configuration owned source inventory differs")
    result = {}
    for source in manifest["sources"]:
        if coverage.sha(Path(source["source"])) != source["sourceSHA256"]:
            raise ValueError("Prepared original typing is stale")
        config = source.get("configuration") or {}
        if any(config.get(key) != value for key,value in {"os":"iOS","arch":"arm64","environment":"simulator","languageVersion":[5]}.items()):
            raise ValueError("Hosted Debug configuration does not match original typing preparation")
        if "DEBUG" not in config.get("defines",[]): raise ValueError("Hosted Debug preparation has no Swift DEBUG definition")
        result[source["source"]] = config
    return result


def compile_attestation(log: Path, manifest: dict, sdk: str) -> dict:
    observed = {};jobs = [];missing = [];overlay = str(Path(manifest["compiler"]["command"][-1]).parent / "overlay.json")
    for line in log.read_text(errors="replace").splitlines():
        if "-module-name" not in line or not ("swiftc" in line or "swift-frontend" in line): continue
        try: tokens = shlex.split(line.strip())
        except ValueError: continue
        responses = {};tokens = expanded_arguments(tokens,coverage.ROOT / "ios",responses)
        module = option(tokens,"-module-name")
        if module not in {*MODULE_FILES,"TranslatePDFNativeCoverageTests"}: continue
        sources = {str(Path(token).resolve()) for token in tokens if token.endswith(".swift") and Path(token).is_file()}
        target = option(tokens,"-target");actual_sdk = option(tokens,"-sdk");language = option(tokens,"-swift-version")
        overlays = [tokens[index+1] for index,token in enumerate(tokens[:-1]) if token == "-vfsoverlay"]
        defines = swift_definitions(tokens)
        job = {"module":module,"target":target,"sdk":actual_sdk,"languageVersion":language,
               "defines":sorted(set(defines)),"overlays":overlays,"sources":sorted(sources),
               "responseFiles":responses,"command":line.strip(),"commandSHA256":hashlib.sha256(line.encode()).hexdigest()}
        if target and actual_sdk and language:
            jobs.append(job)
            for source in manifest["sources"]:
                if source["source"] in sources and Path(source["source"]).name in MODULE_FILES.get(module,set()):
                    if overlay not in overlays: missing.append(f"VFS not present for {source['source']}")
                    if not target.startswith("arm64-apple-ios") or not target.endswith("-simulator"): missing.append(f"Unexpected target: {target}")
                    if Path(actual_sdk).resolve() != Path(sdk).resolve(): missing.append(f"Unexpected SDK: {actual_sdk}")
                    if language != "5": missing.append(f"Unexpected language mode: {language}")
                    if module == "TranslatePDF" and "DEBUG" not in defines: missing.append("App DEBUG configuration missing")
                    source_configuration = source.get("configuration",manifest.get("configuration"))
                    if source_configuration is not None and sorted(set(source_configuration["defines"])) != defines:
                        missing.append(f"Prepared/actual Swift definitions differ for {source['source']}")
                    observed[source["source"]] = {"module":module,"originalSHA256":source["sourceSHA256"],"transformedSHA256":source["transformedSHA256"],"commandSHA256":job["commandSHA256"]}
    required = {source["source"] for source in manifest["sources"]}
    if set(observed) != required: missing.append("Missing actual owned-source compile jobs: "+str(sorted(required-set(observed))))
    tests = [job for job in jobs if job["module"] == "TranslatePDFNativeCoverageTests" and overlay in job["overlays"]]
    if not tests: missing.append("No actual VFS XCTest driver compile job")
    return {"passed":not missing,"failures":missing,"ownedInputs":observed,"compileJobs":jobs,
            "configurationScope":"Actual target/SDK/defines recorded; typed/ARC/invariant equivalence remains independent"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--simulator-id",required=True)
    parser.add_argument("--output",type=Path)
    parser.add_argument("--jobs",type=int,choices=[1,2],default=1)
    parser.add_argument("--derived-data-path",type=Path,help="Reuse an explicitly selected local Xcode build cache; all source and loaded-image attestations remain required")
    parser.add_argument("--stream-safe-clang",action="store_true",help="Use the project-local, real-output-preserving Clang discovery wrapper for this invocation only")
    parser.add_argument("--prepare-only",action="store_true")
    parser.add_argument("--configuration-manifest",type=Path,help="Use exact per-source configurations and original-AST type hints from prepare_certification.py; grants no coverage approval")
    parser.add_argument("--skip-ios26-continued-processing-test",action="store_true",help="Omit only the seven reviewed iOS-26-only tests on a pre-iOS-26 simulator; runtime, driver hash and availability annotations are verified")
    args = parser.parse_args()
    root = coverage.ROOT;output = (args.output or root / ".local-private/swift-ios" / str(uuid.uuid4())).resolve()
    output.mkdir(parents=True,exist_ok=False)
    inventory = json.loads(checked(["xcrun","simctl","list","devices","booted","--json"]))
    devices = [(runtime,device) for runtime,group in inventory["devices"].items() for device in group if device["udid"] == args.simulator_id and device["state"] == "Booted"]
    if len(devices) != 1: raise ValueError("Select exactly the existing approved booted simulator; no boot/reset is performed")
    run_id = str(uuid.uuid4());filenames = {module:f"swift-branches-{run_id}-{module}.json" for module in MODULE_FILES}
    driver_source = root / "ios/tests/NativeCoverageTests.swift"
    driver_original = driver_source.read_text()
    driver_original_sha = hashlib.sha256(driver_original.encode()).hexdigest()
    runtime_id = devices[0][0]
    test_selection = unavailable_test_arguments(runtime_id,driver_original,args.skip_ios26_continued_processing_test)
    native_inputs = [root / "ios/TranslatePDF/TranslateCoreBridge.mm",root / "ios/TranslatePDF/TranslateCoreBridge.h",
                     root / "native/core.hpp", root / "native/docx.hpp",
                     root / "ios/Frameworks/TranslateCore.xcframework/ios-arm64-simulator/TranslateCore.framework/TranslateCore",
                     root / "ios/Frameworks/PDFium.xcframework/ios-arm64-simulator/PDFium.framework/PDFium"]
    native_inputs += sorted({path for framework in (root / "ios/Frameworks").glob("*.xcframework")
                             for path in framework.rglob("*") if path.is_file() and (path.suffix in {".h",".hpp",".modulemap"} or path.name == "module.modulemap")})
    if args.stream_safe_clang:
        native_inputs += [root / "ios/scripts/clang-stream-safe.py",root / "ios/scripts/clang++-stream-safe.py"]
    native_input_hashes = {str(path):coverage.sha(path) for path in native_inputs}
    test_copy = output / "NativeCoverageTests.test.swift"
    test_copy.write_text(teardown_driver(driver_original,filenames,run_id))
    version = checked(["xcrun","swiftc","--version"])
    match = re.search(r"Apple Swift version (\d+)\.(\d+)(?:\.(\d+))?",version)
    if not match: raise ValueError("Unrecognized compiler version")
    config = {"os":"iOS","arch":"arm64","environment":"simulator","defines":["DEBUG"],
              "languageVersion":[5],"compilerVersion":[int(value or "0") for value in match.groups()]}
    injections = {"TranslatePDF":root / "ios/TranslatePDF/AppDelegate.swift","TranslateCore":root / "ios/TranslatePDF/ModelCatalog.swift"}
    source_configurations = prepared_configurations(args.configuration_manifest,version) if args.configuration_manifest else None
    execution = execution_identity(driver_original,filenames,run_id)
    manifest = coverage.prepare(output / "measurement",configuration=config,runtime_injections=injections,extra_overlays={driver_source:test_copy},configurations_by_source=source_configurations,execution=execution)
    if coverage.sha(driver_source) != driver_original_sha:
        raise ValueError("Original test driver changed during unavailable-test selection or overlay preparation")
    sdk = checked(["xcrun","--sdk","iphonesimulator","--show-sdk-path"]).strip()
    overlay = output / "measurement/overlay.json"
    command = ["xcodebuild","-workspace",str(root / "ios/TranslatePDF.xcworkspace"),"-scheme","TranslatePDF","-configuration","Debug",
               "-destination",f"id={args.simulator_id}","-derivedDataPath",str((args.derived_data_path or output / "derived-data").resolve()),"-resultBundlePath",str(output / "tests.xcresult"),
               "-jobs",str(args.jobs),"-parallel-testing-enabled","NO","-maximum-concurrent-test-simulator-destinations","1",
               "-enableCodeCoverage","YES","-only-testing:TranslatePDFNativeCoverageTests","-disableAutomaticPackageResolution",
               "OTHER_SWIFT_FLAGS=$(inherited) -vfsoverlay "+shlex.quote(str(overlay)),"test"]
    command[-1:-1] = test_selection
    if args.stream_safe_clang:
        command[-1:-1] = ["CC="+str(root / "ios/scripts/clang-stream-safe.py"),"CXX="+str(root / "ios/scripts/clang++-stream-safe.py")]
    coverage.write(output / "invocation.json",{"command":command,"configuration":config,"sdk":sdk,"compiler":version,"runID":run_id,"driverOriginalSHA256":driver_original_sha,"driverTestCopySHA256":coverage.sha(test_copy),"runnerSHA256":coverage.sha(Path(__file__)),"nativeBuildInputSHA256":native_input_hashes,"simulatorRuntime":runtime_id,"skippedUnavailableTests":[value.removeprefix("-skip-testing:") for value in test_selection],"availabilityReviewedDriverSHA256":AVAILABILITY_REVIEWED_DRIVER_SHA256 if test_selection else None,"preparedAtEpoch":time.time()})
    if args.prepare_only: print(f"Prepared {output}; no iOS build or simulator action run");return 0
    result = execute(command,output / "xcodebuild.log")
    attestation = compile_attestation(output / "xcodebuild.log",manifest,sdk)
    attestation["xcodebuildExit"] = result.returncode
    coverage.write(output / "compile-attestation.json",attestation)
    if result.returncode: print(f"Hosted build/test failed: {output}");return result.returncode
    data_root = Path(checked(["xcrun","simctl","get_app_container",args.simulator_id,"dev.kigya.translatepdf","data"]).strip())
    results = [];binary_artifacts = {};module_images = []
    (output / "binaries").mkdir()
    for module,filename in filenames.items():
        raw = data_root / "tmp" / filename
        if not raw.is_file(): raise ValueError(f"Actual module recorder output missing: {module}")
        copy = output / filename;shutil.copy2(raw,copy);record = json.loads(copy.read_text())
        if record["recorderModule"] != module or record["declaredModule"] != module: raise ValueError("Actual recorder module identity differs")
        image = Path(record["binaryPath"])
        artifact = output / "binaries" / (record["binarySHA256"][:16]+"-"+image.name)
        if not artifact.exists(): shutil.copy2(image,artifact)
        if coverage.sha(artifact) != record["binarySHA256"] or coverage.binary_uuid(artifact) != record["binaryUUID"]: raise ValueError("Actual loaded image bytes/UUID differ")
        binary_artifacts[str(image)] = artifact
        module_images.append({"module":module,"loadedPath":str(image),"artifact":str(artifact),"sha256":record["binarySHA256"],"uuid":record["binaryUUID"],"runtimeOS":record["runtimeOS"],"recordedAtEpoch":record["recordedAtEpoch"]})
        results.append(copy)
    for source in manifest["sources"]+manifest["extraOverlays"]:
        if coverage.sha(Path(source["source"])) != source["sourceSHA256"]: raise ValueError("Original input changed during hosted build/test")
    for path,digest in native_input_hashes.items():
        if coverage.sha(Path(path)) != digest: raise ValueError("Native bridge/framework input changed during hosted build/test: "+path)
    coverage.write(output / "loaded-module-attestation.json",module_images)
    report = coverage.validate(output / "measurement/manifest.json",results,binary_artifacts=binary_artifacts)
    coverage.write(output / "branch-gate.json",report)
    coverage.write(output / "provenance.json",{"manifestSHA256":coverage.sha(output / "measurement/manifest.json"),"invocationSHA256":coverage.sha(output / "invocation.json"),"compileAttestationSHA256":coverage.sha(output / "compile-attestation.json"),"moduleAttestationSHA256":coverage.sha(output / "loaded-module-attestation.json"),"xcodebuildLogSHA256":coverage.sha(output / "xcodebuild.log"),"runtimeResultSHA256":{str(path):coverage.sha(path) for path in results},"allOwnedOriginalSourcesUnchanged":True,"testOnlyDriverOriginalUnchanged":True})
    print(output)
    print(json.dumps({key:value for key,value in report.items() if key != "missing"},indent=2))
    return 0 if attestation["passed"] else 2


if __name__ == "__main__":raise SystemExit(main())
