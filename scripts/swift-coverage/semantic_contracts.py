#!/usr/bin/env python3
"""Fail-closed local proof rules over actual compiler AST/SIL.

These rules establish specific helper/value facts from compiler lowering. They
cannot certify an application, approve an independent review or manufacture hits.
"""
from __future__ import annotations
from dataclasses import dataclass
from pathlib import Path
import re


def outside_quotes(text,replace):
    """Rewrite lexical metadata/tokens without interpreting literal contents."""
    result=[];start=0;index=0
    while index<len(text):
        if text[index]!='"':index+=1;continue
        result.append(replace(text[start:index]));end=index+1;escaped=False
        while end<len(text):
            char=text[end]
            if char=='"' and not escaped:break
            if char=='\\' and not escaped:escaped=True
            else:escaped=False
            end+=1
        if end==len(text):raise ValueError('Unclosed SIL quoted literal')
        result.append(text[index:end+1]);index=end+1;start=index
    result.append(replace(text[start:]));return ''.join(result)


def strip_metadata(line):
    boundary=[];position=0
    for index,part in enumerate(re.split(r'("(?:[^"\\]|\\.)*")',line)):
        if not index%2:
            match=re.search(r', loc |, scope \d+| //',part)
            if match and not boundary:boundary.append(position+match.start())
        position+=len(part)
    return line[:boundary[0] if boundary else len(line)].strip()


@dataclass
class SILFunction:
    symbol: str
    header: str
    lines: list[str]
    start_line: int
    conflicting_duplicates: bool = False


def functions(text: str) -> dict[str, SILFunction]:
    result = {}; current = None
    for number, line in enumerate(text.splitlines(),1):
        match = re.match(r"sil .*?@([^ ]+) : .+\{$",line)
        if match:
            if current is not None: raise ValueError("Nested/unclosed SIL function")
            current = SILFunction(match[1],line,[],number); continue
        if current is not None:
            if line.startswith("} // end sil function"):
                previous = result.get(current.symbol)
                if previous is not None:
                    # Multi-primary compiler dumps repeat imported definitions. Keep
                    # ambiguity explicit; any proof reaching this symbol fails closed.
                    def normalized(value):
                        return [strip_metadata(line)
                                for line in [value.header,*value.lines] if line.strip() and not line.lstrip().startswith("//")]
                    if normalized(previous) != normalized(current): previous.conflicting_duplicates = True
                else: result[current.symbol] = current
                current = None
            else: current.lines.append(line)
    if current is not None or not result: raise ValueError("Incomplete/empty SIL definition inventory")
    return result


def instructions(function: SILFunction) -> list[dict]:
    if function.conflicting_duplicates: raise ValueError("Conflicting repeated SIL definition: " + function.symbol)
    result = []; block = None
    for offset,line in enumerate(function.lines,1):
        match = re.match(r"(bb\d+)(?:\([^\n]*\))?:",line)
        if match: block = match[1]; continue
        if line.lstrip().startswith("//") or not line.strip(): continue
        stripped = strip_metadata(line)
        match = re.match(r"(?:((?:%\d+|\(%\d+(?:, %\d+)+\))) = )?([a-z_]+)(?: (.*))?$",stripped)
        if not match: raise ValueError("Unrecognized SIL instruction in proof kernel: " + stripped)
        result.append({"block":block,"result":match[1],"opcode":match[2],"operands":match[3] or "",
                       "line":function.start_line+offset,"raw":stripped})
    if not result or any(item["block"] is None for item in result): raise ValueError("Missing SIL basic block")
    return result


def unique_function(inventory: dict[str,SILFunction], module: str, helper: str) -> SILFunction:
    prefix = f"$s{len(module)}{module}7__FBCovO{len(helper)}{helper}"
    matches = [value for symbol,value in inventory.items() if symbol.startswith(prefix) and "6$defer" not in symbol]
    if len(matches) != 1: raise ValueError("Missing/ambiguous exact recorder helper definition: " + helper)
    return matches[0]


def refs(items: list[dict]) -> dict[str,str]:
    result = {}
    for item in items:
        if item["opcode"] == "function_ref":
            match = re.match(r"@([^ ]+) :",item["operands"])
            if not match or not item["result"]: raise ValueError("Malformed SIL function reference")
            result[item["result"]] = match[1]
    return result


def apply_target(item: dict, references: dict[str,str]) -> str:
    match = re.match(r"(%\d+)(?:<.*>)?\(",item["operands"])
    if not match or match[1] not in references: raise ValueError("Indirect/unresolved call in identity kernel")
    return references[match[1]]


