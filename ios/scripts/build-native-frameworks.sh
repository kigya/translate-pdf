#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IOS_DIR="$ROOT/ios"
FRAMEWORKS="$IOS_DIR/Frameworks"
DEVELOPER_DIR="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
export DEVELOPER_DIR
CMAKE_BIN="${CMAKE_BIN:-$(command -v cmake || true)}"
if [[ -z "$CMAKE_BIN" ]]; then
  ANDROID_SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$ANDROID_SDK_ROOT" ]]; then
    CMAKE_BIN="$(find "$ANDROID_SDK_ROOT/cmake" -mindepth 3 -maxdepth 3 -type f -path '*/bin/cmake' -print 2>/dev/null | sort -V | tail -n 1 || true)"
  fi
fi
if [[ -z "$CMAKE_BIN" || ! -x "$CMAKE_BIN" ]]; then
  echo "CMake is required. Set CMAKE_BIN to a local CMake 3.22+ executable." >&2
  exit 2
fi
if [[ ! -d "$DEVELOPER_DIR/Platforms/iPhoneSimulator.platform" || ! -d "$DEVELOPER_DIR/Platforms/iPhoneOS.platform" ]]; then
  echo "Set DEVELOPER_DIR to a full Xcode installation containing iOS SDKs." >&2
  exit 2
fi

mkdir -p "$FRAMEWORKS"
BUILD_ROOT="${IOS_BUILD_DIR:-$ROOT/.local-private/ios-framework-build-unix}"
mkdir -p "$BUILD_ROOT"

build_one() {
  local sdk="$1" variant="$2" out="$3"
  local build="$BUILD_ROOT/$variant"
  mkdir -p "$build"
  local toolchain="$DEVELOPER_DIR/Toolchains/XcodeDefault.xctoolchain/usr/bin"
  "$CMAKE_BIN" -S "$ROOT/native" -B "$build" -G "Unix Makefiles" \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT="$sdk" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 \
    -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY \
    -DCMAKE_C_COMPILER="$toolchain/clang" \
    -DCMAKE_CXX_COMPILER="$toolchain/clang++" \
    -DCMAKE_MAKE_PROGRAM=/usr/bin/make \
    -DPDFium_LIBRARY="$ROOT/.local-private/deps/pdfium/$variant/lib/libpdfium.dylib" \
    -DPDFium_INCLUDE_DIR="$ROOT/.local-private/deps/pdfium/$variant/include" \
    -DCMAKE_BUILD_TYPE=Release > "$BUILD_ROOT/$variant-configure.log" 2>&1 || { tail -80 "$BUILD_ROOT/$variant-configure.log" >&2; exit 5; }
  "$CMAKE_BIN" --build "$build" --target translatepdf --parallel "${BUILD_JOBS:-4}" > "$BUILD_ROOT/$variant-build.log" 2>&1 || { tail -100 "$BUILD_ROOT/$variant-build.log" >&2; exit 6; }
  local core_archive
  core_archive="$(find "$build" -name libtranslatepdf.a -print -quit)"
  [[ -n "$core_archive" ]] || { echo "CMake did not produce libtranslatepdf.a for $variant." >&2; exit 4; }
  local archives=("$core_archive")
  while IFS= read -r file; do archives+=("$file"); done < <(find "$build/llama" -type f -name '*.a' | sort)
  mkdir -p "$(dirname "$out")"
  xcrun --sdk "$sdk" libtool -static -o "$out" "${archives[@]}"
}

