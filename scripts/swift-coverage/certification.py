#!/usr/bin/env python3
"""Collect compiler evidence for review; this program cannot issue approval.

The typed AST and SIL are real compiler output. Comparisons and obligations are
review aids, never a claim that arbitrary probe erasure preserves Swift semantics.
All compiler work is serial and writes only into a fresh project-local directory.
"""
from __future__ import annotations

import argparse
from bisect import bisect_left, bisect_right
from collections import Counter
from functools import lru_cache
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shlex
import subprocess

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("certification_coverage", HERE / "coverage.py")
coverage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(coverage)
COMPILER = Path(coverage.DEVELOPER) / "Toolchains/XcodeDefault.xctoolchain/usr/bin/swiftc"
APPROVED_PLUGIN = coverage.ROOT / "node_modules/expo-modules-macros/apple/ExpoModulesMacros"

# A finite grammar is deliberate. An unrecognized source transformation may not
# inherit an approval intended for a different family.
FAMILIES = {
    "condition": "bool-identity", "logical-left": "bool-identity",
    "logical-right": "bool-identity", "ternary-condition": "bool-identity",
    "type-check": "bool-identity", "for-where": "bool-identity",
    "optional-binding": "optional-identity", "implicit-optional-binding": "optional-identity",
    "optional-chain-base": "optional-identity", "conditional-cast": "optional-identity",
    "coalescing-left": "optional-identity", "iterator-next": "sequence-adapter",
    "throwing-expression": "throwing-autoclosure", "switch-dispatch": "statement-probe",
    "switch-pattern-dispatch": "split-pattern-dispatch", "catch-all": "statement-probe",
    "catch-pattern": "catch-dispatch", "availability": "availability-token",
}
OBLIGATIONS = {
    "bool-identity": ["same-resolved-expression-and-Bool-type", "same-evaluation-and-short-circuit-order", "recorder-does-not-reenter-owned-code"],
    "optional-identity": ["same-resolved-expression-and-Optional-substitution", "same-contextual-overloads", "same-ownership-weak-lifetime-and-exclusivity"],
    "sequence-adapter": ["same-Sequence-and-Iterator-witnesses", "same-makeIterator-next-order", "same-element-and-base-lifetimes", "same-break-continue-throw-cleanup"],
    "throwing-autoclosure": ["same-resolved-throwing-expression", "same-capture-and-lazy-evaluation", "same-success-error-rethrow-and-cleanup", "typed-nonthrowing-disposition-if-applicable"],
    "statement-probe": ["same-original-control-transfer-and-scope", "probe-executed-only-for-original-selected-arm"],
    "split-pattern-dispatch": ["same-ordered-pattern-matching", "same-body-bindings-scopes-and-cleanup"],
    "catch-dispatch": ["same-error-match-order-and-type-casts", "same-error-binding-lifetime-and-rethrow", "same-catch-body-declaration-resolution"],
    "availability-token": ["same-availability-predicate-and-refinement", "same-later-condition-laziness", "same-exit-defer-and-token-lifetime"],
}


def artifact(path: Path) -> dict:
    return {"path": str(path.resolve()), "sha256": coverage.sha(path)}


def expand(tokens: list[str], cwd: Path, responses: dict[str, str], stack: tuple[Path, ...] = ()) -> list[str]:
    result = []
    for token in tokens:
        if not token.startswith("@"):
            result.append(token)
            continue
        path = Path(token[1:]); path = (path if path.is_absolute() else cwd / path).resolve()
        if len(stack) >= 8 or path in stack:
            raise ValueError("Cyclic/deep compiler response files")
        if not path.is_file():
            raise ValueError(f"Missing actual compiler response file: {path}")
        before = coverage.sha(path)
        result.extend(expand(shlex.split(path.read_text()), cwd, responses, (*stack, path)))
        if coverage.sha(path) != before:
            raise ValueError("Compiler response file changed while reading")
        responses[str(path)] = before
    return result


def values(tokens: list[str], key: str) -> list[str]:
    """Top-level Swift options only; -Xcc/-Xfrontend operands are not Swift flags."""
    found = []; index = 0
    while index < len(tokens):
        token = tokens[index]
        if token in {"-Xcc", "-Xfrontend", "-Xlinker", "-Xllvm"}:
            if index + 1 >= len(tokens): raise ValueError("Truncated forwarded compiler argument")
            index += 2; continue
        if token == key:
            if index + 1 >= len(tokens): raise ValueError(f"Missing value: {key}")
            found.append(tokens[index + 1]); index += 2; continue
        if key == "-D" and token.startswith("-D") and len(token) > 2:
            found.append(token[2:])
        index += 1
    return found


def single(tokens: list[str], key: str) -> str:
    options = values(tokens, key)
    if len(options) != 1: raise ValueError(f"Expected exactly one {key}, found {options}")
    return options[0]