def observer_separation(inventory: dict[str,SILFunction], module: str) -> dict:
    """Check a closed recorder-only call graph; never erase an owned callback.

    The kernel receives only literal site/outcome Strings, not the observed
    payload. Its reachable project symbols must stay in the reserved generated
    recorder namespace. Only Swift standard-library calls and NSLock operations
    cross that boundary. Application declarations must separately be checked for
    reserved-name collisions before this result can discharge a site obligation.
    """
    failures = []; root = unique_function(inventory,module,"record")
    if "(@guaranteed String, @guaranteed String, @thin __FBCov.Type) -> ()" not in root.header:
        failures.append("Recorder signature is not two Strings and metatype only")
    own_prefix = f"$s{len(module)}{module}"
    def recorder_namespace(symbol):
        return symbol.startswith((own_prefix+'7__FBCovO',own_prefix+'12__fbRegistry'))
    pending = [root.symbol]; visited = set(); external = set(); globals_seen = set()
    while pending:
        symbol = pending.pop()
        if symbol in visited: continue
        visited.add(symbol)
        function = inventory.get(symbol)
        if function is None:
            failures.append("Missing reachable recorder definition: " + symbol); continue
        body=instructions(function);direct=refs(body)
        locks={item['result'] for item in body if item['opcode']=='objc_method' and re.search(r'#NSLock\.(?:lock|unlock)!foreign',item['operands'])}
        for item in body:
            if item['opcode'] in {'apply','begin_apply','try_apply'}:
                target=re.match(r'(%\d+)(?:<.*>)?\(',item['operands'])
                if not target or target[1] not in set(direct)|locks:
                    failures.append('Unresolved indirect invocation in recorder graph')
            if item["opcode"] in {"witness_method","class_method","dynamic_method","super_method","dynamic_function_ref","previous_dynamic_function_ref","try_apply","throw"}:
                failures.append("Unresolved dynamic/throwing callback in recorder graph: " + item["raw"])
            if item["opcode"] == "objc_method" and not re.search(r"#NSLock\.(?:lock|unlock)!foreign",item["operands"]):
                failures.append("Non-NSLock Objective-C dispatch in recorder graph")
            if item["opcode"] == "global_addr":
                match = re.search(r"@([^ ]+)",item["operands"])
                if not match or not recorder_namespace(match[1]):
                    failures.append("Recorder accesses a global outside its reserved state")
                else: globals_seen.add(match[1])
            if item["opcode"] != "function_ref": continue
            target = re.match(r"@([^ ]+) :",item["operands"])
            if not target: failures.append("Malformed observer function reference"); continue
            target = target[1]
            if target.startswith(own_prefix):
                if not recorder_namespace(target):
                    failures.append("Recorder calls an application symbol: " + target)
                else: pending.append(target)
            elif target.startswith(("$sS","$ss","$sSo6NSLockC")):
                external.add(target)
            else: failures.append("Unapproved external observer call: " + target)
    return {"proved":not failures,"failures":failures,"rootSymbol":root.symbol,"reachableRecorderDefinitions":sorted(visited),
            "externalStandardLibraryOrNSLockCalls":sorted(external),"privateGlobals":sorted(globals_seen),
            "conclusion":"The checked direct project function/global references stay within exact generated recorder mangling components",
            "scope":"Symbol-reference graph only; complete pointer/effect/ownership noninterference is a separate unproved obligation",
            "preconditions":["original owned sources contain no reserved __FBCov or __fb identifiers",
                             "observed call site supplies compiler-verified literal Strings",
                             "Swift standard library and the platform NSLock implement their documented semantics"],
            "notProved":"Normal-return/resource behavior and complete per-site ownership/evaluation equivalence remain separate obligations"}