make_static_framework() {
  local id="$1" device="$2" simulator="$3"
  local xcf="$FRAMEWORKS/$id.xcframework"
  rm -rf "$xcf"
  for tuple in "ios-arm64:$device" "ios-arm64-simulator:$simulator"; do
    local identifier="${tuple%%:*}" archive="${tuple#*:}"
    local fw="$xcf/$identifier/TranslateCore.framework"
    mkdir -p "$fw/Headers" "$fw/Modules"
    cp "$archive" "$fw/TranslateCore"
    cp "$ROOT/native/core.hpp" "$fw/Headers/core.hpp"
    cat > "$fw/Modules/module.modulemap" <<'MAP'
framework module TranslateCore {
  umbrella header "core.hpp"
  export *
  module * { export * }
}
MAP
    cat > "$fw/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>TranslateCore</string>
<key>CFBundleIdentifier</key><string>dev.kigya.translatepdf.TranslateCore</string>
<key>CFBundleName</key><string>TranslateCore</string>
<key>CFBundlePackageType</key><string>FMWK</string>
<key>CFBundleSupportedPlatforms</key><array><string>$(if [[ "$identifier" == *simulator* ]]; then echo iPhoneSimulator; else echo iPhoneOS; fi)</string></array>
<key>MinimumOSVersion</key><string>17.0</string>
<key>DTPlatformName</key><string>$(if [[ "$identifier" == *simulator* ]]; then echo iphonesimulator; else echo iphoneos; fi)</string>
</dict></plist>
PLIST
  done
  cat > "$xcf/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>AvailableLibraries</key><array>
<dict><key>LibraryIdentifier</key><string>ios-arm64</string><key>LibraryPath</key><string>TranslateCore.framework</string><key>SupportedArchitectures</key><array><string>arm64</string></array><key>SupportedPlatform</key><string>ios</string></dict>
<dict><key>LibraryIdentifier</key><string>ios-arm64-simulator</string><key>LibraryPath</key><string>TranslateCore.framework</string><key>SupportedArchitectures</key><array><string>arm64</string></array><key>SupportedPlatform</key><string>ios</string><key>SupportedPlatformVariant</key><string>simulator</string></dict>
</array><key>XCFrameworkFormatVersion</key><string>1.0</string><key>CFBundlePackageType</key><string>XFWK</string></dict></plist>
PLIST
}

make_pdfium_xcframework() {
  local xcf="$FRAMEWORKS/PDFium.xcframework"
  rm -rf "$xcf"
  for tuple in "ios-arm64:ios-device-arm64" "ios-arm64-simulator:ios-simulator-arm64"; do
    local identifier="${tuple%%:*}" platform="${tuple#*:}"
    local source="$ROOT/.local-private/deps/pdfium/$platform/lib/libpdfium.dylib"
    local fw="$xcf/$identifier/PDFium.framework"
    [[ -f "$source" ]] || { echo "Missing local PDFium binary: $source" >&2; exit 3; }
    mkdir -p "$fw/Headers" "$fw/Modules"
    cp "$source" "$fw/PDFium"
    /usr/bin/install_name_tool -id @rpath/PDFium.framework/PDFium "$fw/PDFium"
    cp "$ROOT/.local-private/deps/pdfium/$platform/include/fpdfview.h" "$fw/Headers/"
    cat > "$fw/Modules/module.modulemap" <<'MAP'
framework module PDFium {
  umbrella header "fpdfview.h"
  export *
  module * { export * }
}
MAP
    local os="iPhoneOS" dt="iphoneos"
    if [[ "$identifier" == *simulator* ]]; then os=iPhoneSimulator; dt=iphonesimulator; fi
    cat > "$fw/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>CFBundleExecutable</key><string>PDFium</string><key>CFBundleIdentifier</key><string>dev.kigya.translatepdf.PDFium</string><key>CFBundleName</key><string>PDFium</string><key>CFBundlePackageType</key><string>FMWK</string><key>CFBundleSupportedPlatforms</key><array><string>$os</string></array><key>MinimumOSVersion</key><string>17.0</string><key>DTPlatformName</key><string>$dt</string></dict></plist>
PLIST
  done
  cat > "$xcf/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>AvailableLibraries</key><array>
<dict><key>LibraryIdentifier</key><string>ios-arm64</string><key>LibraryPath</key><string>PDFium.framework</string><key>SupportedArchitectures</key><array><string>arm64</string></array><key>SupportedPlatform</key><string>ios</string></dict>
<dict><key>LibraryIdentifier</key><string>ios-arm64-simulator</string><key>LibraryPath</key><string>PDFium.framework</string><key>SupportedArchitectures</key><array><string>arm64</string></array><key>SupportedPlatform</key><string>ios</string><key>SupportedPlatformVariant</key><string>simulator</string></dict>
</array><key>XCFrameworkFormatVersion</key><string>1.0</string><key>CFBundlePackageType</key><string>XFWK</string></dict></plist>
PLIST
}

build_one iphoneos ios-device-arm64 "$BUILD_ROOT/device/libTranslateCore.a"
build_one iphonesimulator ios-simulator-arm64 "$BUILD_ROOT/simulator/libTranslateCore.a"
make_static_framework TranslateCore "$BUILD_ROOT/device/libTranslateCore.a" "$BUILD_ROOT/simulator/libTranslateCore.a"
make_pdfium_xcframework
printf 'Prepared local iOS XCFrameworks in %s\n' "$FRAMEWORKS"
