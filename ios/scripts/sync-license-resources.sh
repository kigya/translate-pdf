#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE="$ROOT/licenses"
DEST="$ROOT/ios/TranslateCore/Licenses"

rm -rf "$DEST"
mkdir -p "$DEST"
while IFS= read -r source_file; do
  relative="${source_file#"$SOURCE"/}"
  resource_name="${relative//\//__}"
  cp "$source_file" "$DEST/$resource_name"
done < <(find "$SOURCE" -type f -print | LC_ALL=C sort)

printf 'Synced %s license files into %s\n' "$(find "$DEST" -type f | wc -l | tr -d ' ')" "$DEST"
