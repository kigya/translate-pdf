# Folivela

<img src="assets/brand/icon.svg" width="88" alt="Red document icon with translation arrows" />

Translate PDF and Word documents on your phone. Folivela offers three downloadable local models and an optional MyMemory online mode that works without a model download or API key. Document translation supports Russian, Simplified Chinese, French, English, German and Spanish in all 30 directions. The interface supports 30 languages and follows the system language by default.

This is a prototype in verification. Translation quality, long-document reliability, accessibility and complete platform acceptance remain open. There is no public store release.

![Expo SDK 58](https://img.shields.io/badge/Expo-SDK_58-000020?logo=expo) ![React Native 0.88](https://img.shields.io/badge/React_Native-0.88-61DAFB?logo=react&logoColor=white) ![TypeScript 6](https://img.shields.io/badge/TypeScript-6-3178C6?logo=typescript&logoColor=white) ![Android API 26+](https://img.shields.io/badge/Android-API_26%2B-3DDC84?logo=android) ![iOS 17+](https://img.shields.io/badge/iOS-17%2B-333333?logo=apple)

## Documents and translation

- Import PDF or modern Word `.docx` files through the system picker, preview the original and choose a language pair and translation engine.
- Use a local model offline after its download has passed the pinned size and SHA-256 checks. Android OCR uses bundled Tesseract data; iOS uses Vision where the source language is supported.
- Choose MyMemory for online translation without downloading a model. You must agree before text from each new document is sent. The anonymous service allows up to 5,000 characters per day; shared networks and provider availability can affect that limit.
- Keep translation checkpoints locally. Network or quota interruptions pause online work for a later retry. Android uses a foreground service; iOS can pause when the operating system ends its background allowance.
- Save translated PDFs. Word jobs also produce editable DOCX files. The original stays unchanged; Word preview and PDF pagination may differ from Microsoft Word. Unsupported elements and text that needs review are reported.
- Use a dictionary with compatible local models. Saved jobs keep their original language, model and dictionary choices. MyMemory does not use the dictionary.

Engine percentages are subjective product ratings, not measured accuracy or a guarantee for a language pair. Check important passages against the original.

## Interface

The Expo development-build application uses Expo Router and Kinetik source components for navigation, controls, its three-page tutorial, appearance settings and branded startup. It supports light, dark and system appearance, tutorial replay and reduced-motion settings. Buttons use solid fills and white text on red in dark mode. The tutorial has three illustrated, title-only pages with a fixed progress indicator and Next/Skip controls. Grain Drift provides the startup background. Search filenames, mark completed translations with a color and sort them by color. Translation stages and storage use live job and device data. The settings star rating is stored on this device. Removing downloaded models leaves documents and saved jobs on the device.

## Privacy

Local-model translation and document rendering run on the device. Model downloads contact Hugging Face and expose ordinary request metadata to that host.

If you select MyMemory and agree, extracted text fragments and language codes are sent over HTTPS to its public API. The original file is not uploaded. MyMemory's terms allow long-term segment storage and processing by other translation providers. Avoid confidential documents in online mode. The app does not submit contributions to MyMemory's public translation memory and cannot delete provider-retained copies. Read the [app privacy policy](https://kigya.github.io/translate-pdf/privacy/), [MyMemory terms](https://mymemory.translated.net/terms-and-conditions) and [service limits](https://mymemory.translated.net/doc/usagelimits.php).

Document and job data stay in app-private storage. The current UI does not provide source-document deletion. Uninstalling removes app-private data according to the operating system. Saving or sharing an exported PDF or DOCX gives the selected destination access to that file.

## Build from source

Use Node.js 22.13 or later, JDK 21 for Android, and the checked-in native projects. iOS requires Xcode and CocoaPods. The app needs a local development build; Expo Go does not include its custom translation engine. Builds do not require paid EAS services or a hosted backend.

```sh
npm ci
python3 scripts/setup-native.py
npm run typecheck
npm test -- --runInBand
npm start
npm run android
```

The native setup script downloads pinned build dependencies into ignored storage, without downloading translation model weights. For iOS, run `bash ios/scripts/build-native-frameworks.sh`, install pods in `ios`, then build `ios/TranslatePDF.xcworkspace`. Preserve the hand-maintained native projects; do not replace them with `expo prebuild --clean`.

Read the [feature and verification map](public-docs/features.md), [verification status](public-docs/verification.md) for acceptance limits and [architecture and data flow](public-docs/architecture.md) for the native processing boundary. Third-party notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Application source and model weights have separate terms; review the applicable notices before redistribution or use.
