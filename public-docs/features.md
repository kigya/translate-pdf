# Feature and verification map

Implemented behavior is separate from release acceptance. The test identifiers below refer to the project's verification registry; open checks still block a release.

| Capability | Implementation and checks | Acceptance boundary |
|---|---|---|
| PDF and DOCX import | Content validation, private original, bounded OOXML parsing; native round-trip and malformed-file tests | Full real-document and large-file corpus remains open |
| Four translation engines | Three existing local models plus consented MyMemory; six document languages, 30 directed pairs | API compatibility is established on short examples; pair-specific quality is unmeasured |
| Checkpoints and retry | Engine snapshots, fragment persistence, explicit retry after online failure | Complete interruption/background and four-engine device matrix remains open |
| Word preview/export | Isolated bundled renderer, separate DOCX and PDF output | Pagination and formatting are best effort; full fidelity is not claimed |
| Reference onboarding and startup | T39/T42: three title-only scenes, fixed progress, Next/Skip, Grain Drift 0/3, reduced motion | Separate Debug/Release device checks and source-bound UX acceptance are required |
| Appearance and language | T30/T40: inline system/dark/light choices, searchable 30-locale UI picker | Complete RTL, accessibility and native-speaker review remains open |
| Search and color labels | T43: local filename search, persistent six-color labels for finished jobs, color sort | Native migrations and component checks do not replace restart/device checks |
| Processing presentation | T45: real stage timeline and progress, foreground completion confetti | No simulated percentage or provider switching |

Strict 100% coverage of owned JavaScript and native executable code remains mandatory. Current failures are retained. Independent source review, build success and a usable APK do not establish release readiness. See [verification status](verification.md).