def read_jobs(log: Path, cwd: Path, modules: set[str]) -> list[dict]:
    """Only the pinned Swift driver's logged invocation is executable input.

    Frontend/discovery log lines and arbitrary shell text are never replayed.
    Every response file is expanded before the command is frozen.
    """
    result = []; seen = set()
    for line in log.read_text(errors="strict").splitlines():
        if not line.lstrip().startswith("builtin-SwiftDriver -- "): continue
        tokens = shlex.split(line.strip())
        if len(tokens) < 4 or tokens[:2] != ["builtin-SwiftDriver", "--"]: continue
        if Path(tokens[2]) != COMPILER:
            raise ValueError("Compile job does not use the pinned Xcode Swift compiler")
        responses = {}; tokens = expand(tokens[3:], cwd, responses)
        module = single(tokens, "-module-name")
        if module not in modules: continue
        identity = coverage.digest(tokens)
        if identity in seen: continue
        seen.add(identity)
        workdir = values(tokens, "-working-directory")
        if len(workdir) > 1: raise ValueError("Ambiguous compiler working directory")
        job_cwd = Path(workdir[0]).resolve() if workdir else cwd.resolve()
        sources = []
        # Sources are positional operands. Skip paired forwarded options so an
        # imported file named *.swift cannot accidentally become an owned input.
        index = 0
        while index < len(tokens):
            token = tokens[index]
            if token in {"-Xcc", "-Xfrontend", "-Xlinker", "-Xllvm"}:
                index += 2; continue
            if token.endswith(".swift") and not token.startswith("-"):
                path = Path(token); path = (path if path.is_absolute() else job_cwd / path).resolve()
                if not path.is_file(): raise ValueError(f"Missing compiled Swift input: {path}")
                sources.append(str(path))
            index += 1
        if not sources or len(sources) != len(set(sources)): raise ValueError("Missing/duplicate Swift inputs")
        result.append({"module": module, "arguments": tokens, "argumentsDigest": identity,
                       "logLineSHA256": hashlib.sha256(line.encode()).hexdigest(),
                       "cwd": str(job_cwd), "responseFiles": responses, "sources": sources,
                       "target": single(tokens, "-target"), "sdk": single(tokens, "-sdk"),
                       "languageMode": single(tokens, "-swift-version"),
                       "swiftDefines": sorted(set(values(tokens, "-D")))})
    if {job["module"] for job in result} != modules:
        raise ValueError("Missing actual Swift driver jobs for required modules")
    for module in modules:
        if sum(job["module"] == module for job in result) != 1:
            raise ValueError(f"Multiple distinct jobs for {module}; select an exact configuration log")
    return result


# These flags affect outputs/scheduling only. All other flags must be handled
# explicitly below; silently dropping a semantic flag would invalidate the proof.
DROP_ONE = {"-output-file-map", "-emit-module-path", "-emit-objc-header-path", "-index-store-path",
            "-module-cache-path", "-clang-scanner-module-cache-path", "-sdk-module-cache-path",
            "-clang-build-session-file", "-num-threads", "-j", "-o", "-serialize-diagnostics-path"}
DROP_ZERO = {"-c", "-incremental", "-enable-batch-mode", "-use-frontend-parseable-output", "-save-temps",
             "-explicit-module-build", "-serialize-diagnostics", "-emit-dependencies", "-emit-module",
             "-emit-objc-header", "-emit-const-values", "-experimental-emit-module-separately",
             "-validate-clang-modules-once", "-no-emit-module-separately-wmo"}
KEEP_ONE = {"-module-name", "-sdk", "-target", "-target-variant", "-swift-version", "-D", "-I", "-F",
            "-Fsystem", "-import-objc-header", "-import-module", "-module-link-name", "-working-directory",
            "-enable-experimental-feature", "-enable-upcoming-feature", "-strict-concurrency", "-enforce-exclusivity",
            "-clang-target", "-resource-dir", "-package-name", "-module-alias", "-Xllvm"}
KEEP_ZERO = {"-Onone", "-O", "-Osize", "-Ounchecked", "-g", "-gline-tables-only", "-gnone",
             "-enable-testing", "-import-underlying-module", "-enable-bare-slash-regex", "-no-color-diagnostics",
             "-profile-coverage-mapping", "-profile-generate", "-disable-cmo", "-parse-as-library",
             "-enable-library-evolution", "-whole-module-optimization", "-disable-implicit-concurrency-module-import",
             "-disable-implicit-string-processing-module-import", "-application-extension"}
FRONTEND_KEEP = {"-serialize-debugging-options", "-enable-objc-interop", "-disable-objc-interop"}


