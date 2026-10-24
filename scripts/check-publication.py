#!/usr/bin/env python3
"""Bounded pre-publication checks for changed repository content.

This is a read-only screen, not a complete secret scanner or security audit.
It never executes content from the repository. Git is invoked with argument
arrays and shell=False only to read staged blobs or requested refs.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath

MAX_TEXT_BYTES = 20 * 1024 * 1024

PRIVATE_PATH_PARTS = {
    ".local-private",
    ".p-build-app",
    ".codex",
    ".serena",
    ".playwright-mcp",
    "coverage",
    "agent-reports",
}
PRIVATE_PATH_PREFIXES = ("docs/research/", "docs/quality/", "docs/agent-reports/")
FORBIDDEN_SUFFIXES = {
    ".aab", ".apk", ".jks", ".keystore", ".p12", ".pfx", ".pem",
    ".gguf", ".safetensors", ".onnx", ".tflite", ".pt", ".pth",
}
FORBIDDEN_NAMES = {
    ".env", ".env.local", "credentials.json", "service-account.json",
    "google-services.json", "google-services.plist",
}
SECRET_PATTERNS = (
    ("private key material", re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
    ("AWS access key", re.compile(rb"\bAKIA[0-9A-Z]{16}\b")),
    ("GitHub access token", re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{30,})\b")),
    ("Google API key", re.compile(rb"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("local user filesystem path", re.compile(rb"(?:/Users|/home)/[^\s\"'<>]+")),
)
EXTERNAL_POLICY_RESOURCE = re.compile(
    rb"<(?:script|iframe|img|link)\b[^>]*(?:src|href)\s*=\s*['\"]https?://",
    re.IGNORECASE,
)


@dataclass(frozen=True)
class Finding:
    path: str
    reason: str


def relative_path(raw: str) -> str:
    path = PurePosixPath(raw.replace("\\", "/"))
    if path.is_absolute() or ".." in path.parts:
        raise ValueError(f"Path must stay inside the repository: {raw}")
    return path.as_posix()


def inspect_blob(path: str, data: bytes) -> list[Finding]:
    """Inspect one public-candidate blob; exposed for focused in-memory tests."""
    normalized = relative_path(path)
    parts = set(PurePosixPath(normalized).parts)
    findings: list[Finding] = []
    lower_name = PurePosixPath(normalized).name.lower()

    if parts & PRIVATE_PATH_PARTS or normalized.startswith(PRIVATE_PATH_PREFIXES):
        findings.append(Finding(normalized, "private research, evidence, cache, or local workflow path"))
    if lower_name in FORBIDDEN_NAMES or lower_name.startswith(".env."):
        findings.append(Finding(normalized, "credential or local-environment file"))
    if PurePosixPath(lower_name).suffix in FORBIDDEN_SUFFIXES:
        findings.append(Finding(normalized, "private build output, signing material, or model weight"))
    if len(data) > MAX_TEXT_BYTES:
        findings.append(Finding(normalized, f"file exceeds {MAX_TEXT_BYTES // (1024 * 1024)} MiB publication-screen limit"))
        return findings

    # There is deliberately no English-only prose check: localized copy may
    # use any language. Secret, private-path, asset, and size checks still run
    # on localized files exactly as they do on other content.
    for description, pattern in SECRET_PATTERNS:
        if pattern.search(data):
            findings.append(Finding(normalized, f"possible {description}"))

    if normalized == "privacy/index.html":
        if re.search(rb"<\s*script\b", data, re.IGNORECASE):
            findings.append(Finding(normalized, "privacy page must not execute JavaScript"))
        if EXTERNAL_POLICY_RESOURCE.search(data):
            findings.append(Finding(normalized, "privacy page must not load external page resources"))

    return findings


def run_git(*args: str) -> bytes:
    result = subprocess.run(
        ["git", *args], check=False, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, shell=False,
    )
    if result.returncode:
        message = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(message or f"git {' '.join(args)} failed")
    return result.stdout


def changed_paths(raw: bytes) -> list[str]:
    fields = raw.decode("utf-8", errors="surrogateescape").split("\0")
    if fields and fields[-1] == "":
        fields.pop()
    result: list[str] = []
    index = 0
    while index < len(fields):
        status = fields[index]
        index += 1
        if status.startswith(("R", "C")):
            index += 1  # old name; inspect the destination below
        if index >= len(fields):
            raise ValueError("Could not parse Git's NUL-delimited changed-file list")
        path = fields[index]
        index += 1
        if not status.startswith("D"):
            result.append(relative_path(path))
    return result


def read_staged(path: str) -> bytes:
    return read_git_blob(f":{path}")


def read_ref(ref: str, path: str) -> bytes:
    return read_git_blob(f"{ref}:{path}")


def read_git_blob(spec: str) -> bytes:
    size = int(run_git("cat-file", "-s", spec).decode("ascii").strip())
    if size > MAX_TEXT_BYTES:
        return b" " * (MAX_TEXT_BYTES + 1)
    return run_git("show", spec)


def staged_symlink(path: str) -> bool:
    records = run_git("ls-files", "--stage", "-z", "--", path).split(b"\0")
    return any(record.split(b"\t", 1)[0].split(b" ", 1)[0] == b"120000" for record in records if record)


def ref_symlink(ref: str, path: str) -> bool:
    records = run_git("ls-tree", "-z", ref, "--", path).split(b"\0")
    return any(record.split(b"\t", 1)[0].split(b" ", 1)[0] == b"120000" for record in records if record)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--staged", action="store_true", help="inspect non-deleted files in the Git index")
    modes.add_argument("--paths", nargs="+", help="inspect these working-tree files without changing them")
    modes.add_argument("--base-ref", help="inspect changed files between two Git refs")
    parser.add_argument("--head-ref", help="head ref required with --base-ref")
    args = parser.parse_args(argv)
    if bool(args.base_ref) != bool(args.head_ref):
        parser.error("--base-ref and --head-ref must be used together")

    try:
        blobs: list[tuple[str, bytes]] = []
        if args.staged:
            names = changed_paths(run_git("diff", "--cached", "--name-status", "-z"))
            for name in names:
                if staged_symlink(name):
                    raise ValueError(f"Refusing to publish symlink: {name}")
                blobs.append((name, read_staged(name)))
        elif args.base_ref:
            names = changed_paths(run_git("diff", "--name-status", "-z", args.base_ref, args.head_ref))
            for name in names:
                if ref_symlink(args.head_ref, name):
                    raise ValueError(f"Refusing to publish symlink: {name}")
                blobs.append((name, read_ref(args.head_ref, name)))
        else:
            for raw_path in args.paths:
                name = relative_path(raw_path)
                root = Path.cwd().resolve()
                candidate = root / name
                resolved = candidate.resolve(strict=True)
                if not resolved.is_relative_to(root):
                    raise ValueError(f"Refusing to read outside repository: {name}")
                if candidate.is_symlink():
                    raise ValueError(f"Refusing to follow symlink: {name}")
                if not candidate.is_file():
                    raise ValueError(f"Not a regular file: {name}")
                size = candidate.stat().st_size
                if size > MAX_TEXT_BYTES:
                    blobs.append((name, b" " * (MAX_TEXT_BYTES + 1)))
                else:
                    blobs.append((name, candidate.read_bytes()))

        findings = [item for path, data in blobs for item in inspect_blob(path, data)]
    except (OSError, RuntimeError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    if findings:
        for finding in findings:
            print(f"BLOCK {finding.path}: {finding.reason}")
        print(f"Publication screen blocked: {len(findings)} finding(s) across {len(blobs)} file(s).")
        return 1
    print(
        f"Publication screen passed for {len(blobs)} file(s). "
        "This bounded check does not replace secret scanning, license review, or security review."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