# These finite programs describe the transfer rule, not a digest whitelist. Every
# non-debug operation, operand, ownership annotation, basic-block parameter and
# edge must match. SSA identifiers may be renamed; instructions may not be added
# or reordered. Unknown compiler lowering remains an unproved obligation.
_BOOL_PROGRAM = r'''
bb0(%0 : @guaranteed $String, %1 : $Bool, %2 : $@thin __FBCov.Type):
%7 = struct_extract %1, #Bool._value
cond_br %7, bb1, bb2
bb1:
%10 = string_literal utf8 "true"
%11 = integer_literal $Builtin.Word, 4
%12 = integer_literal $Builtin.Int1, -1
%13 = metatype $@thin String.Type
%14 = function_ref @$sSS21_builtinStringLiteral17utf8CodeUnitCount7isASCIISSBp_BwBi1_tcfC : $@convention(method) (Builtin.RawPointer, Builtin.Word, Builtin.Int1, @thin String.Type) -> @owned String
%15 = apply %14(%10, %11, %12, %13) : $@convention(method) (Builtin.RawPointer, Builtin.Word, Builtin.Int1, @thin String.Type) -> @owned String
br bb3(%15)
bb2:
%17 = string_literal utf8 "false"
%18 = integer_literal $Builtin.Word, 5
%19 = integer_literal $Builtin.Int1, -1
%20 = metatype $@thin String.Type
%21 = function_ref @$sSS21_builtinStringLiteral17utf8CodeUnitCount7isASCIISSBp_BwBi1_tcfC : $@convention(method) (Builtin.RawPointer, Builtin.Word, Builtin.Int1, @thin String.Type) -> @owned String
%22 = apply %21(%17, %18, %19, %20) : $@convention(method) (Builtin.RawPointer, Builtin.Word, Builtin.Int1, @thin String.Type) -> @owned String
br bb3(%22)
bb3(%24 : @owned $String):
%25 = function_ref @RECORDER : $@convention(method) (@guaranteed String, @guaranteed String, @thin __FBCov.Type) -> ()
%26 = apply %25(%0, %24, %2) : $@convention(method) (@guaranteed String, @guaranteed String, @thin __FBCov.Type) -> ()
destroy_value %24
return %1
'''
_OPTIONAL_PROGRAM = r'''
bb0(%0 : $*Optional<T>, %1 : @guaranteed $String, %2 : $*Optional<T>, %3 : $@thin __FBCov.Type):
%8 = metatype $@thin Optional<T>.Type
%9 = metatype $@thin _OptionalNilComparisonType.Type
%10 = function_ref @$ss26_OptionalNilComparisonTypeV10nilLiteralAByt_tcfC : $@convention(method) (@thin _OptionalNilComparisonType.Type) -> _OptionalNilComparisonType
%11 = apply %10(%9) : $@convention(method) (@thin _OptionalNilComparisonType.Type) -> _OptionalNilComparisonType
%12 = function_ref @$sSq2eeoiySbxSg_s26_OptionalNilComparisonTypeVtFZ : $@convention(method) <τ_0_0 where τ_0_0 : ~Copyable, τ_0_0 : ~Escapable> (@in_guaranteed Optional<τ_0_0>, _OptionalNilComparisonType, @thin Optional<τ_0_0>.Type) -> Bool
%13 = apply %12<T>(%2, %11, %8) : $@convention(method) <τ_0_0 where τ_0_0 : ~Copyable, τ_0_0 : ~Escapable> (@in_guaranteed Optional<τ_0_0>, _OptionalNilComparisonType, @thin Optional<τ_0_0>.Type) -> Bool
%14 = struct_extract %13, #Bool._value
cond_br %14, bb1, bb2
bb1:
%17 = string_literal utf8 "nil"
%18 = integer_literal $Builtin.Word, 3
%19 = integer_literal $Builtin.Int1, -1
%20 = metatype $@thin String.Type
%21 = function_ref @$sSS21_builtinStringLiteral17utf8CodeUnitCount7isASCIISSBp_BwBi1_tcfC : $@convention(method) (Builtin.RawPointer, Builtin.Word, Builtin.Int1, @thin String.Type) -> @owned String
%22 = apply %21(%17, %18, %19, %20) : $@convention(method) (Builtin.RawPointer, Builtin.Word, Builtin.Int1, @thin String.Type) -> @owned String
br bb3(%22)
bb2:
%24 = string_literal utf8 "some"
%25 = integer_literal $Builtin.Word, 4
%26 = integer_literal $Builtin.Int1, -1
%27 = metatype $@thin String.Type
%28 = function_ref @$sSS21_builtinStringLiteral17utf8CodeUnitCount7isASCIISSBp_BwBi1_tcfC : $@convention(method) (Builtin.RawPointer, Builtin.Word, Builtin.Int1, @thin String.Type) -> @owned String
%29 = apply %28(%24, %25, %26, %27) : $@convention(method) (Builtin.RawPointer, Builtin.Word, Builtin.Int1, @thin String.Type) -> @owned String
br bb3(%29)
bb3(%31 : @owned $String):
%32 = function_ref @RECORDER : $@convention(method) (@guaranteed String, @guaranteed String, @thin __FBCov.Type) -> ()
%33 = apply %32(%1, %31, %3) : $@convention(method) (@guaranteed String, @guaranteed String, @thin __FBCov.Type) -> ()
destroy_value %31
copy_addr %2 to [init] %0
%36 = tuple ()
return %36
'''


