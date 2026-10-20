#!/usr/bin/env bash
set -uo pipefail

ROOT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

usage() {
  cat <<'EOF'
Usage: bash scripts/check-local.sh <javascript|android|ios|native|secrets|publication>

Runs the same bounded project checks used by main-only CI. Android/iOS release
builds are unsigned for verification (Android uses the project's test debug
key). Set BASE_REF for the publication screen when checking a local branch.
EOF
}

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    printf 'Required command is unavailable: %s\n' "$1" >&2
    return 1
  fi
}

install_js_dependencies() {
  require_command node && require_command npm || return 1
  local node_major
  node_major="$(node -p 'process.versions.node.split(".")[0]')"
  if [[ "$node_major" != 22 ]]; then
    printf 'Node.js 22 is required; found %s.\n' "$(node --version)" >&2
    return 1
  fi
  npm ci
}

check_javascript() {
  install_js_dependencies || return 1
  npm run typecheck || return 1
  npm run lint || return 1
  npm test -- --runInBand --coverage
}

check_android() {
  require_command java && require_command sdkmanager || return 1
  local java_major
  java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p')"
  if [[ "$java_major" != 21 ]]; then
    printf 'JDK 21 is required; found %s.\n' "${java_major:-unknown}" >&2
    return 1
  fi
  install_js_dependencies || return 1
  python3 scripts/setup-native.py --platforms android-arm64 || return 1
  export NODE_BINARY="$(command -v node)"

  local result=0
  android/gradlew -p android --no-daemon --stacktrace :app:check || result=1
  android/gradlew -p android --no-daemon --stacktrace :app:lintRelease || result=1
  android/gradlew -p android --no-daemon --stacktrace :app:assembleRelease || result=1
  return "$result"
}

check_ios() {
  [[ "$(uname -s)" == Darwin ]] || {
    printf 'The unsigned iOS Release build requires macOS and Xcode.\n' >&2
    return 1
  }
  require_command xcodebuild && require_command pod || return 1
  export DEVELOPER_DIR="$(xcode-select -p)"
  install_js_dependencies || return 1
  python3 scripts/setup-native.py --platforms ios-device-arm64 ios-simulator-arm64 || return 1
  ios/scripts/build-native-frameworks.sh || return 1
  ios/scripts/sync-license-resources.sh || return 1
  pod install --project-directory=ios --deployment || return 1
  ios/scripts/test-store-pipeline.sh || return 1

  local result=0
  xcodebuild \
    -workspace ios/TranslatePDF.xcworkspace \
    -scheme TranslatePDF \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "$ROOT_DIR/.local-private/build/ios-release" \
    CODE_SIGNING_ALLOWED=NO \
    analyze || result=1
  xcodebuild \
    -workspace ios/TranslatePDF.xcworkspace \
    -scheme TranslatePDF \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -archivePath "$ROOT_DIR/ios/build/TranslatePDF.xcarchive" \
    CODE_SIGNING_ALLOWED=NO \
    archive || result=1
  return "$result"
}

