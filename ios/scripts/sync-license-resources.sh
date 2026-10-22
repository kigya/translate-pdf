#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
python3 - "$ROOT" <<'PY'
import json
from pathlib import Path, PurePosixPath
import shutil
import sys
import tempfile

root = Path(sys.argv[1]).resolve()
source = root / 'licenses'
dest = root / 'ios/TranslateCore/Licenses'
font_source = root / 'assets/fonts'
font_dest = root / 'ios/TranslateCore/Resources/fonts'
catalog = json.loads((source / 'catalog.json').read_text(encoding='utf-8'))
if catalog.get('version') != 1 or not isinstance(catalog.get('notices'), list) or not catalog['notices']:
    raise ValueError('Invalid or empty license catalog')
files = {}
identifiers = set()
for notice in catalog['notices']:
    if not isinstance(notice, dict) or not isinstance(notice.get('id'), str) or not notice['id'] or notice['id'] in identifiers:
        raise ValueError('Invalid or duplicate license identifier')
    identifiers.add(notice['id'])
    if not isinstance(notice.get('title'), str) or not notice['title']:
        raise ValueError('Missing license title')
    platforms = notice.get('platforms')
    paths = notice.get('files')
    if not isinstance(platforms, list) or not platforms or any(p not in ('android', 'ios') for p in platforms):
        raise ValueError('Invalid license platform')
    if not isinstance(paths, list) or not paths:
        raise ValueError('Missing license files')
    for relative in paths:
        if not isinstance(relative, str) or not relative or '\\' in relative or any(p in ('', '.', '..') for p in relative.split('/')) or PurePosixPath(relative).is_absolute():
            raise ValueError('Unsafe license path')
        path = (source / relative).resolve()
        if not path.is_relative_to(source.resolve()) or not path.is_file():
            raise ValueError('Missing or escaped license file')
        if 'ios' in platforms:
            name = relative.replace('/', '__')
            if name == 'catalog.json' or (name in files and files[name] != path):
                raise ValueError('Colliding license resource names')
            files[name] = path
fonts = [font_source / name for name in ('NotoSans-Regular.ttf', 'NotoSansSC-Regular.ttf')]
if any(not path.is_file() for path in fonts):
    raise ValueError('Missing bundled font')
# Validate all inputs before changing existing resources, including parse failures.
dest.parent.mkdir(parents=True, exist_ok=True)
staging = Path(tempfile.mkdtemp(prefix='.licenses-', dir=dest.parent))
try:
    shutil.copyfile(source / 'catalog.json', staging / 'catalog.json')
    for name, path in files.items():
        shutil.copyfile(path, staging / name)
    if dest.exists():
        shutil.rmtree(dest)
    staging.rename(dest)
finally:
    if staging.exists():
        shutil.rmtree(staging)
font_dest.mkdir(parents=True, exist_ok=True)
for path in fonts:
    shutil.copyfile(path, font_dest / path.name)
print(f'Synced {len(files)} license files and {len(fonts)} bundled fonts')
PY
