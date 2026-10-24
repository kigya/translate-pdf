#!/usr/bin/env python3
"""Bind per-site independent recommendations to a prepared classification inventory.

This does not approve an invariant, add hits, remove source decisions, or pass the
application gate. It preserves all pending compiler/typed/ownership obligations.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bind(inventory_path: Path, review_path: Path) -> dict:
    inventory = json.loads(inventory_path.read_text())
    rows = []
    for line in review_path.read_text().splitlines():
        match = re.match(r"^\|\s*\d+\s+`([0-9a-f]{12})`\s*\|\s*`([^`]+)`\s*\|\s*([^|]+)\|\s*(.+)\|\s*$",line)
        if match: rows.append(match.groups())
    if not rows: raise ValueError("No source-specific independent review rows found")
    prefixes = [row[0] for row in rows]
    if len(prefixes) != len(set(prefixes)): raise ValueError("Duplicate independent review IDs")
    items = inventory["sites"]
    if len(items) != len(rows): raise ValueError("Inventory/review site counts differ")
    results = []
    for item in items:
        identity = {"sourceSHA256":item["sourceSHA256"],"kind":item["kind"],"start":item["startUTF8"],"end":item["endUTF8"]}
        expected_id = hashlib.sha256(json.dumps(identity,sort_keys=True,separators=(",",":")).encode()).hexdigest()
        if item["classificationID"] != expected_id: raise ValueError("Classification identity does not match original source/range")
        matches = [row for row in rows if item["classificationID"].startswith(row[0])]
        if len(matches) != 1: raise ValueError("Missing/ambiguous independent source-site recommendation")
        _, location, recommendation, obligation = matches[0]
        if location != f'{Path(item["source"]).name}:{item["line"]}': raise ValueError("Source location differs from review")
        source = Path(item["source"])
        if sha(source) != item["sourceSHA256"]: raise ValueError("Reviewed original source changed")
        raw = source.read_bytes()[item["startUTF8"]:item["endUTF8"]].decode()
        if raw != item["sourceText"]: raise ValueError("Reviewed source bytes/range differ")
        results.append({**item,"independentRecommendation":recommendation.strip(),
                        "independentObligation":obligation.strip(),
                        "dispositionStatus":"classified-pending-required-proofs",
                        "inventedHits":False,"applicationGateApproved":False})
    return {"schemaVersion":1,"registryDigest":inventory["registryDigest"],
            "inventorySHA256":sha(inventory_path),"classifierSHA256":sha(Path(__file__)),
            "independentReport":{"path":str(review_path.resolve()),"sha256":sha(review_path)},
            "status":"classified-pending-required-proofs","sites":results,
            "rule":"A scientific source/intrinsic distinction does not waive real source outcomes or prove user-input safety"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--inventory",type=Path,required=True)
    parser.add_argument("--review",type=Path,required=True)
    parser.add_argument("--output",type=Path,required=True)
    args = parser.parse_args()
    result = bind(args.inventory,args.review)
    args.output.write_text(json.dumps(result,indent=2,sort_keys=True)+"\n")
    print(f'{len(result["sites"])} exact source recommendations bound; all required proof obligations remain pending')
    return 0


if __name__ == "__main__": raise SystemExit(main())