check_native() {
  [[ "$(uname -s)" == Darwin ]] || {
    printf 'The host PDFium regression suite requires the pinned macOS PDFium binary.\n' >&2
    return 1
  }
  require_command cmake && require_command ctest && require_command xcrun || return 1
  python3 scripts/setup-native.py --platforms mac-arm64 || return 1

  local build_dir="$ROOT_DIR/.local-private/build/native-host"
  cmake -S native -B "$build_dir" \
    -DCMAKE_BUILD_TYPE=Release \
    -DTRANSLATEPDF_HOST_COVERAGE=ON || return 1
  cmake --build "$build_dir" --parallel "$(sysctl -n hw.ncpu)" || return 1
  export TRANSLATEPDF_NATIVE_SMOKE="$build_dir/native-smoke"
  export DYLD_LIBRARY_PATH="$ROOT_DIR/.local-private/deps/pdfium/mac-arm64/lib${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
  export LLVM_PROFILE_FILE="$build_dir/coverage/%p.profraw"
  mkdir -p "$build_dir/coverage"

  local result=0
  ctest --test-dir "$build_dir" --output-on-failure || result=1
  python3 -m unittest discover -s native/tests -v || result=1
  local profiles=("$build_dir"/coverage/*.profraw)
  if [[ ! -f "${profiles[0]}" ]]; then
    printf 'LLVM coverage profiles were not produced.\n' >&2
    return 1
  fi
  xcrun llvm-profdata merge -sparse "${profiles[@]}" -o "$build_dir/coverage/coverage.profdata" || result=1
  xcrun llvm-cov report "$build_dir/native-smoke" \
    -instr-profile="$build_dir/coverage/coverage.profdata" \
    -ignore-filename-regex='(llama\.cpp|pdfium|\.local-private)' \
    > "$build_dir/coverage/llvm-cov-report.txt" || result=1
  cat "$build_dir/coverage/llvm-cov-report.txt"
  xcrun llvm-cov export "$build_dir/native-smoke" \
    -instr-profile="$build_dir/coverage/coverage.profdata" \
    -ignore-filename-regex='(llama\.cpp|pdfium|\.local-private)' \
    > "$build_dir/coverage/llvm-cov-export.json" || result=1
  if ! python3 scripts/check-native-coverage.py \
      --owned-sources scripts/native-owned-sources.txt \
      --llvm-json "$build_dir/coverage/llvm-cov-export.json" \
      --report "$build_dir/coverage/native-coverage-gate.json"; then
    result=1
  fi
  return "$result"
}

gitleaks_binary() {
  local os arch archive expected
  os="$(uname -s)"
  arch="$(uname -m)"
  if [[ "$os" == Linux && "$arch" == x86_64 ]]; then
    archive=gitleaks_8.30.1_linux_x64.tar.gz
    expected=551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb
  elif [[ "$os" == Darwin && "$arch" == arm64 ]]; then
    archive=gitleaks_8.30.1_darwin_arm64.tar.gz
    expected=b40ab0ae55c505963e365f271a8d3846efbc170aa17f2607f13df610a9aeb6a5
  else
    printf 'No verified Gitleaks v8.30.1 binary is configured for %s/%s.\n' "$os" "$arch" >&2
    return 1
  fi
  require_command curl && require_command tar || return 1
  local tool_dir="$ROOT_DIR/.local-private/tools/gitleaks-v8.30.1"
  mkdir -p "$tool_dir"
  if [[ ! -x "$tool_dir/gitleaks" ]]; then
    local archive_path="$tool_dir/$archive"
    curl --fail --location --retry 3 --silent --show-error \
      "https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/$archive" \
      --output "$archive_path" || return 1
    local actual
    if [[ "$os" == Darwin ]]; then
      actual="$(shasum -a 256 "$archive_path" | awk '{print $1}')"
    else
      actual="$(sha256sum "$archive_path" | awk '{print $1}')"
    fi
    if [[ "$actual" != "$expected" ]]; then
      printf 'Gitleaks archive SHA-256 mismatch.\n' >&2
      return 1
    fi
    tar -xzf "$archive_path" -C "$tool_dir" gitleaks || return 1
  fi
  printf '%s\n' "$tool_dir/gitleaks"
}

check_secrets() {
  local binary
  binary="$(gitleaks_binary)" || return 1
  "$binary" git "$ROOT_DIR" --redact --config "$ROOT_DIR/.gitleaks.toml" --exit-code 1
}

check_publication() {
  require_command python3 || return 1
  local base="${BASE_REF:-}"
  if [[ -z "$base" ]]; then
    if git rev-parse --verify --quiet origin/main >/dev/null; then
      base=origin/main
    elif git rev-parse --verify --quiet main >/dev/null; then
      base=main
    elif git rev-parse --verify --quiet HEAD^ >/dev/null; then
      base=HEAD^
    else
      printf 'Set BASE_REF to the branch or commit this change will merge into.\n' >&2
      return 1
    fi
  fi
  local head="${HEAD_REF:-HEAD}"
  if [[ -n "${HEAD_REF:-}" ]]; then
    python3 scripts/check-publication.py --base-ref "$base" --head-ref "$head"
    return $?
  fi

  local -a paths=()
  local path
  local merge_base
  if merge_base="$(git merge-base "$base" HEAD 2>/dev/null)"; then
    while IFS= read -r path; do [[ -n "$path" ]] && paths+=("$path"); done < <(git diff --name-only --diff-filter=ACMRT "$merge_base" HEAD)
  else
    printf 'No merge base between %s and HEAD; scanning worktree, staged, and untracked paths only.\n' "$base" >&2
  fi
  while IFS= read -r path; do [[ -n "$path" ]] && paths+=("$path"); done < <(git diff --name-only --diff-filter=ACMRT)
  while IFS= read -r path; do [[ -n "$path" ]] && paths+=("$path"); done < <(git diff --cached --name-only --diff-filter=ACMRT)
  while IFS= read -r path; do [[ -n "$path" ]] && paths+=("$path"); done < <(git ls-files --others --exclude-standard)
  if [[ "${#paths[@]}" -eq 0 ]]; then
    printf 'No changed public-candidate files found against %s.\n' "$base"
    return 0
  fi
  python3 scripts/check-publication.py --paths "${paths[@]}"
}

case "${1:-}" in
  javascript) check_javascript ;;
  android) check_android ;;
  ios) check_ios ;;
  native) check_native ;;
  secrets) check_secrets ;;
  publication) check_publication ;;
  -h|--help|help) usage ;;
  *) usage >&2; exit 2 ;;
esac
