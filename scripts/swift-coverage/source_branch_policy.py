#!/usr/bin/env python3
"""Read-only post-run project Swift source-branch analysis (not a gate).

All input hashes must come from the caller's preserved evidence inventory. This
module checks byte binding and registry accounting, NOT compiler provenance,
semantic equivalence, active-target completeness, or original LLVM coverage.
No classifications waive an outcome here. Independent review must resolve their
source-specific obligations before application acceptance.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys

POLICY_VERSION = "project-swift-source-branches-v1"
BOOL_KINDS = {"condition", "ternary-condition", "logical-left", "logical-right", "for-where"}
BIND_KINDS = {"optional-binding", "implicit-optional-binding"}
SHORT_CIRCUIT_KINDS = {"coalescing-left", "optional-chain-base"}
COMPOSITE_RULE = "direct-logical-expression-spine-v1"
RULES = {
    "source-condition": "atomic-control-or-logical-operand-v1",
    "operation-diagnostic": "operation-distinct-from-control-predicate-v1",
    "error-diagnostic": "throw-is-not-a-boolean-condition-v1",
    "iteration-diagnostic": "for-in-next-observation-v1",
    "dispatch-diagnostic": "selected-arm-is-not-a-predicate-pair-v1",
}


class PolicyError(ValueError):
    """Malformed, incomplete or unbound evidence must not produce a percentage."""


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bound_json(path: Path, expected: str) -> dict:
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise PolicyError(f"Invalid expected SHA-256: {path}")
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != expected:
        raise PolicyError(f"SHA-256 drift: {path}")
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise PolicyError(f"Duplicate JSON key {key}: {path}")
            result[key] = value
        return result
    value = json.loads(data, object_pairs_hook=unique)
    if not isinstance(value, dict):
        raise PolicyError(f"Expected JSON object: {path}")
    return value


def reference(path: Path, expected: str) -> dict:
    return {"path": str(path.resolve()), "sha256": expected}


def classify(site: dict) -> tuple[list[str], str]:
    """Return eligible raw outcome names, retaining every other outcome too.

    A written dispatch alternative has ONE selected obligation. Rejected typed
    catch patterns are diagnostics, not invented false switch/catch arms.
    Different operations sharing a byte range are deliberately NOT coalesced.
    """
    kind = site["kind"]
    role = site.get("metricRole")
    expected_role = None
    if kind in BOOL_KINDS:
        outcomes = {"false", "true"}
        expected_role = "source-condition"
        if role == "composite-diagnostic":
            if site.get("metricRule") != COMPOSITE_RULE:
                raise PolicyError("Unknown composite AST rule")
            eligible, reason = [], "duplicate-composite-logical-result"
        else:
            eligible, reason = ["false", "true"], "atomic-bool-source-condition"
    elif kind == "availability":
        outcomes = {"false", "true"}
        expected_role = "source-condition"
        eligible, reason = ["false", "true"], "source-availability-condition"
    elif kind in BIND_KINDS:
        outcomes = {"nil", "some"}
        expected_role = "source-condition"
        eligible, reason = ["nil", "some"], "source-optional-binding"
    elif kind in SHORT_CIRCUIT_KINDS:
        outcomes = {"nil", "some"}
        expected_role = "operation-diagnostic"
        eligible, reason = ["nil", "some"], "explicit-source-short-circuit-continuation"
    elif kind == "iterator-next":
        outcomes = {"item", "exhausted"}
        expected_role = "iteration-diagnostic"
        eligible, reason = ["item", "exhausted"], "actual-for-in-iteration-and-exit"
    elif kind in {"switch-dispatch", "switch-pattern-dispatch", "catch-all"}:
        selected = "caught" if kind == "catch-all" else "selected"
        outcomes = {selected}
        expected_role = "dispatch-diagnostic"
        eligible, reason = [selected], "written-dispatch-alternative-selected"
    elif kind == "catch-pattern":
        declared = set(site["outcomes"])
        if declared not in ({"nil", "some"}, {"false", "true"}):
            raise PolicyError("Unknown typed catch outcomes")
        outcomes = declared
        expected_role = "dispatch-diagnostic"
        eligible, reason = ["some" if "some" in outcomes else "true"], "written-handler-selected-not-intermediate-rejection"
    elif kind in {"conditional-cast", "type-check", "throwing-expression"}:
        outcomes = {"nil", "some"} if kind == "conditional-cast" else {"false", "true"} if kind == "type-check" else {"success", "error"}
        expected_role = "error-diagnostic" if kind == "throwing-expression" else "operation-diagnostic"
        eligible, reason = [], "standalone-operation-or-implicit-call-error-diagnostic"
    else:
        raise PolicyError(f"Unknown site kind: {kind}")
    if len(site["outcomes"]) != len(outcomes) or set(site["outcomes"]) != outcomes:
        raise PolicyError(f"Unexpected outcomes for {kind}")
    if role != "composite-diagnostic" or kind not in BOOL_KINDS:
        if role != expected_role or site.get("metricRule") != RULES[expected_role]:
            raise PolicyError(f"Unknown kind/AST role mapping: {kind}/{role}")
    return eligible, reason


def analyze(manifest_path: Path, manifest_sha256: str,
            raw_artifacts: list[tuple[Path, str]]) -> dict:
    """Analyze immutable snapshots; never rewrite manifest, profile or hits.

    The complete raw registry is mandatory even for diagnostics and zero counts.
    Source snapshots and syntax inventories are hash-bound, while their compiler
    provenance/semantic interpretation remains an independent review obligation.
    """
    manifest = bound_json(manifest_path, manifest_sha256)
    if type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1:
        raise PolicyError("Unknown manifest schema")
    registry = manifest.get("registry")
    sources = manifest.get("sources")
    if not isinstance(registry, dict) or not registry or not isinstance(sources, list) or not sources:
        raise PolicyError("Empty or missing source/registry inventory")
    if not re.fullmatch(r"[0-9a-f]{64}", manifest.get("registryDigest", "")):
        raise PolicyError("Missing measurement identity")
    records, all_ids, choices, source_names = [], set(), set(), set()
    source_annex = []
    blockers = []
    for source in sources:
        name, namespace = source["source"], source["namespace"]
        if name in source_names or not isinstance(namespace, str) or not namespace:
            raise PolicyError("Duplicate source or empty namespace")
        source_names.add(name)
        snapshot = source["originalSnapshot"]
        if snapshot["sha256"] != source["sourceSHA256"]:
            raise PolicyError("Source snapshot identity mismatch")
        contents = Path(snapshot["path"]).read_bytes()
        if hashlib.sha256(contents).hexdigest() != snapshot["sha256"]:
            raise PolicyError(f"Source snapshot drift: {name}")
        ast = bound_json(Path(source["ast"]), source["astSHA256"])
        if type(ast.get("schemaVersion")) is not int or ast["schemaVersion"] != 1:
            raise PolicyError("Unknown syntax inventory schema")
        for key in ("sites", "unsupported", "reviewRequired", "structural", "classificationInventory", "configurationRegions"):
            if key not in source or ast.get(key) != source[key]:
                raise PolicyError(f"Manifest/syntax inventory mismatch: {name}/{key}")
        if source["unsupported"]:
            raise PolicyError(f"Unsupported source constructs: {name}")
        if any(region.get("state") not in {"active", "inactive"} for region in source["configurationRegions"]):
            raise PolicyError(f"Unresolved conditional compilation: {name}")
        source_annex.append({"source": name, "sourceSHA256": source["sourceSHA256"],
                             "originalSnapshot": snapshot, "ast": reference(Path(source["ast"]), source["astSHA256"]),
                             **{key: source[key] for key in ("unsupported", "reviewRequired", "structural", "classificationInventory", "configurationRegions")}})
        # No automatic invariant exemptions, weak-owner waivers, or literal hits.
        if source["classificationInventory"] or source["reviewRequired"] or source["structural"]:
            blockers.append({"source": name, "reason": "independent-source-classification-review-required",
                             "classifications": len(source["classificationInventory"]),
                             "reviewRequired": len(source["reviewRequired"]), "structural": len(source["structural"])})
        for site in source["sites"]:
            site_id = site["id"]
            if type(site_id) is not int or site_id < 0:
                raise PolicyError("Invalid site ID")
            full_id = f"{namespace}:{site_id}"
            if full_id in all_ids:
                raise PolicyError(f"Duplicate site: {full_id}")
            all_ids.add(full_id)
            start, end = site["startUTF8"], site["endUTF8"]
            if type(start) is not int or type(end) is not int or not 0 <= start < end <= len(contents):
                raise PolicyError(f"Invalid source range: {full_id}")
            choice = (name, site["kind"], start, end)
            if choice in choices:
                raise PolicyError(f"Duplicate source operation: {full_id}")
            choices.add(choice)
            eligible, reason = classify(site)
            if registry.get(full_id) != site["outcomes"]:
                raise PolicyError(f"Registry/source outcome mismatch: {full_id}")
            records.append({"site": full_id, "source": name, "sourceSHA256": source["sourceSHA256"],
                            "originalSite": site, "sourceText": contents[start:end].decode("utf-8"),
                            "eligibleOutcomes": eligible, "policyReason": reason,
                            "diagnosticOutcomes": [o for o in site["outcomes"] if o not in eligible],
                            "rawCounts": {o: 0 for o in site["outcomes"]}})
    if all_ids != set(registry):
        raise PolicyError("Registry/source site mismatch")
    if not raw_artifacts:
        raise PolicyError("No raw runtime artifacts")
    by_id = {record["site"]: record for record in records}
    seen_runs = set()
    raw_annex = []
    for path, expected in raw_artifacts:
        raw = bound_json(path, expected)
        if type(raw.get("schemaVersion")) is not int or raw["schemaVersion"] != 1 or raw.get("registryDigest") != manifest["registryDigest"] or raw.get("invalid") is not False:
            raise PolicyError(f"Invalid recorder/schema/measurement identity: {path}")
        run_id = raw.get("runID")
        if not isinstance(run_id, str) or not run_id or run_id in seen_runs:
            raise PolicyError("Missing/duplicate run ID")
        seen_runs.add(run_id)
        hits = raw.get("hits")
        if not isinstance(hits, dict) or set(hits) != all_ids:
            raise PolicyError(f"Missing/unknown raw record: {path}")
        for site_id, counts in hits.items():
            if not isinstance(counts, dict) or set(counts) != set(registry[site_id]):
                raise PolicyError(f"Missing/unknown raw outcome: {site_id}")
            for outcome, value in counts.items():
                if type(value) is not int or not 0 <= value <= 2**64 - 1:
                    raise PolicyError(f"Invalid raw counter: {site_id}/{outcome}")
                by_id[site_id]["rawCounts"][outcome] += value
        # Preserve complete raw metadata AND all per-export counts (not just sums).
        raw_annex.append({**reference(path, expected), "record": raw})
    required = [(r["site"], o) for r in records for o in r["eligibleOutcomes"]]
    if not required:
        raise PolicyError("Empty source denominator is not 100%")
    missing = [{"site": site, "outcome": outcome} for site, outcome in required if not by_id[site]["rawCounts"][outcome]]
    zeros = [{"site": r["site"], "outcome": o} for r in records for o, value in r["rawCounts"].items() if not value]
    covered = len(required) - len(missing)
    return {"schemaVersion": 1, "policyVersion": POLICY_VERSION,
            "policySHA256": sha256(Path(__file__)), "policyReviewStatus": "pending-independent-review",
            "scope": "post-run-project-Swift-source-branch-observations",
            "bindingScope": "pinned-input-bytes-and-registry-accounting-only",
            "manifest": reference(manifest_path, manifest_sha256), "registryDigest": manifest["registryDigest"],
            "preparationDigest": manifest.get("preparationDigest"),
            "sourceBranchCoverage": {"covered": covered, "denominator": len(required),
                                     "percent": 100 * covered / len(required), "allEligibleOutcomesObserved": not missing,
                                     "denominatorStatus": "candidate-until-independent-policy-review", "missing": missing},
            "rawSiteCount": len(records), "rawOutcomeCount": sum(len(r["rawCounts"]) for r in records),
            "allRawZeros": zeros, "records": records, "rawArtifacts": raw_annex, "sourceAnnex": source_annex,
            "independentAcceptanceObligations": blockers,
            "acceptance": {"semanticEquivalence": "not-evaluated", "compilerAndRuntimeProvenance": "not-evaluated",
                           "activeTargetSyntaxCompleteness": "not-evaluated", "sourceClassifications": "not-evaluated",
                           "originalLLVMLineFunctionRegionCoverage": "not-evaluated", "applicationGate": "not-evaluated"}}


def artifact_argument(value: str) -> tuple[Path, str]:
    expected, separator, path = value.partition("=")
    if not separator or not path:
        raise argparse.ArgumentTypeError("Expected SHA256=PATH")
    return Path(path), expected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--result", required=True, action="append", type=artifact_argument, metavar="SHA256=PATH")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    try:
        report = analyze(args.manifest, args.manifest_sha256, args.result)
        # Exclusive creation prevents accidental replacement of preserved evidence.
        with args.output.open("x") as handle:
            json.dump(report, handle, indent=2, sort_keys=True)
            handle.write("\n")
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Source-branch analysis rejected: {error}", file=sys.stderr)
        return 2
    return 0  # Successful analysis is NOT coverage/acceptance success.


if __name__ == "__main__":
    raise SystemExit(main())
