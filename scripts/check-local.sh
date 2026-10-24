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

select_xcode_developer_dir() {
  local candidate
  local -a candidates=()

  if [[ -n "${DEVELOPER_DIR:-}" ]]; then
    candidates+=("$DEVELOPER_DIR")
  else
    candidate="$(/usr/bin/xcode-select -p 2>/dev/null || true)"
    [[ -z "$candidate" ]] || candidates+=("$candidate")
    local app
    for app in /Applications/Xcode*.app; do
      [[ -d "$app/Contents/Developer" ]] && candidates+=("$app/Contents/Developer")
    done
  fi

  for candidate in "${candidates[@]}"; do
    [[ -x "$candidate/usr/bin/xcodebuild" ]] || continue
    [[ -d "$candidate/Platforms/iPhoneOS.platform" ]] || continue
    [[ -d "$candidate/Platforms/iPhoneSimulator.platform" ]] || continue
    if ! DEVELOPER_DIR="$candidate" "$candidate/usr/bin/xcodebuild" -version >/dev/null 2>&1; then
      continue
    fi
    export DEVELOPER_DIR="$candidate"
    export PATH="$DEVELOPER_DIR/usr/bin:$PATH"
    printf 'Using full Xcode at %s\n' "$DEVELOPER_DIR"
    return 0
  done

  printf 'A full Xcode installation with iOS and iOS Simulator SDKs is required. Set DEVELOPER_DIR to its Contents/Developer directory.\n' >&2
  return 1
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
  local java_home
  java_home="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p')"
  [[ -d "$java_home" ]] || { printf 'Cannot resolve the checked JDK home.\n' >&2; return 1; }
  install_js_dependencies || return 1
  python3 scripts/setup-native.py --platforms android-arm64 || return 1
  export NODE_BINARY="$(command -v node)"

  local result=0
  android/gradlew -p android --no-daemon "-Dorg.gradle.java.home=$java_home" --stacktrace :app:check || result=1
  android/gradlew -p android --no-daemon "-Dorg.gradle.java.home=$java_home" --stacktrace :app:lintRelease || result=1
  android/gradlew -p android --no-daemon "-Dorg.gradle.java.home=$java_home" --stacktrace :app:assembleRelease || result=1
  return "$result"
}