def replay_arguments(job: dict, output: Path, overlay: Path | None) -> tuple[list[str], list[dict]]:
    """Construct one safe, serial analysis invocation from a real compile job.

    Unknown flags block extraction. Clang stat/session/module cache destinations
    are removed/rebound, so no shared Xcode cache is written by this invocation.
    """
    tokens = job["arguments"]; kept = []; changes = []; index = 0
    while index < len(tokens):
        token = tokens[index]
        if token in DROP_ONE:
            if index + 1 >= len(tokens): raise ValueError("Truncated output/cache flag")
            changes.append({"option": token, "value": tokens[index + 1], "reason": "isolated-analysis-output-or-scheduling"})
            index += 2; continue
        if token in DROP_ZERO or re.fullmatch(r"-j\d+", token):
            changes.append({"option": token, "reason": "analysis-action-or-scheduling"}); index += 1; continue
        if token == "-vfsoverlay":
            if index + 1 >= len(tokens): raise ValueError("Truncated VFS overlay option")
            changes.append({"option": token, "value": tokens[index + 1], "reason": "explicit-original-or-transformed-view"})
            index += 2; continue
        if token == "-Xfrontend":
            if index + 1 >= len(tokens): raise ValueError("Truncated frontend option")
            operand = tokens[index + 1]
            if operand == "-const-gather-protocols-file":
                if index + 3 >= len(tokens) or tokens[index + 2] != "-Xfrontend": raise ValueError("Malformed constant extraction option")
                changes.append({"option": operand, "value": tokens[index + 3], "reason": "unused-output-metadata"})
                index += 4; continue
            if operand not in FRONTEND_KEEP: raise ValueError(f"Unreviewed frontend option: {operand}")
            kept.extend(tokens[index:index + 2]); index += 2; continue
        if token == "-Xcc":
            if index + 1 >= len(tokens): raise ValueError("Truncated Clang option")
            operand = tokens[index + 1]
            if operand == "-ivfsstatcache":
                if index + 3 >= len(tokens) or tokens[index + 2] != "-Xcc": raise ValueError("Malformed Clang stat cache")
                changes.append({"option": operand, "value": tokens[index + 3], "reason": "no-shared-stat-cache"})
                index += 4; continue
            if operand.startswith(("-fmodules-cache-path", "-fmodule-file=", "-fplugin", "-load")):
                raise ValueError(f"Unreviewed shared cache/plugin/explicit module operand: {operand}")
            # Header search paths, definitions and module maps affect semantics;
            # preserve them byte-for-byte. No executable Clang plugin is allowed.
            kept.extend(tokens[index:index + 2]); index += 2; continue
        if token == "-load-plugin-executable":
            if index + 1 >= len(tokens): raise ValueError("Truncated compiler plugin operand")
            plugin, separator, module = tokens[index + 1].partition("#")
            if not separator or Path(plugin).resolve() != APPROVED_PLUGIN.resolve() or module != "ExpoModulesMacros" or not APPROVED_PLUGIN.is_file():
                raise ValueError("Unreviewed compiler plugin executable")
            # This is the exact installed Expo macro binary already used by the
            # actual build. Both invocations retain it and bind its bytes.
            changes.append({"option": token, "reason": "retained-exact-actual-build-plugin", "plugin": artifact(APPROVED_PLUGIN)})
            kept.extend(tokens[index:index + 2]); index += 2; continue
        if token in KEEP_ONE:
            if index + 1 >= len(tokens): raise ValueError("Truncated semantic option")
            kept.extend(tokens[index:index + 2]); index += 2; continue
        if token in KEEP_ZERO or token.startswith(("-D", "-I", "-F")) and len(token) > 2:
            kept.append(token); index += 1; continue
        if token in job["sources"]:
            kept.append(token); index += 1; continue
        raise ValueError(f"Unreviewed compiler argument blocks replay: {token}")
    if overlay is not None: kept += ["-vfsoverlay", str(overlay.resolve())]
    kept += ["-j", "1", "-module-cache-path", str(output / "module-cache"),
             "-Xcc", "-fmodules-cache-path=" + str(output / "clang-module-cache")]
    return kept, changes


def execute(arguments: list[str], cwd: Path, stdout: Path, stderr: Path) -> dict:
    env = {**os.environ, "DEVELOPER_DIR": coverage.DEVELOPER}
    # The explicit SDK argument is authoritative. Ambient CLT SDKROOT otherwise
    # selects an incompatible Swift interface even with DEVELOPER_DIR set.
    env.pop("SDKROOT", None)
    with stdout.open("w") as out, stderr.open("w") as err:
        result = subprocess.run([str(COMPILER), *arguments], cwd=cwd, env=env, text=True, stdout=out, stderr=err)
    return {"argv": [str(COMPILER), *arguments], "cwd": str(cwd), "exit": result.returncode,
            "stdout": artifact(stdout), "stderr": artifact(stderr)}


def typed_nodes(text: str) -> list[dict]:
    """Retain typed headers without pretending Swift's dump is a stable AST API.

    Only documented-by-fixture fields are parsed. Full raw dumps remain bound and
    reviewable; unrecognized node shapes cannot be automatically approved.
    """
    nodes = []; ancestors = []
    for ordinal, line in enumerate(text.splitlines(), 1):
        match = re.match(r"^(\s*)\((?:original_init=|processed_init=)?(\#?[A-Za-z_]+)\b(.*)$", line)
        if not match: continue
        tail = match[3]
        item = {"dumpLine": ordinal, "indent": len(match[1]), "kind": match[2], "header": line.strip()}
        while ancestors and ancestors[-1]["indent"] >= item["indent"]: ancestors.pop()
        for key in ("type", "interface_type", "decl", "isolation_crossing", "captures", "thrown_type", "cast_to"):
            value = re.search(r"\b" + key + r'="((?:[^"\\]|\\.)*)"', tail)
            if value: item[key] = value[1]
        for key in ("ownership", "cast_kind"):
            value = re.search(r"\b" + key + r"=([A-Za-z_]+)", tail)
            if value: item[key] = value[1]
        capture = re.search(r"\bcaptures=(\([^\n]*\))", tail)
        if capture: item["captures"] = capture[1]
        span = re.search(r"range=\[(.+):(\d+):(\d+) - (?:line:(\d+):(\d+)|(.+):(\d+):(\d+))\]", tail)
        if span:
            item["range"] = {"source": span[1], "startLine": int(span[2]), "startColumn": int(span[3]),
                             "endLine": int(span[4] or span[7]), "endColumn": int(span[5] or span[8])}
        else:
            for ancestor in reversed(ancestors):
                if "range" in ancestor:
                    item["enclosingRange"] = ancestor["range"]
                    item["rangeOwnerDumpLine"] = ancestor["dumpLine"]
                    break
        item["throws"] = bool(re.search(r"(?:^|\s)throws(?:\s|$)", tail))
        item["nothrow"] = bool(re.search(r"(?:^|\s)nothrow(?:\s|$)", tail))
        nodes.append(item)
        ancestors.append(item)
    if not nodes or nodes[0]["kind"] != "source_file": raise ValueError("Unrecognized/empty Swift typed AST dump")
    return nodes


