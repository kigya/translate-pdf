# Translate PDF

<img src="assets/brand/icon.svg" width="96" alt="Translate PDF document and translation arrows icon" />

Translate PDFs between Russian, Simplified Chinese, French, English, German, and Spanish on a phone or tablet. Translation runs on the device with a model you download separately.

![React Native 0.88](https://img.shields.io/badge/React_Native-0.88-61DAFB?logo=react&logoColor=white) ![TypeScript](https://img.shields.io/badge/TypeScript-6-3178C6?logo=typescript&logoColor=white) ![Android API 26+](https://img.shields.io/badge/Android-API_26%2B-3DDC84?logo=android&logoColor=white) ![iOS 17+](https://img.shields.io/badge/iOS-17%2B-333333?logo=apple)

**Development prototype.** Translation quality, large-document reliability and device memory limits are still being tested. There is no public store release. Memory figures are provisional estimates; the operating system can pause a job when it reports low memory or the model cannot allocate memory.

## In the app

- Four interactive onboarding scenes demonstrate page translation, image text, model selection and background progress. The scenes use an authored example; their timing is not a device benchmark.
- A document library, local glossary, job progress, page comparison and PDF export share the same native job state.
- Android saves block checkpoints in SQLite and processes jobs in a foreground service. iOS saves progress and pauses when its available background time ends.
- PDFium preserves the source page and drawings. Text that cannot fit safely stays in the original page with a translation annotation and a partial-result status.
- Android bundles Tesseract OCR data for all six document languages; iOS uses Vision. The interface includes 30 locales, light and dark themes, RTL layout and reduced-motion states. Any two different supported document languages can be selected, giving 30 translation directions.

The C++ core runs llama.cpp with pinned GGUF packages. Weights are downloaded separately after a user action and checked against SHA-256. The selected model applies to new jobs; existing jobs keep their saved language pair, glossary, model and processing profile.

## Optional models

| Model | Download | EN→RU sample passages scored A/B out of 44 |
|---|---|---:|---:|
| [Hy-MT2 1.8B Q4_K_M](https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF) | 1.13 GB | 17/44 |
| [TranslateGemma 4B Q4_K_M](https://huggingface.co/bullerwins/translategemma-4b-it-GGUF) | 2.49 GB | 29/44 |
| [Hy-MT2 7B Q4_K_M](https://huggingface.co/tencent/Hy-MT2-7B-GGUF) | 4.62 GB | 31/44 |

These counts come from an open-label, non-expert manual review of 44 technical English-to-Russian passages. A/B retained the source meaning; C needed substantial revision and D changed a critical condition or object. All three models produced critical errors, so none is validated for technical warnings or arbitrary PDFs. The counts describe this sample only; they are not general accuracy scores.

All three models remain available even when a RAM estimate is above the current free-memory reading. The estimates are advisory warnings, not measured whole-app memory requirements, and do not disable a model or prevent a new job from starting. If Android reports low memory or a model allocation fails, the job pauses at its saved checkpoint. Disk space, model integrity and runtime compatibility checks still apply. TranslateGemma is a community quantization, requires acceptance of the Gemma terms before download, and does not support the saved glossary.

The Hy-MT2 packages use Apache-2.0. Models and documents are excluded from the base app download.

## Privacy

Documents, translations, glossary entries and checkpoints stay in the app's private storage. The app has no cloud translation option; inference runs locally. There are no accounts, ads, analytics SDKs or translation servers. Model downloads contact their public hosting provider. Exporting or sharing a PDF gives the selected destination access to that file. Uninstalling the app removes its private files.

## Build from source

Use Node 22, JDK 21, Android SDK/NDK 27.1.12297006, and Xcode 26.6 for iOS. Dependency versions are recorded in the lockfiles. The Android target currently builds ARM64 devices.

```sh
npm ci
python3 scripts/setup-native.py
npm run typecheck
npm test -- --runInBand --coverage
npm start
```

`setup-native.py` downloads and verifies the pinned PDFium, llama.cpp and JSON dependencies into ignored local storage. It does not download model weights by default. Android builds use the `android` Gradle project. For iOS, run `bash ios/scripts/build-native-frameworks.sh`, install the pods in `ios`, and open `ios/TranslatePDF.xcworkspace`. Select the full Xcode installation through `DEVELOPER_DIR` when the system default points to Command Line Tools.

The strict coverage checks currently fail; they remain required before release. Native dependency notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Model weights, user documents, test inputs, local reports and signing material are excluded from version control.