def transfer_program(lines: list[str], recorder_symbol: str) -> list[str]:
    names = {}; result = []
    for line in lines:
        line = strip_metadata(line)
        if not line or line.startswith(("//", "debug_value ", "increment_profiler_counter ")): continue
        line = re.sub(r", scope \d+$", "", line)
        line = outside_quotes(line,lambda part:part.replace("@"+recorder_symbol+" :", "@RECORDER :"))
        def rename(match):
            value = match[0]
            if value not in names: names[value] = "%v"+str(len(names))
            return names[value]
        result.append(outside_quotes(line,lambda part:re.sub(r"%\d+", rename, part)))
    return result


def exact_transfer_rule(inventory: dict[str,SILFunction], module: str, helper: str) -> dict:
    """Prove the finite SSA/CFG transfer, retaining observer/site obligations."""
    if helper not in {"boolean","optional","attempt","selected"}: raise ValueError("No transfer rule for helper")
    function = unique_function(inventory,module,helper)
    record = unique_function(inventory,module,"record")
    # instructions() rejects conflicting repeated definitions and unknown syntax.
    instructions(function); instructions(record)
    signatures={
      "boolean":"(@guaranteed String, Bool, @thin __FBCov.Type) -> Bool",
      "optional":"(@guaranteed String, @in_guaranteed Optional<T>, @thin __FBCov.Type) -> @out Optional<T>",
      "selected":"(@guaranteed String, @guaranteed String, @thin __FBCov.Type) -> ()",
      "attempt":"(@guaranteed String, @guaranteed @noescape @callee_guaranteed @substituted <τ_0_0> () -> (@out τ_0_0, @error any Error) for <T>, @thin __FBCov.Type) -> (@out T, @error any Error)"}
    signature=signatures[helper]
    failures = []
    expected_header=f"sil [ossa] @{function.symbol} : $@convention(method) "+("<T> " if helper in {"optional","attempt"} else "")+signature+" {"
    if function.header not in {expected_header,expected_header.replace("sil [ossa]", "sil hidden [ossa]",1)}:failures.append("Unexpected complete helper ownership/type signature or attributes")
    counter_count={'boolean':2,'optional':2,'attempt':3,'selected':1}[helper]
    for item in instructions(function):
        if item['opcode']=='increment_profiler_counter' and not re.fullmatch(r'[0-'+str(counter_count-1)+r'], "'+re.escape(function.symbol)+r'", num_counters '+str(counter_count)+r', hash 0',item['operands']):
            failures.append("Profiler counter does not belong to this exact helper")
    if not record.header.endswith("(@guaranteed String, @guaranteed String, @thin __FBCov.Type) -> () {"):
        failures.append("Unexpected complete recorder ownership/type signature")
    template=(_OPTIONAL_PROGRAM if helper=="optional" else _BOOL_PROGRAM) if helper in {"optional","boolean"} else (Path(__file__).resolve().parent/'contracts'/(helper+'.transfer.sil')).read_text()
    expected = transfer_program(template.splitlines(), "RECORDER")
    actual = transfer_program(function.lines,record.symbol)
    if actual != expected: failures.append("Exact SSA/ownership/control-flow transfer precondition differs")
    return {"helper":helper,"symbol":function.symbol,"definitionLine":function.start_line,
            "proved":not failures,"failures":failures,
            "conclusion":({"boolean":"Returns the identical input Bool SSA value; its own Bool bit selects matching true/false literals",
               "optional":"Copies the unchanged input Optional to the return slot; exact nil comparison selects nil/some literals",
               "selected":"Passes the exact two guaranteed String inputs and metatype once into record, then returns Void",
               "attempt":"Invokes its noescape callback exactly once, copies the successful result unchanged or rethrows the identical error object, and records the corresponding literal outcome"}[helper]),
            "scope":"Finite helper body transfer only; site resolution, evaluation, ownership and observer behavior remain separate",
            "rule":{"optional":"optional-copy-through-v1","boolean":"bool-return-input-v1",
                    "attempt":"callback-once-result-error-transfer-v1","selected":"literal-delegation-v1"}[helper],
            "checkedInstructions":len(actual),"recorderSymbol":record.symbol,
            "preconditionsChecked":["exact ownership/type signature and compiler linkage attributes", "complete instruction inventory modulo debug/profiling",
              "every SSA operand, ownership operation, return/throw, basic-block parameter and control-flow edge in the finite transfer program",
              "exact recorder target, argument provenance and literal success/error or value outcomes where present"],
            "semanticEquivalenceCertified":False,"applicationGateApproved":False}