def compiler_output(invocation: dict, action: str) -> tuple[str, dict]:
    marker = "(source_file " if action == "ast" else "sil_stage raw\n"
    candidates = []
    for stream in ("stdout", "stderr"):
        text = Path(invocation[stream]["path"]).read_text()
        if marker in text: candidates.append((text, invocation[stream]))
    if len(candidates) != 1: raise ValueError("Missing/ambiguous real compiler analysis output stream")
    return candidates[0]


def sil_summary(text: str) -> dict:
    if not text.startswith("sil_stage raw\n"): raise ValueError("Expected real raw SILGen output")
    functions = []
    for line in text.splitlines():
        if line.startswith("sil ") and " : $" in line: functions.append(line)
    if not functions: raise ValueError("Empty SIL function inventory")
    instructions = Counter()
    for line in text.splitlines():
        match = re.match(r"\s+(?:%\d+ = )?([a-z_]+)\b", line)
        if match: instructions[match[1]] += 1
    return {"functionHeaders": functions, "instructionCounts": dict(sorted(instructions.items())),
            "comparisonStatus": "raw-ownership-control-flow-and-witness-review-required"}


def sil_locations(text: str) -> list[dict]:
    result = []; function = None
    for number, line in enumerate(text.splitlines(), 1):
        declaration = re.match(r"sil .+?@([^ ]+) : (.+)", line)
        if declaration: function = declaration[1]
        location = re.search(r', loc (\* )?"([^"]+)":(\d+):(\d+)', line)
        operation = re.match(r"\s+(?:%\d+ = )?([a-z_]+)\b", line)
        if not location or not operation: continue
        symbol = re.search(r"\b(?:function_ref|class_method|objc_method|witness_method)\b.*?(@[^ :]+|#[^ :]+)", line)
        result.append({"dumpLine": number, "function": function, "opcode": operation[1], "raw": line,
                       "source": location[2], "line": int(location[3]), "column": int(location[4]),
                       "autoGeneratedLocation": bool(location[1]),
                       "resolvedReference": symbol[1] if symbol else None})
    if not result: raise ValueError("Missing source-mapped SIL instruction evidence")
    return result


def source_sil_projection(source: dict, nodes: list[dict], transformed: bool) -> list[dict]:
    raw = Path(source["transformed"] if transformed else source["source"]).read_bytes()
    paths = {Path(source["source"]).resolve()}
    if transformed: paths.add(Path(source["transformed"]).resolve())
    projected = []; resolved_paths = {}
    segments = source["sourceMap"]; starts = [item["transformedStartUTF8"] for item in segments]
    for node in nodes:
        name = node["source"]
        if name not in resolved_paths: resolved_paths[name] = Path(name).resolve()
        if resolved_paths[name] not in paths: continue
        offset = byte_offset(raw, node["line"], node["column"])
        offset = mapped_offset(source, offset, segments, starts) if transformed else offset
        if offset is None: continue
        projected.append({**node, "originalOffsetUTF8": offset})
    return projected


def add_sil_comparisons(source: dict, sites: list[dict], original: list[dict], transformed: list[dict]) -> None:
    before = source_sil_projection(source, original, False)
    after = source_sil_projection(source, transformed, True)
    for site in sites:
        left = [node for node in before if site["startUTF8"] <= node["originalOffsetUTF8"] < site["endUTF8"]]
        right = [node for node in after if site["startUTF8"] <= node["originalOffsetUTF8"] < site["endUTF8"]]
        site["sourceMappedSIL"] = {"original": left, "transformed": right,
                                   "originalOpcodeCounts": dict(Counter(node["opcode"] for node in left)),
                                   "transformedOpcodeCounts": dict(Counter(node["opcode"] for node in right)),
                                   "originalResolvedReferences": sorted(set(node["resolvedReference"] for node in left if node["resolvedReference"])),
                                   "transformedResolvedReferences": sorted(set(node["resolvedReference"] for node in right if node["resolvedReference"])),
                                   "status": "concrete-ownership-effects-witnesses-and-control-flow-review-required",
                                   "limitation": "Inserted-location instructions remain in raw SIL; instruction counts alone do not prove aliasing, ARC, error or laziness equivalence"}


def source_span(source: Path, start: int, end: int) -> tuple[int, int, int, int]:
    raw = source.read_bytes()
    if not 0 <= start < end <= len(raw): raise ValueError("Invalid source range")
    first = raw[:start]; last = raw[:end - 1]
    return first.count(b"\n") + 1, start - first.rfind(b"\n"), last.count(b"\n") + 1, end - last.rfind(b"\n") - 1


@lru_cache(maxsize=32)
def line_layout(raw: bytes) -> tuple[tuple[int, ...], tuple[int, ...]]:
    lines = raw.splitlines(keepends=True)
    offsets = []; position = 0
    for item in lines: offsets.append(position); position += len(item)
    return tuple(offsets), tuple(len(item) for item in lines)


