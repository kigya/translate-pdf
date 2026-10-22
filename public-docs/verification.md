# Verification status

Folivela remains a prototype in verification. Current source adds optional MyMemory translation, DOCX processing/export and an Expo/Kinetik UI migration. These changes invalidate earlier full-source acceptance evidence.

The DOCX core has passed host CMake tests, round-trip and malicious ZIP/XML cases, plus AddressSanitizer and UndefinedBehaviorSanitizer. iOS store/provider host tests cover consent, engine snapshots, fragment checkpoints and Unicode request limits. Android Debug/Release and unsigned iOS device/ARM64 simulator Release builds pass. Bounded Xiaomi checks verify consented online Word translation, nonblank later preview pages, editable export and the updated tutorial. Detailed current results are in [online and Word verification](online-word-verification.md). These checks do not establish complete device or platform acceptance.

The required checks include strict 100% owned JavaScript and native coverage, Android Debug/Release Xiaomi flows, Android and unsigned iOS Release builds, complete document/lifecycle/accessibility/localization cases, security review, a source-bound privacy report and merged-main CI. Missing or failed checks remain blockers. The project does not claim release readiness, production signing, a GitHub Release or a store submission.

MyMemory availability was checked on short synthetic examples in all 30 document-language directions. This was an API compatibility check, not a quality benchmark. Its anonymous quota and data-retention terms limit its suitability. All four displayed engine percentages are approximate product ratings; they do not establish translation accuracy.
