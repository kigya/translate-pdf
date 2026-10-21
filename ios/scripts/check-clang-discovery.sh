#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname "$0")/../.." && pwd)"
DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export DEVELOPER_DIR
OUT_DIR="${IOS_COMPILER_PROBE_DIR:-$ROOT/.local-private/ios-compiler-probe}"
mkdir -p "$OUT_DIR"

for sdk in iphoneos iphonesimulator; do
  case "$sdk" in
    iphoneos)
      platform="iPhoneOS"
      sdk_name="iPhoneOS"
      target="arm64-apple-ios17.0"
      expected_platform_macro='^#define TARGET_OS_SIMULATOR 0$'
      ;;
    iphonesimulator)
      platform="iPhoneSimulator"
      sdk_name="iPhoneSimulator"
      target="arm64-apple-ios17.0-simulator"
      expected_platform_macro='^#define TARGET_OS_SIMULATOR 1$'
      ;;
  esac
  sdk_root="$DEVELOPER_DIR/Platforms/$platform.platform/Developer/SDKs/$sdk_name.sdk"
  clang="$DEVELOPER_DIR/Toolchains/XcodeDefault.xctoolchain/usr/bin/clang"
  [[ -x "$clang" && -d "$sdk_root" ]] || {
    printf 'Missing Xcode clang or SDK: %s / %s\n' "$clang" "$sdk_root" >&2
    exit 2
  }

  for language in c c++ objective-c objective-c++; do
    stdout="$OUT_DIR/$sdk-$language.stdout"
    stderr="$OUT_DIR/$sdk-$language.stderr"
    if "$clang" \
      -E -dM \
      -target "$target" \
      -isysroot "$sdk_root" \
      -x "$language" - \
      </dev/null >"$stdout" 2>"$stderr"; then
      :
    else
      status=$?
      printf 'Apple clang macro probe failed (exit %s): %s / %s\n' "$status" "$sdk" "$language" >&2
      cat "$stderr" >&2
      exit "$status"
    fi

    grep -q '^#define __clang__ 1$' "$stdout" || {
      printf 'Compiler output lacks the real __clang__ macro: %s / %s\n' "$sdk" "$language" >&2
      exit 1
    }
    grep -q '^#define __ENVIRONMENT_IPHONE_OS_VERSION_MIN_REQUIRED__ ' "$stdout" || {
      printf 'Compiler output lacks the iOS deployment target macro: %s / %s\n' "$sdk" "$language" >&2
      exit 1
    }
    grep -q "$expected_platform_macro" "$stdout" || {
      printf 'Compiler output lacks the expected SDK platform macro: %s / %s\n' "$sdk" "$language" >&2
      exit 1
    }
    if [[ "$language" == objective-c || "$language" == objective-c++ ]]; then
      grep -q '^#define __OBJC__ 1$' "$stdout" || {
        printf 'Compiler output lacks the Objective-C language macro: %s / %s\n' "$sdk" "$language" >&2
        exit 1
      }
    fi
  done
done

printf 'Full compiler macro output was captured for 8 C/Objective-C probes under %s (stdout/stderr files, no pipes).\n' "$OUT_DIR"
