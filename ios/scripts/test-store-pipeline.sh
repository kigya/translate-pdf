#!/bin/sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
IOS_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
PROJECT_DIR=$(CDPATH= cd -- "$IOS_DIR/.." && pwd)
TEMP_DIR=$(mktemp -d "${TMPDIR:-/tmp}/translatepdf-store-tests.XXXXXX")
trap 'rm -rf "$TEMP_DIR"' EXIT HUP INT TERM

xcrun swiftc \
  "$IOS_DIR/TranslatePDF/ModelCatalog.swift" \
  "$IOS_DIR/TranslatePDF/ModelDownloadCoordinator.swift" \
  "$IOS_DIR/TranslatePDF/TranslatorStore.swift" \
  "$IOS_DIR/TranslatePDF/MyMemoryTranslator.swift" \
  "$IOS_DIR/tests/TranslatorStorePipelineTest.swift" \
  -o "$TEMP_DIR/translator-store-pipeline-tests"

"$TEMP_DIR/translator-store-pipeline-tests"
python3 "$IOS_DIR/tests/docx_module_contract_test.py"
