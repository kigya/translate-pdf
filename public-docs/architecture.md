# Architecture and data flow

TranslatePDF is a local-first mobile prototype built with Bare React Native and TypeScript, with native Kotlin/Swift modules and a C++ translation/PDF layer.

## A translation job

1. The user chooses a PDF through the platform document picker. The app copies it into private app storage and records its name, page count, and a source hash.
2. OCR and PDF text extraction run on-device. Android uses bundled Tesseract data; iOS uses Vision. Translation uses a model the user has downloaded to the device.
3. The app stores job settings, dictionary snapshot, page progress, and checkpoints locally. Android runs work through a foreground service. iOS uses a background download session for model files and can pause translation when the operating system ends its available background time.
4. A translated PDF is assembled and checked locally before the app offers it for saving or sharing through the operating system.

PDF contents, OCR text, translations, and dictionary terms are not sent to a translation service in the current implementation. Model downloads are the network-dependent feature: pinned model assets are fetched from Hugging Face and verified against expected size and SHA-256.

## Local data and limits

Source PDFs, database records, page checkpoints, dictionary entries, models, and app-private exports are stored on-device. The app currently provides model removal, while its user interface does not provide source-document deletion. Removing a model does not remove documents or saved jobs. A user-selected export destination receives the exported PDF.

Android automatic app backup is disabled. iOS explicitly marks downloaded models and translated exports as excluded from backup; this does not establish that every app file is excluded from every device backup configuration.

The app has no account service, ad SDK, analytics SDK, telemetry endpoint, cloud translation endpoint, or Firebase integration in the current source tree. This statement describes the checked-in application source and should be rechecked if dependencies or network features change.