check_ios() {
  [[ "$(uname -s)" == Darwin ]] || {
    printf 'The unsigned iOS Release build requires macOS and Xcode.\n' >&2
    return 1
  }
  select_xcode_developer_dir || return 1
  require_command xcodebuild || return 1
  if ! command -v cmake >/dev/null 2>&1; then
    local verified_cmake="$ROOT_DIR/.local-private/tools/cmake-3.31.12/cmake-3.31.12-macos-universal/CMake.app/Contents/bin/cmake"
    if [[ -x "$verified_cmake" ]]; then
      export CMAKE_BIN="$verified_cmake"
    else
      printf 'CMake is required to build the pinned iOS native frameworks.\n' >&2
      return 1
    fi
  fi
  bash ios/scripts/check-clang-discovery.sh || return 1
  install_js_dependencies || return 1
  python3 scripts/setup-native.py --platforms ios-device-arm64 ios-simulator-arm64 || return 1
  ios/scripts/build-native-frameworks.sh || return 1
  ios/scripts/sync-license-resources.sh || return 1
  if command -v pod >/dev/null 2>&1; then
    pod install --project-directory=ios --deployment || return 1
  elif cmp -s ios/Podfile.lock ios/Pods/Manifest.lock; then
    printf 'CocoaPods is unavailable; using the installed Pods tree matching Podfile.lock.\n'
  else
    printf 'CocoaPods is unavailable and the installed Pods tree does not match Podfile.lock.\n' >&2
    return 1
  fi
  ios/scripts/test-store-pipeline.sh || return 1

  local result=0
  xcodebuild \
    -workspace ios/TranslatePDF.xcworkspace \
    -scheme TranslatePDF \
    -jobs 2 \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -derivedDataPath "$ROOT_DIR/.local-private/build/ios-release" \
    CODE_SIGNING_ALLOWED=NO \
    analyze || result=1
  xcodebuild \
    -workspace ios/TranslatePDF.xcworkspace \
    -scheme TranslatePDF \
    -jobs 2 \
    -configuration Release \
    -destination 'generic/platform=iOS' \
    -archivePath "$ROOT_DIR/ios/build/TranslatePDF.xcarchive" \
    ARCHS=arm64 \
    CODE_SIGNING_ALLOWED=NO \
    archive || result=1
  xcodebuild \
    -workspace ios/TranslatePDF.xcworkspace \
    -scheme TranslatePDF \
    -jobs 2 \
    -configuration Release \
    -destination 'generic/platform=iOS Simulator' \
    -derivedDataPath "$ROOT_DIR/.local-private/build/ios-simulator-release" \
    ARCHS=arm64 \
    CODE_SIGNING_ALLOWED=NO \
    build || result=1

  local coverage_dir="$ROOT_DIR/.local-private/build/ios-native-coverage"
  local coverage_derived="$coverage_dir/DerivedData"
  local coverage_result="$coverage_dir/NativeCoverage.xcresult"
  rm -rf "$coverage_derived" "$coverage_result"
  mkdir -p "$coverage_dir"
  local simulator_destination="${IOS_SIMULATOR_DESTINATION:-platform=iOS Simulator,name=iPhone 17 Pro}"
  local coverage_log="$coverage_dir/test.log"
  if ! xcodebuild \
      -workspace ios/TranslatePDF.xcworkspace \
      -scheme TranslatePDF \
      -jobs 1 \
      -configuration Debug \
      -destination "$simulator_destination" \
      -derivedDataPath "$coverage_derived" \
      -resultBundlePath "$coverage_result" \
      -enableCodeCoverage YES \
      -parallel-testing-enabled NO \
      -maximum-concurrent-test-simulator-destinations 1 \
      CODE_SIGNING_ALLOWED=NO \
      test > "$coverage_log" 2>&1; then
    tail -100 "$coverage_log" >&2
    result=1
  else
    local coverage_profile="$coverage_dir/Coverage.profdata"
    local coverage_binary="$coverage_derived/Build/Products/Debug-iphonesimulator/TranslatePDF.app/TranslatePDF.debug.dylib"
    local coverage_test_binary="$coverage_derived/Build/Products/Debug-iphonesimulator/TranslatePDF.app/PlugIns/TranslatePDFNativeCoverageTests.xctest/TranslatePDFNativeCoverageTests"
    local llvm_json="$coverage_dir/ios-native-coverage-llvm.json"
    local xccov_json="$coverage_dir/ios-xccov-report.json"
    local -a profile_candidates=("$coverage_derived"/Build/ProfileData/*/Coverage.profdata)
    if [[ ! -f "${profile_candidates[0]}" || ! -f "$coverage_binary" || ! -f "$coverage_test_binary" ]]; then
      printf 'XCTest coverage profile or instrumented app/test binary is missing.\n' >&2
      result=1
    else
      cp "${profile_candidates[0]}" "$coverage_profile" || result=1
      xcrun llvm-cov export "$coverage_binary" \
        -object "$coverage_test_binary" \
        -instr-profile="$coverage_profile" \
        > "$llvm_json" || result=1
      xcrun xccov view --report --json "$coverage_result" > "$xccov_json" || result=1
    fi
  fi
  return "$result"
}

check_native() {
  [[ "$(uname -s)" == Darwin ]] || {
    printf 'The host PDFium regression suite requires the pinned macOS PDFium binary.\n' >&2
    return 1
  }
  require_command cmake && require_command ctest && require_command xcrun && require_command swiftc || return 1
  # These real model paths are required by the owned profile branches. Omitting
  # them silently skips the model contracts and cannot satisfy strict coverage.
  python3 scripts/setup-native.py --platforms mac-arm64 --models q4 gemma hy7 || return 1
  export TRANSLATEPDF_TEST_MODEL="$ROOT_DIR/.local-private/models/Hy-MT2-1.8B-Q4_K_M.gguf"
  export TRANSLATEPDF_TEST_GEMMA_MODEL="$ROOT_DIR/.local-private/models/translategemma-4b-it-Q4_K_M.gguf"
  export TRANSLATEPDF_TEST_HY7_MODEL="$ROOT_DIR/.local-private/models/Hy-MT2-7B-Q4_K_M.gguf"

  local build_dir="$ROOT_DIR/.local-private/build/native-host"
  cmake -S native -B "$build_dir" \
    -DCMAKE_BUILD_TYPE=Release \
    -DTRANSLATEPDF_HOST_COVERAGE=ON || return 1
  cmake --build "$build_dir" --parallel 2 || return 1
  export TRANSLATEPDF_NATIVE_SMOKE="$build_dir/native-smoke"
  export DYLD_LIBRARY_PATH="$ROOT_DIR/.local-private/deps/pdfium/mac-arm64/lib${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
  mkdir -p "$build_dir/coverage"
  local profile_dir
  profile_dir="$(mktemp -d "$build_dir/coverage/run-XXXXXXXX")" || return 1
  export LLVM_PROFILE_FILE="$profile_dir/%p.profraw"

  local result=0
  ctest --test-dir "$build_dir" --output-on-failure || result=1
  # Native host integration files use the repository's `*_test.py` naming;
  # unittest's default `test*.py` pattern silently discovers zero of them.
  python3 -m unittest discover -s native/tests -p '*_test.py' -v || result=1
  local profiles=("$profile_dir"/*.profraw)
  if [[ ! -f "${profiles[0]}" ]]; then
    printf 'LLVM coverage profiles were not produced.\n' >&2
    return 1
  fi
  xcrun llvm-profdata merge -sparse "${profiles[@]}" -o "$build_dir/coverage/coverage.profdata" || result=1
  xcrun llvm-cov report "$build_dir/native-smoke" \
    --object "$build_dir/translation-guard-test" \
    --object "$build_dir/jni-bridge-test" \
    --object "$build_dir/docx-fault-test" \
    -instr-profile="$build_dir/coverage/coverage.profdata" \
    -ignore-filename-regex='(llama\.cpp|pdfium|\.local-private)' \
    > "$build_dir/coverage/llvm-cov-report.txt" || result=1
  cat "$build_dir/coverage/llvm-cov-report.txt"
  xcrun llvm-cov export "$build_dir/native-smoke" \
    --object "$build_dir/translation-guard-test" \
    --object "$build_dir/jni-bridge-test" \
    --object "$build_dir/docx-fault-test" \
    -instr-profile="$build_dir/coverage/coverage.profdata" \
    -ignore-filename-regex='(llama\.cpp|pdfium|\.local-private)' \
    > "$build_dir/coverage/llvm-cov-export.json" || result=1
  bash scripts/run-native-swift-coverage.sh "$build_dir/swift-coverage" || result=1
  local coverage_args=(
    --owned-sources scripts/native-owned-sources.txt
    --llvm-json "$build_dir/coverage/llvm-cov-export.json"
    --report "$build_dir/coverage/native-coverage-gate.json"
  )
  local ios_coverage_dir="$ROOT_DIR/.local-private/build/ios-native-coverage"
  local ios_llvm_json="${TRANSLATEPDF_IOS_LLVM_JSON:-$ios_coverage_dir/ios-native-coverage-llvm.json}"
  local xccov_json="${TRANSLATEPDF_IOS_XCCOV_JSON:-$ios_coverage_dir/ios-xccov-report.json}"
  if [[ ! -f "$ios_llvm_json" && -f "$ROOT_DIR/.local-private/ios-build-current/ios-native-coverage-llvm.json" ]]; then
    ios_llvm_json="$ROOT_DIR/.local-private/ios-build-current/ios-native-coverage-llvm.json"
  fi
  if [[ ! -f "$xccov_json" && -f "$ROOT_DIR/.local-private/ios-build-current/ios-xccov-report.json" ]]; then
    xccov_json="$ROOT_DIR/.local-private/ios-build-current/ios-xccov-report.json"
  fi
  if [[ -f "$ios_llvm_json" ]]; then
    coverage_args+=(--llvm-json "$ios_llvm_json")
  else
    # The standalone host Swift test export is useful while XCTest coverage is
    # unavailable; its branch evidence remains missing and fails closed.
    coverage_args+=(--llvm-json "$build_dir/swift-coverage/store-tests-llvm.json")
  fi
  if [[ -f "$xccov_json" ]]; then
    coverage_args+=(--xccov-json "$xccov_json")
  fi
  local jacoco_xml="${TRANSLATEPDF_JACOCO_XML:-$ROOT_DIR/android/app/build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml}"
  if [[ -f "$jacoco_xml" ]]; then
    coverage_args+=(--jacoco-xml "$jacoco_xml")
  fi
  if ! python3 scripts/check-native-coverage.py \
      "${coverage_args[@]}"; then
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
