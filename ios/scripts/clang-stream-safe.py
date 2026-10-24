#!/usr/bin/env python3
"""Preserve real Clang output while avoiding sequential-reader pipe deadlock.

Only the macro-discovery query is buffered. Production compilation executes the
unmodified Xcode compiler directly, with all original arguments and environment.
No compiler output is parsed, truncated, synthesized or reordered within a stream.
"""
from pathlib import Path
import os
import signal
import subprocess
import sys
import tempfile


def main() -> int:
    developer = Path(os.environ.get("DEVELOPER_DIR", "/Applications/Xcode.app/Contents/Developer"))
    compiler_name = "clang++" if "++" in Path(sys.argv[0]).name else "clang"
    compiler = developer / "Toolchains/XcodeDefault.xctoolchain/usr/bin" / compiler_name
    arguments = sys.argv[1:]
    command = [str(compiler), *arguments]
    if "-E" not in arguments or "-dM" not in arguments:
        os.execv(str(compiler), command)
    # SwiftBuild can read stdout to EOF before it drains stderr. Close stdout
    # after its complete bytes so a small global pipe pool cannot deadlock it.
    with tempfile.TemporaryFile() as stdout, tempfile.TemporaryFile() as stderr:
        result = subprocess.run(command, stdout=stdout, stderr=stderr)
        for stream, descriptor in [(stdout, 1), (stderr, 2)]:
            stream.seek(0)
            while chunk := stream.read(64 * 1024):
                offset = 0
                while offset < len(chunk):
                    offset += os.write(descriptor, chunk[offset:])
            os.close(descriptor)
        if result.returncode < 0:
            if -result.returncode not in (signal.SIGKILL, signal.SIGSTOP):
                signal.signal(-result.returncode, signal.SIG_DFL)
            os.kill(os.getpid(), -result.returncode)
        return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