def byte_offset(raw: bytes, line: int, column: int) -> int:
    offsets, lengths = line_layout(raw)
    if line < 1 or line > len(offsets) or column < 1 or column > lengths[line - 1] + 1:
        raise ValueError("Compiler source location outside bound UTF-8 bytes")
    return offsets[line - 1] + column - 1


def validate_source_map(source: dict) -> None:
    original = Path(source["source"]).read_bytes(); transformed = Path(source["transformed"]).read_bytes()
    previous_original = previous_transformed = 0
    if not source.get("sourceMap"): raise ValueError("Missing lossless original/transformed byte map")
    for segment in source["sourceMap"]:
        start, end = segment["originalStartUTF8"], segment["originalEndUTF8"]
        target, finish = segment["transformedStartUTF8"], segment["transformedEndUTF8"]
        if not 0 <= previous_original <= start < end <= len(original) or not 0 <= previous_transformed <= target < finish <= len(transformed):
            raise ValueError("Invalid or overlapping original/transformed byte map")
        if original[start:end] != transformed[target:finish]: raise ValueError("Source map bytes differ")
        previous_original, previous_transformed = end, finish


def mapped_offset(source: dict, offset: int, segments: list[dict] | None = None, starts: list[int] | None = None) -> int | None:
    segments = source["sourceMap"] if segments is None else segments
    starts = [item["transformedStartUTF8"] for item in segments] if starts is None else starts
    index = bisect_right(starts, offset) - 1
    if index >= 0:
        segment = segments[index]
        if offset < segment["transformedEndUTF8"]:
            return segment["originalStartUTF8"] + offset - segment["transformedStartUTF8"]
    return None


def semantic_projection(source: dict, nodes: list[dict], transformed: bool) -> list[dict]:
    """Compare retained source-origin resolution without erasing probe semantics.

    Inserted/replaced AST nodes remain available in the raw dump. They cannot be
    equated to original nodes by deleting helper names or renumbering SIL values.
    """
    raw = Path(source["transformed"] if transformed else source["source"]).read_bytes()
    accepted_paths = {Path(source["source"]).resolve()}
    if transformed: accepted_paths.add(Path(source["transformed"]).resolve())
    projected = []; resolved_paths = {}
    segments = source["sourceMap"]; starts = [item["transformedStartUTF8"] for item in segments]
    for node in nodes:
        span = node.get("range")
        if not span: continue
        name = span["source"]
        if name not in resolved_paths: resolved_paths[name] = Path(name).resolve()
        if resolved_paths[name] not in accepted_paths: continue
        start = byte_offset(raw, span["startLine"], span["startColumn"])
        end = byte_offset(raw, span["endLine"], span["endColumn"])
        if transformed:
            start, end = mapped_offset(source, start, segments, starts), mapped_offset(source, end, segments, starts)
            if start is None or end is None: continue
        signature = {key: node[key] for key in ("kind", "type", "interface_type", "decl", "isolation_crossing", "captures", "ownership", "cast_kind", "cast_to", "throws", "nothrow") if key in node}
        if "decl" in signature:
            # Local declarations include source coordinates. Map only known
            # exact source locations; unresolved generated contexts are retained.
            def location(match: re.Match) -> str:
                name = match[1]
                if name not in resolved_paths: resolved_paths[name] = Path(name).resolve()
                path = resolved_paths[name]
                if path not in accepted_paths: return match[0]
                offset = byte_offset(raw, int(match[2]), int(match[3]))
                offset = mapped_offset(source, offset, segments, starts) if transformed else offset
                return "@owned-source:" + str(offset) if offset is not None else match[0]
            signature["decl"] = re.sub(r"@([^@\"\n]+):(\d+):(\d+)", location, signature["decl"])
        projected.append({"startUTF8": start, "endTokenStartUTF8": end, "signature": signature, "dumpLine": node["dumpLine"]})
    return projected


def compare_typed_sites(source: dict, original_nodes: list[dict], transformed_nodes: list[dict]) -> list[dict]:
    validate_source_map(source)
    original = semantic_projection(source, original_nodes, False)
    transformed = semantic_projection(source, transformed_nodes, True)
    result = []
    for site in site_obligations(source, original_nodes):
        def within(item: dict) -> bool:
            return site["startUTF8"] <= item["startUTF8"] <= item["endTokenStartUTF8"] < site["endUTF8"]
        before = [item for item in original if within(item)]
        after = [item for item in transformed if within(item)]
        def counts(items: list[dict]) -> Counter:
            return Counter(json.dumps({key: item[key] for key in ("startUTF8", "endTokenStartUTF8", "signature")}, sort_keys=True) for item in items)
        left, right = counts(before), counts(after)
        missing = [json.loads(key) | {"multiplicity": value} for key, value in (left - right).items()]
        added = [json.loads(key) | {"multiplicity": value} for key, value in (right - left).items()]
        result.append({**site, "retainedOriginalNodeCount": len(before), "retainedTransformedNodeCount": len(after),
                       "sourceOriginTypedNodesEqual": bool(before) and left == right,
                       "missingOrChangedOriginalTypedNodes": missing, "addedSourceOriginTypedNodes": added,
                       "transformedTypedNodes": after,
                       "comparisonMeaning": "Source-origin type/declaration/effect equality only; inserted scopes, ARC and SIL are not erased or certified"})
    return result


