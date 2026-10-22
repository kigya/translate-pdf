# Third-party notices

TranslatePDF is a private prototype. No project-level license has been selected for TranslatePDF itself. This notice grants no license to the app's original code or product materials. Canonical notices are tracked under [`licenses/`](licenses/). The explicit [`licenses/catalog.json`](licenses/catalog.json) selects the in-app notices for each platform: 32 groups, with 30 Android groups/118 text files and 28 iOS groups/99 text files. Android-only OCR notices are not listed on iOS. Source-only, legacy and provenance files can remain in this repository without being exposed as runtime notices. Consult each selected full text for its terms.

## Runtime and UI dependencies

| Component | Pinned version | License | Tracked notice |
|---|---|---|---|
| React | 19.3.0 | MIT | [`licenses/react/LICENSE`](licenses/react/LICENSE) |
| React Native | 0.88.0 | MIT | [`licenses/react-native/LICENSE`](licenses/react-native/LICENSE) |
| React Native Safe Area Context | 5.10.1 | MIT | [`licenses/react-native-safe-area-context/LICENSE`](licenses/react-native-safe-area-context/LICENSE) |
| React Native Reanimated | 4.7.1 | MIT | [`licenses/react-native-reanimated/LICENSE`](licenses/react-native-reanimated/LICENSE) |
| React Native SVG | 15.16.0 | MIT | [`licenses/react-native-svg/LICENSE`](licenses/react-native-svg/LICENSE) |
| React Native Worklets | 0.13.0 | MIT | [`licenses/react-native-worklets/LICENSE`](licenses/react-native-worklets/LICENSE) |
| Hermes | version resolved by `ios/Podfile.lock` and Android Gradle dependency resolution | MIT | [`licenses/hermes/LICENSE`](licenses/hermes/LICENSE) |
| Tesseract4Android | 4.9.0 | Apache-2.0 | [`licenses/tesseract4android/4.9.0/`](licenses/tesseract4android/4.9.0/), including the wrapper, Tesseract, Leptonica, libpng, and IJG JPEG notices shipped with its AAR; upstream [tagged source](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0) |

The bundled `tessdata_fast` OCR data for English, Russian, Simplified Chinese, French, German, and Spanish is Apache-2.0. Its tracked notice is [`licenses/tessdata-fast/LICENSE.txt`](licenses/tessdata-fast/LICENSE.txt); the pinned source revision and each asset checksum are in [`assets/tessdata/SOURCE.json`](assets/tessdata/SOURCE.json). The redistributed Noto Sans Regular and Noto Sans SC Regular fonts are under SIL Open Font License 1.1. Their notices are copied to [`licenses/noto-sans/LICENSE.txt`](licenses/noto-sans/LICENSE.txt) and [`licenses/fonts/NotoSansSC/`](licenses/fonts/NotoSansSC/), respectively. Font provenance and checksums are in [`assets/fonts/SOURCE.md`](assets/fonts/SOURCE.md) and [`assets/fonts/NotoSansSC-SOURCE.json`](assets/fonts/NotoSansSC-SOURCE.json).

## Native dependencies

| Component | Pinned source | License and notice |
|---|---|---|
| llama.cpp | v0.7.0, commit `620d2225cc436ab0b001e3200e590e3b286ef9ab` | MIT; [`licenses/llama.cpp/LICENSE`](licenses/llama.cpp/LICENSE) |
| nlohmann/json | v3.12.0 single-header dependency | MIT; [`licenses/nlohmann-json/LICENSE`](licenses/nlohmann-json/LICENSE) |
| PDFium | build 8100, `bblanchon/pdfium-binaries` package | Package notice and every license file shipped in the pinned build package are copied into [`licenses/pdfium/8100/`](licenses/pdfium/8100/), including PDFium and notices for Abseil, AGG, Dragonbox, fast_float, FreeType, HarfBuzz, ICU, Little CMS, libjpeg-turbo, OpenJPEG, libpng, LLVM libc, simdutf, and zlib. |

The catalog includes the full pinned PDFium license set, including its root `LICENSE` and all 17 component notices. Android packages selected files under `assets/licenses/`. The iOS resource bundle stores the selected files under unique names formed by replacing path separators with `__`; `ios/scripts/sync-license-resources.sh` validates the catalog and regenerates that mirror before CocoaPods packages it. The unused New App Screen component, mobile-unlinked cpp-httplib and disabled model variants are absent from the in-app list. This catalog is not an exhaustive platform/toolchain SBOM.

## Translation models

