"""Finite original typed-AST evidence for optional receiver reads.

The dump format is pinned by real compiler tests, not a general Swift AST API.
Unknown lvalue accessors fail closed. In particular, absence of assignment syntax
does not imply that a selected method/getter is nonmutating.
"""
from pathlib import Path


def derive(source: Path, nodes: list[dict], requests: list[dict]) -> list[dict]:
    raw = source.read_bytes(); lines = raw.splitlines(keepends=True)
    offsets = []; offset = 0
    for line in lines: offsets.append(offset); offset += len(line)
    def point(area, prefix):
        line, column = area[prefix+"Line"], area[prefix+"Column"]
        if not 1 <= line <= len(lines) or not 1 <= column <= len(lines[line-1])+1: return None
        return offsets[line-1]+column-1
    ancestry = {}; stack = []
    for index,node in enumerate(nodes):
        while stack and nodes[stack[-1]]["indent"] >= node["indent"]: stack.pop()
        ancestry[index] = stack.copy(); stack.append(index)
    classes = {n["interface_type"][:-5] for n in nodes if n["kind"] == "class_decl" and n.get("interface_type", "").endswith(".Type")}
    result = []
    for request in requests:
        facts = []
        for index,node in enumerate(nodes):
            area = node.get("range")
            if node["kind"] != "bind_optional_expr" or not area or Path(area["source"]).resolve() != source.resolve(): continue
            if (point(area,"start"),point(area,"end")) != (request["startUTF8"],request["endUTF8"]-1): continue
            parents = [nodes[i] for i in ancestry[index]]
            # Swift retains unresolved original initializer syntax beside its
            # processed typed initializer. Only the latter supplies resolution.
            if any(n["header"].startswith("(original_init=") for n in parents): continue
            receiver = node.get("type", ""); selected = None; rule = None; selected_type = ""
            # An applied dot-syntax declref explicitly describes the selected
            # self convention. A mutating value method carries inout self.
            dot = next((i for i in reversed(ancestry[index]) if nodes[i]["kind"] == "dot_syntax_call_expr"
                        and nodes[i].get("range") and point(nodes[i]["range"],"start") <= request["startUTF8"]
                        and point(nodes[i]["range"],"end") >= request["endUTF8"]-1),None)
            if dot is not None:
                candidates = [n for n in nodes[dot+1:index] if n["kind"] in {"declref_expr","member_ref_expr"}
                              and "function_ref=single" in n["header"] and n.get("decl")]
                if candidates:
                    target = candidates[0]
                    if target.get("type", "").startswith("(") and "inout" not in target["type"]:
                        selected=target["decl"]; selected_type=target["type"]; rule="selected-noninout-self"
                    else: continue
            if selected is None:
                references = [n for n in reversed(parents) if n.get("decl")]
                if not references:
                    for child in nodes[index+1:]:
                        if child["indent"] <= node["indent"]: break
                        if child.get("decl"): references=[child]; break
                if references: selected=references[0]["decl"];selected_type=references[0].get("type", "")
                if receiver and not receiver.startswith("@lvalue "):
                    rule="typed-rvalue-receiver"
                elif parents and parents[-1]["kind"] == "load_expr":
                    rule="explicit-receiver-load"
                elif parents and parents[-1]["kind"] == "tuple_element_expr":
                    rule="builtin-tuple-element-read"
                elif receiver.removeprefix("@lvalue ") in classes:
                    rule="declared-reference-receiver"
            if selected and rule:
                facts.append({**request,"selectedDeclaration":selected,"selectedType":selected_type,"receiverType":receiver,"rule":rule})
        unique = {tuple(sorted(f.items())) for f in facts}
        if len(unique) == 1: result.append(dict(next(iter(unique))))
    return result
