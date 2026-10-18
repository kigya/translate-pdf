# Third-party notices

TranslatePDF is a private prototype. No project-level license has been selected for TranslatePDF itself. This notice grants no license to the app's original code or product materials. Copies of the notices named below are tracked under [`licenses/`](licenses/) and are included in iOS and Android app assets. Consult each full text for its terms.

## Runtime and UI dependencies

| Component | Pinned version | License | Tracked notice |
|---|---|---|---|
| React | 19.3.0 | MIT | [`licenses/react/LICENSE`](licenses/react/LICENSE) |
| React Native | 0.88.0 | MIT | [`licenses/react-native/LICENSE`](licenses/react-native/LICENSE) |
| React Native New App Screen | 0.88.0 | MIT; source package is part of the React Native repository | [`licenses/react-native-new-app-screen/LICENSE`](licenses/react-native-new-app-screen/LICENSE) |
| React Native Safe Area Context | 5.10.1 | MIT | [`licenses/react-native-safe-area-context/LICENSE`](licenses/react-native-safe-area-context/LICENSE) |
| React Native Reanimated | 4.7.1 | MIT | [`licenses/react-native-reanimated/LICENSE`](licenses/react-native-reanimated/LICENSE) |
| React Native Worklets | 0.13.0 | MIT | [`licenses/react-native-worklets/LICENSE`](licenses/react-native-worklets/LICENSE) |
| Hermes | version resolved by `ios/Podfile.lock` and Android Gradle dependency resolution | MIT | [`licenses/hermes/LICENSE`](licenses/hermes/LICENSE) |
| Tesseract4Android | 4.9.0 | Apache-2.0 | [`licenses/tesseract4android/4.9.0/`](licenses/tesseract4android/4.9.0/), including the wrapper, Tesseract, Leptonica, libpng, and IJG JPEG notices shipped with its AAR; upstream [tagged source](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0) |

The English `tessdata_fast` OCR data is Apache-2.0. Its tracked notice is [`licenses/tessdata-fast/LICENSE.txt`](licenses/tessdata-fast/LICENSE.txt); source revision and asset checksum are in [`assets/tessdata/SOURCE.json`](assets/tessdata/SOURCE.json). The redistributed Noto Sans Regular font is under SIL Open Font License 1.1, copied to [`licenses/noto-sans/LICENSE.txt`](licenses/noto-sans/LICENSE.txt); font provenance and checksum are in [`assets/fonts/SOURCE.md`](assets/fonts/SOURCE.md).

## Native dependencies

| Component | Pinned source | License and notice |
|---|---|---|
| llama.cpp | v0.7.0, commit `620d2225cc436ab0b001e3200e590e3b286ef9ab` | MIT; [`licenses/llama.cpp/LICENSE`](licenses/llama.cpp/LICENSE) |
| nlohmann/json | v3.12.0 single-header dependency | MIT; [`licenses/nlohmann-json/LICENSE`](licenses/nlohmann-json/LICENSE) |
| cpp-httplib | vendored by the pinned llama.cpp source | MIT; [`licenses/cpp-httplib/LICENSE`](licenses/cpp-httplib/LICENSE) |
| PDFium | build 8100, `bblanchon/pdfium-binaries` package | Package notice and every license file shipped in the pinned build package are copied into [`licenses/pdfium/8100/`](licenses/pdfium/8100/), including PDFium and notices for Abseil, AGG, Dragonbox, fast_float, FreeType, HarfBuzz, ICU, Little CMS, libjpeg-turbo, OpenJPEG, libpng, LLVM libc, simdutf, and zlib. |

The PDFium package directory was copied in full from the private build inputs, including its root `LICENSE` and the complete `licenses/` directory. These copies are source-tracked, and the Android `assets/licenses/` packages the hierarchy with app builds. CocoaPods flattens resource-bundle files, so the iOS bundle stores the same files under unique names formed by replacing path separators with `__`; `ios/scripts/sync-license-resources.sh` regenerates that mirror from this canonical directory. This does not establish that every transitive platform/toolchain dependency is inventoried.

## Translation model

The model is downloaded by the user at runtime and is not checked into this repository. Tencent's Hy-MT2 1.8B model repository identifies the model as Apache-2.0: [Tencent model repository](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF), pinned source revision `a0c709d9fac510f2c807aa3af52872340dc37a4a`. The community IQ3_XS and IQ2_M conversions are from `mradermacher/Hy-MT2-1.8B-i1-GGUF`, revision `9f5c7d98d8b625800775e6197e55c7ed38f2f33a`; the pinned repository metadata reports Apache-2.0, and its canonical license text is in [`licenses/models/hy-mt2-1.8b/LICENSE-Apache-2.0.txt`](licenses/models/hy-mt2-1.8b/LICENSE-Apache-2.0.txt). Those are community conversions, not Tencent-published quantizations. Their artifact hashes and sizes are recorded in the model catalog; translation quality and device-memory compatibility remain unverified.

## Redistribution limits

These notices cover the listed redistributed components. They are not an exhaustive SBOM or legal clearance for a release: Android transitive AAR/native notices, the complete Xcode/Apple toolchain inventory, and notices for generated artifacts still require a release-specific inventory. Before public binary distribution, verify that the actual packaged artifacts and their required notices match these tracked copies and that users can access the notices in the product. No project-level license should be inferred from third-party licenses.
