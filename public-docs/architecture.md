# Architecture and data flow

Folivela uses Expo SDK 58, Expo Router, React Native 0.88 and TypeScript. Kinetik source components provide tokens and motion. Kotlin/Swift modules own durable jobs and private storage; C++ provides PDFium, local inference and bounded OOXML processing.

## Translation

The system picker copies a PDF or DOCX into private storage and records its hash. PDF text extraction and OCR stay local. The DOCX engine reads supported Word XML parts without fetching relationships or executing embedded content. It preserves the original package and creates a separate translated DOCX.

A job freezes its language pair, document hash and chosen engine. Local jobs retain their pinned model/profile and compatible dictionary snapshot. Online jobs require explicit consent and send only text fragments to the MyMemory GET API, bounded to 500 UTF-8 bytes and one paragraph per request. Saved fragments are reused after interruption. Quota, connection and temporary provider failures pause the job; there is no automatic provider switch.

Android owns work in a foreground service; service lifecycle callbacks hand expensive work to background executors. Pause requests acknowledge stop, save a checkpoint and release the worker before Resume becomes available. Generations reject callbacks from an earlier run. iOS stores checkpoints and pauses when its background time expires. The UI polls lightweight activity snapshots with cached counters; full bootstrap is reserved for structural state changes. Timers show stage duration rather than simulated progress.

## Preview and export

PDF rendering and assembly use the shared native source-preserving renderer. Each source page keeps its geometry, artwork and navigation; translated text fits verified paragraph, column and table-cell containers. Explicit source object/character ownership prevents crossed cells or omitted text. Actual ink, glyph, artwork and post-save checks reject unsafe output before atomic publication. Verified inline prose symbols may move inside the same original paragraph while retaining their content/font/style/linear transforms; diagram and legend symbols remain fixed. Renderer policies invalidate incompatible preview/PDF caches without clearing translated checkpoints. A reviewed separate manual payload is not evidence of local-model translation quality. Word previews use a bundled, isolated docx-preview renderer, JSZip and a native print-to-PDF adapter. Only the allow-listed local renderer assets and the selected private document are served to the WebView. Network requests and arbitrary navigation are blocked. The cached PDF feeds the same image/page preview UI; physical pages are distinct from translation segments. Editable DOCX export remains available if preview generation fails.

Word pagination and inline formatting are best effort. Unsupported content stays original and is reported. The app does not promise pixel-identical Microsoft Word output.

## Privacy and storage

Document text remains local unless the user selects MyMemory and agrees for that document. MyMemory may retain submitted text and involve external translation providers under its terms. Model downloads contact Hugging Face and verify file size and SHA-256. There is no application backend, account, ad SDK or Firebase integration.

Documents, translations, checkpoints and dictionary entries remain in app-private storage. Model removal keeps documents and jobs. Confirmed deletion stops owned work before removing the selected job, its checkpoints, private outputs and previews. A shared imported source copy is removed only after its last translation; external original files and user exports remain. Export destinations receive the exported file. Android backup is disabled; iOS excludes downloaded models and exports from backup, which does not establish exclusion of every file from every device backup configuration.

## Interface state

Document color labels are stored with native job data and apply to every noncanceled translation. BookPocket folders open separate screens for all, unfinished, completed and six colors. Unfinished/failed translations precede completed/partial results; partial results require review, canceled jobs are hidden, and imports without jobs remain available. Filename search stays inside the selected folder. Creation/addition timestamps are shown. Settings opens from the top right and Back retains the selected folder. No rating prompt or rating control is shown. Existing stored preferences remain compatible. The progress timeline reads real job stages, and confetti runs only on a foreground transition to completed work.

The home model summary uses Compact/Balanced/Maximum/Online. Model Settings reveals the actual model/provider, license and download inside FoldPanel. StatusButton reports verified download state and actual bytes. Online hides Dictionary while retaining local dictionaries. Appearance has system mode and a manual ThemeSwitch. The separate vector Folivela folio/sail F splash runs on the UI thread, observes Reduce Motion/background state and initializes in parallel with application data.
