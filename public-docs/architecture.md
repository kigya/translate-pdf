# Architecture and data flow

Folivela uses Expo SDK 58, Expo Router, React Native 0.88 and TypeScript. Kinetik source components provide tokens and motion. Kotlin/Swift modules own durable jobs and private storage; C++ provides PDFium, local inference and bounded OOXML processing.

## Translation

The system picker copies a PDF or DOCX into private storage and records its hash. PDF text extraction and OCR stay local. The DOCX engine reads supported Word XML parts without fetching relationships or executing embedded content. It preserves the original package and creates a separate translated DOCX.

A job freezes its language pair, document hash and chosen engine. Local jobs retain their pinned model/profile and compatible dictionary snapshot. Online jobs require explicit consent and send only text fragments to the MyMemory GET API, bounded to 500 UTF-8 bytes and one paragraph per request. Saved fragments are reused after interruption. Quota, connection and temporary provider failures pause the job; there is no automatic provider switch.

Android owns work in a foreground service. iOS stores checkpoints and pauses when its background time expires. The UI receives snapshots and private file references rather than model weights or whole-document payloads.

## Preview and export

PDF rendering and assembly use the existing native pipeline. Word previews use a bundled, isolated docx-preview renderer, JSZip and a native print-to-PDF adapter. Only the allow-listed local renderer assets and the selected private document are served to the WebView. Network requests and arbitrary navigation are blocked. The cached PDF feeds the same image/page preview UI; physical pages are distinct from translation segments. Editable DOCX export remains available if preview generation fails.

Word pagination and inline formatting are best effort. Unsupported content stays original and is reported. The app does not promise pixel-identical Microsoft Word output.

## Privacy and storage

Document text remains local unless the user selects MyMemory and agrees for that document. MyMemory may retain submitted text and involve external translation providers under its terms. Model downloads contact Hugging Face and verify file size and SHA-256. There is no application backend, account, ad SDK or Firebase integration.

Documents, translations, checkpoints and dictionary entries remain in app-private storage. Model removal keeps documents and jobs. The UI does not currently offer source-document deletion. Export destinations receive the exported file. Android backup is disabled; iOS excludes downloaded models and exports from backup, which does not establish exclusion of every file from every device backup configuration.

## Interface state

Document color labels and the recent/color library sort are stored with native job/preferences data. Labels are available for completed or partial translations. Filename search stays local. No rating prompt or rating control is shown. Existing stored preferences remain compatible. The progress timeline reads real job stages, and confetti runs only on a foreground transition to completed work.
