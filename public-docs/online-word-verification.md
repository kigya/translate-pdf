# Online and Word implementation evidence

This iteration adds optional MyMemory translation, DOCX processing and the Expo/Kinetik interface, including filename search, persistent document colors, local app ratings and actual translation-stage indicators. It remains under verification. Required release checks have not all passed.

| Check | Evidence | Remaining boundary |
|---|---|---|
| JavaScript | 305 component/domain/router tests, TypeScript and ESLint pass | Strict 100% owned coverage is still enforced and currently fails |
| Android | Debug and Release APK builds, Release lint and native unit tests pass | JVM coverage is below 100%; WebView rendering also needs device evidence |
| DOCX core | Host round trips, bounded ZIP/XML attack cases, Unicode, tables/images, headers/notes, namespace and structural-break regressions pass; ASan/UBSan pass | LLVM reports 99.77% lines and 94.19% branch sides; complete document fidelity and strict coverage remain open |
| iOS | Unsigned device and ARM64 simulator Release builds pass; 26 store/provider tests pass | Builds and host tests do not establish iPhone runtime behavior or complete native coverage |
| Xiaomi Debug candidate | Additive update preserves preferences/models; current tutorial/theme/language-search and persistent color/search flows pass, with old documents and installed models retained. Earlier native Word checks verified consented translation, nonblank second pages and editable export | Full four-engine, interruption, permissions, language, accessibility and large-document acceptance remains incomplete |
| Xiaomi final Release | Final APK verifies PDF and Word consent refusal/agreement, online completion, preview, PDF save and Word DOCX/PDF save; originals and Word images/styles remain unchanged; both Word PDF pages reopen as nonblank | Full four-engine, language, interruption, accessibility and large-document acceptance remains open |
| Privacy | Published page returns HTTP 200 and matches source SHA-256 `744f6e71144a1f95836083e826a1c11b0dcc489ae7b6ed0f2a9561aacaa0bd75` | Complete dependency, backup and packet-level audit remains open |
| Independent review | Initial online/Word findings and all five U034 UI source findings resolved | Bounded source review does not replace runtime or full security acceptance |

Android Word preview initially produced blank later pages. The renderer now lays out each selected section at a fixed viewport origin before native PDF rasterization; the original and translated second pages were verified as nonblank on the physical device. The initial failed observation remains in private verification evidence. This is a synthetic fixture result, not a general Microsoft Word fidelity claim.

Buttons use solid fills throughout the application. Onboarding uses animated titles without subtitles, matching the approved reference, with immediate reduced-motion presentation. Next advances every page and completes the third; Skip remains below the primary button. Grain Drift uses grain 0 and speed 3 at startup. One fixed progress track sits outside the paging content. Illustration contrast has explicit light/dark palette checks. Model cards show percentages and an information action; the percentages remain conditional product ratings rather than measured translation accuracy.

All three local engine definitions and six document languages remain. MyMemory requests require agreement for each new document, use HTTPS GET without a key/email, and retain fragment checkpoints. Extraction, document rebuilding and preview run locally. Only selected text fragments are submitted; the anonymous provider limit is 5,000 characters/day. Provider errors pause work without changing its engine.

The dependency review found three unpatched leaf advisories in `braces`, `node-forge` and `sprintf-js`; npm audit propagates them to 59 graph entries. The inspected Android bundle does not include these packages, supporting a build/CLI tooling exposure disposition. This is a scoped disposition, not a clean dependency-audit claim. No blanket advisory suppression or forced Expo/React Native downgrade was applied.

The approved exact Expo SDK pin is 58.0.16. Expo's package checker suggests newer patch versions for Expo, Router and Splash Screen; those advisories remain visible rather than silently replacing the approved pin.

The version must not merge into `main` while required coverage, physical Debug/Release, accessibility/localization/security or other acceptance checks fail. APK availability is not release acceptance. Production signing, GitHub Releases and store publication remain separate work.

The phone PDF reviewer now has one vertical scroll area for its page, text and export actions. A failed save-reachability test exposed the former two-pane layout; the corrected final APK passed the same export flow. Wide screens retain two scrolling columns.
