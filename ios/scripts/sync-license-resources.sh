#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SOURCE="$ROOT/licenses"
DEST="$ROOT/ios/TranslateCore/Licenses"
FONT_SOURCE="$ROOT/assets/fonts"
FONT_DEST="$ROOT/ios/TranslateCore/Resources/fonts"

rm -rf "$DEST"
mkdir -p "$DEST"
while IFS= read -r source_file; do
  relative="${source_file#"$SOURCE"/}"
  resource_name="${relative//\//__}"
  cp "$source_file" "$DEST/$resource_name"
done < <(find "$SOURCE" -type f -print | LC_ALL=C sort)

mkdir -p "$FONT_DEST"
for font in NotoSans-Regular.ttf NotoSansSC-Regular.ttf; do
  [[ -f "$FONT_SOURCE/$font" ]] || { echo "Missing bundled font: $FONT_SOURCE/$font" >&2; exit 2; }
  rm -f "$FONT_DEST/$font"
  cp "$FONT_SOURCE/$font" "$FONT_DEST/$font"
done

printf 'Synced %s license files into %s and copied %s bundled fonts into %s\n' \
  "$(find "$DEST" -type f | wc -l | tr -d ' ')" "$DEST" \
  "$(find "$FONT_DEST" -maxdepth 1 -type f | wc -l | tr -d ' ')" "$FONT_DEST"
