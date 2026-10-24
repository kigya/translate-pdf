#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"
SCAN_DIR="$ROOT_DIR/.local-private/security-source"
mkdir -p "$SCAN_DIR"
export SEMGREP_SETTINGS_FILE="$SCAN_DIR/settings.yml"
RULES_DIR="$(mktemp -d "$SCAN_DIR/run-XXXXXXXX")/rules"
python3 scripts/security/prepare-rules.py "$RULES_DIR"
cp "$(dirname "$RULES_DIR")/rules-receipt.json" "$SCAN_DIR/rules-receipt.json"
.local-private/security-tools/bin/semgrep scan \
  --oss-only --metrics off --disable-version-check --no-git-ignore \
  --jobs 2 --max-memory 2048 --timeout 15 --error --strict \
  --config "$RULES_DIR" --json --output "$SCAN_DIR/semgrep.json" \
  App.tsx app src index.js
