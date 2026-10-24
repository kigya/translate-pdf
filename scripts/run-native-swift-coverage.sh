#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output_dir="${1:-$root/.local-private/build/native-swift-coverage}"
mkdir -p "$output_dir"

swiftc -profile-generate -profile-coverage-mapping \
  "$root/ios/TranslatePDF/ModelCatalog.swift" \
  "$root/ios/TranslatePDF/ModelDownloadCoordinator.swift" \
  "$root/ios/TranslatePDF/TranslatorStore.swift" \
  "$root/ios/TranslatePDF/MyMemoryTranslator.swift" \
  "$root/ios/tests/TranslatorStorePipelineTest.swift" \
  -o "$output_dir/store-tests"

profile_dir="$(mktemp -d "$output_dir/run-XXXXXXXX")"
export LLVM_PROFILE_FILE="$profile_dir/store-tests-%p.profraw"
"$output_dir/store-tests"
xcrun llvm-profdata merge -sparse "$profile_dir"/store-tests-*.profraw \
  -o "$output_dir/store-tests.profdata"
xcrun llvm-cov export "$output_dir/store-tests" \
  -instr-profile="$output_dir/store-tests.profdata" \
  > "$output_dir/store-tests-llvm.json"
printf 'Swift LLVM coverage export: %s\n' "$output_dir/store-tests-llvm.json"
