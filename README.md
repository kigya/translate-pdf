# Folivela

<img src="assets/brand/icon.svg" width="88" alt="Red document icon with translation arrows" />

Folivela is an offline-first PDF translation prototype for Android and iOS. Translate documents between Russian, Simplified Chinese, French, English, German, and Spanish with a model downloaded to your device. Scanned pages can use on-device text recognition where supported. The interface has 30 languages and follows the system language by default.

The project is still in verification. Translation quality, long-document reliability, memory use, background behavior, accessibility, and store readiness have not been fully established. There is no public store release.

![React Native 0.88](https://img.shields.io/badge/React_Native-0.88-61DAFB?logo=react&logoColor=white) ![TypeScript 6](https://img.shields.io/badge/TypeScript-6-3178C6?logo=typescript&logoColor=white) ![Android API 26+](https://img.shields.io/badge/Android-API_26%2B-3DDC84?logo=android) ![iOS 17+](https://img.shields.io/badge/iOS-17%2B-333333?logo=apple)

## What is implemented

- PDF selection, a source preview, translation options, and a local document library.
- On-device translation with separately downloaded model files. Downloads are checked against pinned size and SHA-256 values.
- Local OCR using bundled Tesseract data on Android and Apple Vision on iOS, subject to the platform's language support.
- A language-pair dictionary whose entries are saved with each new job, so later edits do not change an existing job's saved terms.
- Local progress and checkpoints. Android uses a foreground service; iOS can pause when the operating system ends its available background time.
- Storage controls for downloaded models. Removing models leaves documents and saved translation work on the device.
- Automatic animated onboarding demonstrations, animated navigation, light and dark appearance, and reduced-motion handling.

The app does not send PDF contents to a translation server. Downloading a model requires an internet connection and contacts its public host, Hugging Face; the host receives ordinary connection metadata such as the IP address and requested model file. You can read the [privacy policy](https://kigya.github.io/translate-pdf/privacy/) before using the app.

The library currently has no in-app control for deleting source documents. Uninstalling the app removes its app-private data according to the operating system. Exporting a translated PDF through the system share or save flow gives the selected destination access to that exported file.

## Build from source

The repository contains a Bare React Native application. Use Node.js 22.11 or later and follow the platform-native setup for the checked-in Android and iOS projects. Dependencies are pinned in the lockfile.

```sh
npm ci
python3 scripts/setup-native.py
npm run typecheck
npm test -- --runInBand
npm start
```

The native setup script downloads pinned build dependencies into ignored local storage; it does not download translation model weights. Android builds use the `android` Gradle project. For iOS, run `bash ios/scripts/build-native-frameworks.sh`, install the CocoaPods dependencies in `ios`, then open `ios/TranslatePDF.xcworkspace`.

The project is not release-ready: JavaScript coverage reaches all four 100% thresholds, but native coverage is incomplete, and several device, lifecycle, translation-quality, privacy, accessibility, and release checks remain open. See [verification status](public-docs/verification.md) and [architecture and data flow](public-docs/architecture.md). Third-party notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## License and model terms

Application source and model weights may have different terms. Review the applicable third-party notices and each model's terms before redistribution or use. The app may require you to accept a model's terms before downloading it.
