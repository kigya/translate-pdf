# Verification status

Folivela 0.5.0 / build 500 is in verification. The [current requirements, measurements and acceptance matrix](0.5.0-verification.md) is authoritative for the source-preserving PDF, processing, library and launch changes. Android testing uses the first 30 Equinox pages; the separately produced Russian manual contains all 415. Earlier online/Word and onboarding checks are retained as [historical bounded evidence](online-word-verification.md).

Current JavaScript tests reach 100% of all four owned-code metrics. Shared native PDF tests and current Android/iOS builds pass, but whole-owned C++/Kotlin/Swift coverage remains below the strict 100% requirement. The earlier native-100 snapshot predates the new renderer and does not certify current source. The complete three-model Xiaomi reference runs, final Debug/Release device acceptance, matched performance convergence, accessibility/localization matrix and merged-main CI remain open. A compiled or installed APK does not close these checks.

The project retains strict 100% owned executable-code coverage and required functional/security/build/device gates. Missing or failed checks remain visible. There is no release-readiness, production-signing, merged-main, GitHub Release or store-publication claim. The current task prepares source and a draft PR; final publication is outside its scope.

MyMemory availability was checked on short synthetic examples in all 30 document-language directions. That establishes bounded API compatibility, not a quality benchmark. Its anonymous quota and data-retention terms still apply. Engine percentages are approximate product ratings, not measured translation accuracy.