def any_result_hints(source: dict, original_nodes: list[dict], original_ast: Path) -> dict:
    """Bind the finite Any-returning try? inference repair to original typing.

    Do not guess result types from API spellings. A cast on the *result* of the
    generic helper prevents try? contextual inference from requesting T=Any?.
    The exact original call must itself resolve to Any before this is offered.
    """
    raw = Path(source["source"]).read_bytes(); entries = []
    projection = semantic_projection(source, original_nodes, False)
    for site in source["sites"]:
        start, end = site["startUTF8"], site["endUTF8"]
        if site["kind"] != "throwing-expression" or raw[start:start + 4] != b"try?": continue
        expression = start + 4
        while expression < end and raw[expression:expression + 1].isspace(): expression += 1
        candidates = {item["signature"].get("type") for item in projection
                      if item["signature"]["kind"] == "call_expr" and item["startUTF8"] == expression
                      and item["endTokenStartUTF8"] == end - 1}
        candidates.discard("<null>")
        if candidates == {"Any"}:
            entries.append({"siteStartUTF8": start, "expressionStartUTF8": expression, "endUTF8": end, "type": "Any"})
    return {"sourceSHA256": source["sourceSHA256"], "originalTypedAST": artifact(original_ast), "typingHints": entries,
            "status": "original-type-bound-rewrite-hints-not-equivalence-approval"}


def site_obligations(source: dict, nodes: list[dict]) -> list[dict]:
    path = Path(source["source"]).resolve(); result = []; resolved_paths = {}; owned_nodes = []
    for node in nodes:
        area = node.get("range")
        if not area: continue
        name = area["source"]
        if name not in resolved_paths: resolved_paths[name] = Path(name).resolve()
        if resolved_paths[name] == path: owned_nodes.append(node)
    owned_nodes.sort(key=lambda node: (node["range"]["startLine"], node["range"]["startColumn"]))
    starts = [(node["range"]["startLine"], node["range"]["startColumn"]) for node in owned_nodes]
    for site in source["sites"]:
        key = f'{source["namespace"]}:{site["id"]}'
        family = FAMILIES.get(site["kind"])
        span = source_span(path, site["startUTF8"], site["endUTF8"])
        candidates = []
        for node in owned_nodes[bisect_left(starts, span[:2]):bisect_right(starts, span[2:])]:
            area = node.get("range")
            if (area["startLine"], area["startColumn"]) < span[:2] or (area["endLine"], area["endColumn"]) > span[2:]: continue
            candidates.append(node)
        enclosing = []
        expected_enclosing = {"availability": "if_stmt", "switch-pattern-dispatch": "case_stmt", "catch-pattern": "case_stmt"}.get(site["kind"])
        if not candidates and expected_enclosing:
            containers = [node for node in owned_nodes
                          if node["kind"] == expected_enclosing
                          and (node["range"]["startLine"], node["range"]["startColumn"]) <= span[:2]
                          and (node["range"]["endLine"], node["range"]["endColumn"]) >= span[2:]]
            if containers:
                nearest = min(containers, key=lambda node: (node["range"]["endLine"] - node["range"]["startLine"], node["range"]["endColumn"] - node["range"]["startColumn"]))
                enclosing = [nearest] + [node for node in nodes if node.get("rangeOwnerDumpLine") == nearest["dumpLine"]]
        result.append({"site": key, "kind": site["kind"], "family": family,
                       "source": source["source"], "sourceSHA256": source["sourceSHA256"],
                       "startUTF8": site["startUTF8"], "endUTF8": site["endUTF8"], "outcomes": site["outcomes"],
                       "originalTypedNodes": candidates,
                       "originalTypedEnclosingNodes": enclosing,
                       "mappingScope": "exact-source-expression-nodes" if candidates else "enclosing-compiler-control-or-pattern" if enclosing else "unresolved",
                       "requiredProofs": OBLIGATIONS.get(family, ["unsupported-transformation-family"]),
                       "status": "pending-independent-concrete-typed-and-SIL-review" if family and (candidates or enclosing) else "blocked-missing-typed-mapping-or-family",
                       "coverageDisposition": "retain-all-declared-outcomes-including-zero-hits"})
    return result