The model is downloaded by the user at runtime and is not checked into this repository. Tencent's Hy-MT2 1.8B model repository identifies the model as Apache-2.0: [Tencent model repository](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF), pinned source revision `a0c709d9fac510f2c807aa3af52872340dc37a4a`. Historical community IQ3_XS and IQ2_M conversions are from `mradermacher/Hy-MT2-1.8B-i1-GGUF`, revision `9f5c7d98d8b625800775e6197e55c7ed38f2f33a`; the pinned repository metadata reports Apache-2.0, and its canonical license text is in [`licenses/models/hy-mt2-1.8b/LICENSE-Apache-2.0.txt`](licenses/models/hy-mt2-1.8b/LICENSE-Apache-2.0.txt). Those are community conversions, not Tencent-published quantizations. Their artifact hashes and sizes are recorded in the model catalog; translation quality and device-memory compatibility remain unverified.

The current chooser also offers the official [Hy-MT2 7B Q4_K_M package](https://huggingface.co/tencent/Hy-MT2-7B-GGUF), revision `ab8472660ac61fac25f1af43fac2599d52a8a775`. The exact upstream Apache-2.0 license, including Tencent's 2026 copyright notice, is preserved in [`licenses/models/hy-mt2-7b/LICENSE`](licenses/models/hy-mt2-7b/LICENSE); package provenance is in the adjacent [`NOTICE.txt`](licenses/models/hy-mt2-7b/NOTICE.txt). The legacy IQ3_XS and IQ2_M packages are disabled and absent from the three-choice picker.

[TranslateGemma 4B](https://huggingface.co/google/translategemma-4b-it) uses the Gemma Terms of Use and incorporated use restrictions. The downloadable Q4_K_M derivative is from `bullerwins/translategemma-4b-it-GGUF`, revision `7c938465a870d8624bcfa98a8e4a3510053c19a8`; it is a community quantization. Full [terms](licenses/models/TranslateGemma/TermsOfUse.txt), [use restrictions](licenses/models/TranslateGemma/ProhibitedUsePolicy.txt), the required [Notice](licenses/models/TranslateGemma/Notice), and [quantization provenance](licenses/models/TranslateGemma/ModificationNotice.txt) are bundled with the app. The model screen links to the terms and requires an explicit acceptance action before download. This does not establish release-specific legal clearance.

## Redistribution limits

These notices cover the listed redistributed components. They are not an exhaustive SBOM or legal clearance for a release: Android transitive AAR/native notices, the complete Xcode/Apple toolchain inventory, and notices for generated artifacts still require a release-specific inventory. Before public binary distribution, verify that the actual packaged artifacts and their required notices match these tracked copies and that users can access the notices in the product. No project-level license should be inferred from third-party licenses.


## DOCX preview and OOXML processing

DOCX preview uses docx-preview 0.3.7 (Apache-2.0) and JSZip 3.10.1 (distributed here under its MIT option). The JSZip group also includes the bundled pako, lie, immediate and setimmediate notices; the alternative GPL text is not listed as a second license choice in the app. Native OOXML processing uses the pinned miniz and pugixml versions recorded in [`native/vendor/README.md`](native/vendor/README.md) and their bundled license notices. Preview assets run locally; this does not describe the separate optional MyMemory service.

## Kinetik UI

Kinetik UI source components were installed with kinetik-ui 1.4.2 and adapted for Folivela under the MIT license. Source provenance is recorded in kinetik.json.

## Expo and bundled JavaScript

The catalog includes Expo SDK/Router, Gesture Handler, Screens, View Shot and the React Native Skia wrapper, in addition to the runtime components above. Android and iOS Release source maps were reconciled against the resolved runtime package paths; the grouped JavaScript notice includes the package-local MIT/ISC/BSD notices for those bundled paths. Build-only dependencies are not automatically added to the in-app list.

React Native Skia 2.14.0 packages Skia m154 prebuilts. The wrapper MIT notice, Google Skia BSD core notice and the notices for the embedded libraries are included in the platform catalog. The [source record](licenses/react-native-skia/SOURCE.json) pins the successful upstream build, its Skia source revision and each selected notice. Android arm64 archives and all iOS framework files were compared byte for byte with the installed packages; their object inventories select the common and platform-specific notices. The standard-navigation 0.0.5 tarball omits its license file. The bundled MIT text is pinned to the maintainer’s [license addition](https://github.com/react-navigation/standard-navigation/blob/33f3d3c976a24bad9fe32583939318bc143fa41b/LICENSE); its executable navigator body matches the published version after TypeScript transpilation. Dependency security and release-specific distribution checks remain separate from these attribution records.


The native runtime groups preserve the required Boost, Double Conversion, Folly, glog, fmt, Android runtime and SocketRocket notices selected from the resolved artifacts and pinned source versions. Common Apache-2.0 terms are shared across those components. Android notices include Commons IO/Codec, Zstd, Brotli, Bolts, Expo native helpers, the packaged C++ runtime and Fresco JPEG decoder. The Public Suffix List notice includes MPL-2.0 and an immutable URL to the corresponding editable source; its normal/exception rules match the redistributed OkHttp database. [`licenses/native-runtime/SOURCE.json`](licenses/native-runtime/SOURCE.json) records the selected source bytes and hashes. These notices do not grant a license to the app's original code.
