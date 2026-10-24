#!/usr/bin/env python3
"""Guard the iOS bridge contracts whose runtime dependencies are unavailable to host tests."""

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
SOURCE = (ROOT / "ios/TranslatePDF/PdfTranslatorModule.swift").read_text(encoding="utf-8")
STORE = (ROOT / "ios/TranslatePDF/TranslatorStore.swift").read_text(encoding="utf-8")
BRIDGE = (ROOT / "ios/TranslatePDF/PdfTranslatorBridge.m").read_text(encoding="utf-8")


def function_body(signature: str, next_signature: str) -> str:
    start = SOURCE.index(signature)
    end = SOURCE.index(next_signature, start)
    return SOURCE[start:end]


warnings = function_body(
    "private func normalizedWarnings(",
    "private func mergeWarnings(",
)
assert "value as? [[String:Any]]" in warnings, "native warning objects should remain structured"
assert '["code":"DOCX_UNSUPPORTED_CONTENT","message":$0]' in warnings, (
    "native string warnings must be normalized to the stable code/message object shape"
)

export = function_body(
    "private func buildDocxExport(",
    "private func processJob(",
)
assert re.search(r'check\["segmentCount"\][^\n]*>\s*0', export), (
    "DOCX export verification should require readable text segments"
)
assert not re.search(r'check\["segmentCount"\][^\n]*(?:==|!=)\s*expectedSegments', export), (
    "translated paragraphs may expand or contract; exported segment count must not equal source count"
)

assert '@objc func setJobColor(' in SOURCE and 'self.store.setJobColor(jobId,color:color)' in SOURCE, (
    "the iOS bridge should expose the native terminal-job color update"
)
assert 'RCT_EXTERN_METHOD(setJobColor:' in BRIDGE, "the Objective-C bridge should export setJobColor"
assert '"labelColor":r["label_color"] ?? NSNull()' in STORE, "job snapshots should expose nullable labelColor"
assert '"appRating":appPreferences["appRating"]' in SOURCE and '"librarySort":appPreferences["librarySort"]' in SOURCE, (
    "bootstrap and settings snapshots should expose local app rating and library sort"
)
assert 'self.store.transaction {' in SOURCE and 'number.doubleValue.rounded(.towardZero)==number.doubleValue' in SOURCE, (
    "settings should validate integer ratings before atomically writing preferences"
)

print("PASS iOS DOCX, labels, and app-preference source contracts")