def audit_configuration(manifest: dict, jobs: list[dict]) -> dict:
    failures = []; records = []; generated = []
    required = {source["source"] for source in manifest["sources"]}
    compiled = {source for job in jobs for source in job["sources"]}
    if required - compiled: failures.append("Compiled/owned source inventory mismatch")
    for value in sorted(compiled - required):
        path = Path(value).resolve()
        expo = coverage.ROOT / "ios/Pods/Target Support Files/Pods-TranslatePDF/ExpoModulesProvider.swift"
        generated_assets = (path.is_relative_to(coverage.ROOT / ".local-private") and path.name == "GeneratedAssetSymbols.swift"
                            and path.parent.name == "DerivedSources" and "Intermediates.noindex" in path.parts)
        if path == expo.resolve() or generated_assets:
            generated.append({**artifact(path), "classification": "generated-build-input-retained-in-both-compilations"})
        else: failures.append("Compiled/owned source inventory mismatch: unclassified input " + value)
    for source in manifest["sources"]:
        matches = [job for job in jobs if source["source"] in job["sources"]]
        if len(matches) != 1:
            failures.append(f'Missing/ambiguous actual configuration: {source["source"]}'); continue
        job = matches[0]; config = source.get("configuration", manifest.get("configuration"))
        triple = re.fullmatch(r"([^-]+)-apple-(ios|macosx)([0-9.]+)(?:-(simulator|macabi))?", job["target"])
        item = {"source": source["source"], "module": job["module"], "actualTarget": job["target"],
                "actualSDK": job["sdk"], "actualSwiftDefines": job["swiftDefines"],
                "preparedConfiguration": config, "configurationRegions": source.get("configurationRegions", []),
                "argumentsDigest": job["argumentsDigest"]}
        if not triple or not config:
            failures.append(f'Unknown target or missing preparation configuration: {source["source"]}')
        else:
            expected = {"os": "iOS" if triple[2] == "ios" else "macOS", "arch": triple[1],
                        "environment": triple[4], "defines": job["swiftDefines"]}
            for key, value in expected.items():
                actual = sorted(set(config.get(key, []))) if key == "defines" else config.get(key)
                if actual != value: failures.append(f'{source["source"]}: prepared {key} differs from actual Swift job')
            # Language mode 5 does not necessarily mean effective swift(>=5.x)
            # predicates are evaluated as version 5.0. Compiler evaluation below
            # must certify these predicates before an active-region proof exists.
            if not config.get("languageVersion") or str(config["languageVersion"][0]) != job["languageMode"]:
                failures.append(f'{source["source"]}: language mode differs')
            regions = source.get("configurationRegions", [])
            predicate_checks = []
            if regions:
                # This finite independent evaluator covers the exact current
                # production grammar. More complex/import/feature/version
                # predicates require an actual compiler predicate probe first.
                condition = regions[0].get("condition")
                answer = (expected["os"] == "iOS") if condition == "os(iOS)" else (condition in job["swiftDefines"]) if isinstance(condition, str) and re.fullmatch(r"[A-Za-z_][A-Za-z_0-9]*", condition) else None
                if answer is None or len(regions) > 2 or len(regions) == 2 and "condition" in regions[1]:
                    failures.append(f'{source["source"]}: conditional predicate grammar requires compiler-specific proof')
                else:
                    for index, region in enumerate(regions):
                        expected_state = "active" if (answer if index == 0 else not answer) else "inactive"
                        predicate_checks.append({"condition": condition if index == 0 else "else", "expectedState": expected_state, "recordedState": region["state"]})
                        if region["state"] != expected_state: failures.append(f'{source["source"]}: active source region differs from actual job')
                item["predicateProof"] = {"kind": "finite-actual-target-and-Swift-definition-evaluation", "checks": predicate_checks,
                                          "scope": "one-simple-if-group-with-optional-else-only"}
        records.append(item)
    return {"passed": not failures, "failures": failures, "sources": records, "generatedInputs": generated,
            "scope": "one-actual-build-configuration-only", "configurationUnionCertified": False,
            "requiredProof": "Compiler-evaluated predicates and all required Debug/Release/device/simulator variants remain independent obligations"}


