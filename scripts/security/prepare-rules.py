#!/usr/bin/env python3
"""Fetch a pinned rules snapshot for internal source analysis, never redistribute it."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import tarfile
import urllib.request

REVISION = '16b9e6d45f10649f6a531ec4838ed9924372c3bc'
SHA256 = '098703252fbff4c04186e7b6dd06f9a6a03fb548778341056a945e099ec2a824'
URL = f'https://codeload.github.com/semgrep/semgrep-rules/tar.gz/{REVISION}'
MAX_ARCHIVE = 8 * 1024 * 1024


def prepare(destination: Path, archive: bytes) -> dict:
    if len(archive) > MAX_ARCHIVE or hashlib.sha256(archive).hexdigest() != SHA256:
        raise ValueError('Security rules archive does not match the pinned SHA-256')
    selected = []
    license_text = None
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as source:
        for member in source.getmembers():
            path = PurePosixPath(member.name)
            parts = path.parts[1:]
            if member.isfile() and parts == ('LICENSE',):
                payload = source.extractfile(member)
                if payload is not None and member.size <= 1024 * 1024:
                    license_text = payload.read()
                continue
            if not member.isfile() or len(parts) < 2:
                continue
            if parts[0] not in ('javascript', 'typescript') or 'security' not in parts or path.suffix != '.yaml':
                continue
            if '..' in parts or path.is_absolute() or member.size > 1024 * 1024:
                raise ValueError('Unsafe security rules archive member')
            payload = source.extractfile(member)
            if payload is None:
                raise ValueError('Missing security rule content')
            selected.append((Path(*parts), payload.read()))
    if len(selected) != 177 or license_text is None:
        raise ValueError('Unexpected security rules inventory')
    destination.mkdir(parents=True, exist_ok=False)
    (destination / 'LICENSE').write_bytes(license_text)
    files = {}
    for relative, data in selected:
        output = destination / relative
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
        files[relative.as_posix()] = hashlib.sha256(data).hexdigest()
    receipt = {'revision': REVISION, 'archive_sha256': SHA256,
               'license_sha256': hashlib.sha256(license_text).hexdigest(), 'rule_files': files}
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--archive', type=Path, help='Use an already downloaded archive; the same hash check applies')
    args = parser.parse_args()
    if args.archive:
        archive = args.archive.read_bytes()
    else:
        with urllib.request.urlopen(URL, timeout=30) as response:
            archive = response.read(MAX_ARCHIVE + 1)
    receipt = prepare(args.destination, archive)
    (args.destination.parent / 'rules-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(f"Prepared {len(receipt['rule_files'])} pinned security rules")


if __name__ == '__main__':
    main()