def collect(manifest_path: Path, log: Path, output: Path, modules: set[str]) -> dict:
    project = coverage.ROOT.resolve(); output = output.resolve()
    if not output.is_relative_to(project / ".local-private"):
        raise ValueError("Evidence must be written under this project's .local-private")
    output.mkdir(parents=True, exist_ok=False)
    collector_before = artifact(Path(__file__))
    manifest_before = artifact(manifest_path)
    manifest = json.loads(manifest_path.read_text())
    if manifest.get("registryDigest") != coverage.digest(coverage.registry_payload(manifest)):
        raise ValueError("Manifest digest mismatch")
    for source in manifest["sources"]:
        for key, checksum in (("source", "sourceSHA256"), ("transformed", "transformedSHA256"), ("ast", "astSHA256")):
            if coverage.sha(Path(source[key])) != source[checksum]: raise ValueError("Stale source or prepared artifact")
        config = source.get("configuration") or {}
        if config.get("typingHints"):
            proof = config.get("typingHintsProof", {})
            original_ast = proof.get("originalTypedAST", {})
            ast_path = Path(original_ast.get("path", ""))
            if not ast_path.is_file() or coverage.sha(ast_path) != original_ast.get("sha256"):
                raise ValueError("Typed original proof artifact changed")
            actual = any_result_hints(source, json.loads(ast_path.read_text()), ast_path)
            if actual != proof or config["typingHints"] != actual["typingHints"]:
                raise ValueError("Typed rewrite hint differs from original compiler resolution")
    snapshot = output / "compile-log.txt"; snapshot.write_bytes(log.read_bytes())
    jobs = read_jobs(snapshot, project / "ios", modules)
    coverage.write(output / "jobs.json", jobs)
    configuration = audit_configuration(manifest, jobs)
    coverage.write(output / "configuration-audit.json", configuration)
    compiler_before = artifact(COMPILER.resolve())
    version = subprocess.run([str(COMPILER), "--version"], check=True, capture_output=True, text=True).stdout
    records = []; obligations = []; failures = list(configuration["failures"])
    for job in jobs:
        module = output / job["module"]; module.mkdir()
        record = {"module": job["module"], "argumentsDigest": job["argumentsDigest"], "variants": {}}
        ast_variants = {}; sil_variants = {}
        for variant in ("original", "transformed"):
            directory = module / variant; directory.mkdir()
            overlay = manifest_path.parent / "overlay.json" if variant == "transformed" else None
            arguments, changes = replay_arguments(job, directory, overlay)
            variant_record = {"adjustments": changes, "actions": {}}
            for action, suffix in (("-dump-ast", "ast"), ("-emit-silgen", "sil")):
                trace = directory / (suffix + "-loaded-modules.json")
                # Swift explicitly rejects loaded-module tracing with -dump-ast.
                # The matching SIL job supplies real dependency trace evidence.
                analysis_flags = []
                if suffix == "sil": analysis_flags = ["-emit-loaded-module-trace-path", str(trace), "-Xllvm", "-sil-print-debuginfo"]
                invocation = execute([*arguments, *analysis_flags, action], Path(job["cwd"]), directory / (suffix + ".txt"), directory / (suffix + ".stderr"))
                variant_record["actions"][suffix] = invocation
                if invocation["exit"]:
                    failures.append(f'{job["module"]}/{variant}/{suffix}: compiler exited {invocation["exit"]}')
                    continue
                text, raw_artifact = compiler_output(invocation, suffix)
                parsed = typed_nodes(text) if suffix == "ast" else sil_summary(text)
                variant_record["actions"][suffix]["rawAnalysis"] = raw_artifact
                if suffix == "sil" and trace.is_file():
                    module_trace = json.loads(trace.read_text())
                    variant_record["actions"][suffix]["loadedModuleTrace"] = artifact(trace)
                    variant_record["actions"][suffix]["loadedSwiftModuleInputs"] = [artifact(Path(path)) for path in sorted(set(module_trace["swiftmodules"]))]
                elif suffix == "sil": failures.append(f'{job["module"]}/{variant}/{suffix}: missing loaded module trace')
                coverage.write(directory / (suffix + "-index.json"), parsed)
                variant_record["actions"][suffix]["index"] = artifact(directory / (suffix + "-index.json"))
                if suffix == "ast": ast_variants[variant] = parsed
                else: sil_variants[variant] = sil_locations(text)
            record["variants"][variant] = variant_record
        records.append(record)
        if "original" in ast_variants:
            for source in manifest["sources"]:
                if source["source"] in job["sources"]:
                    hints = (source.get("configuration") or {}).get("typingHints", [])
                    if hints:
                        actual_hints = any_result_hints(source, ast_variants["original"], module / "original/ast-index.json")["typingHints"]
                        if hints != actual_hints: failures.append("Rewrite hints differ from this exact active original compiler job")
                    sites = compare_typed_sites(source, ast_variants["original"], ast_variants.get("transformed", []))
                    add_sil_comparisons(source, sites, sil_variants.get("original", []), sil_variants.get("transformed", []))
                    obligations.extend(sites)
    for source in manifest["sources"]:
        for key, checksum in (("source", "sourceSHA256"), ("transformed", "transformedSHA256"), ("ast", "astSHA256")):
            if coverage.sha(Path(source[key])) != source[checksum]: failures.append("Source/prepared artifact changed during evidence capture")
    if artifact(COMPILER.resolve()) != compiler_before: failures.append("Compiler binary changed during capture")
    if artifact(Path(__file__)) != collector_before: failures.append("Collector changed during capture")
    if artifact(manifest_path) != manifest_before: failures.append("Manifest changed during capture")
    for item in configuration["generatedInputs"]:
        if coverage.sha(Path(item["path"])) != item["sha256"]: failures.append("Generated build input changed during capture")
    expected_sites = set(manifest["registry"])
    if {item["site"] for item in obligations} != expected_sites: failures.append("Incomplete original typed site inventory")
    if any(item["status"].startswith("blocked") for item in obligations): failures.append("Missing typed mapping or unsupported family")
    # This is an obligation packet, not a self-issued certificate. No boolean
    # approval field supplied in a JSON file can change this status.
    report = {"schemaVersion": 1, "status": "evidence-collected-pending-independent-proof",
              "applicationGateApproved": False, "semanticEquivalenceCertified": False,
              "registryDigest": manifest["registryDigest"], "manifest": manifest_before,
              "compileLog": artifact(snapshot), "originalCompileLogPath": str(log), "collector": collector_before,
              "compiler": {**compiler_before, "version": version},
              "jobs": artifact(output / "jobs.json"), "configurationAudit": artifact(output / "configuration-audit.json"),
              "failures": failures, "modules": records, "siteObligations": obligations,
              "classifications": [item for source in manifest["sources"] for item in source["classificationInventory"]],
              "unsupported": [item for source in manifest["sources"] for item in source["unsupported"]],
              "candidateOutcomes": {site: {outcome: 0 for outcome in outcomes} for site, outcomes in manifest["registry"].items()},
              "counterStatus": "denominator-only-zero-initialization-no-runtime-hits-collected",
              "remainingProofs": ["every-concrete-transformation-original-vs-transformed-typed-AST-and-SIL",
                                  "all-invariant-and-ownership-classifications", "all-required-active-configurations",
                                  "independent-review-bound-to-exact-evidence", "loaded-module-and-runtime-measurements"]}
    coverage.write(output / "evidence.json", report)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--compile-log", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--module", action="append", required=True)
    args = parser.parse_args()
    try:
        report = collect(args.manifest.resolve(), args.compile_log.resolve(), args.output, set(args.module))
        print(json.dumps({"status": report["status"], "failures": report["failures"], "sites": len(report["siteObligations"]),
                          "evidence": str((args.output / "evidence.json").resolve()), "applicationGateApproved": False}, indent=2))
        return 0 if not report["failures"] else 1
    except (ValueError, OSError, KeyError, subprocess.SubprocessError) as error:
        print(f"Compiler evidence capture blocked: {error}")
        return 2


if __name__ == "__main__": raise SystemExit(main())
