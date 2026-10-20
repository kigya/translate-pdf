# Verification status

TranslatePDF remains in verification. This is a summary of current evidence and limits, not a release certificate.

The UX iteration implements a red launcher/startup sequence, automatic onboarding, animated navigation and model presentation, separate language and theme controls, local storage management, a pair-specific dictionary, original-PDF preview, and contextual notification permission handling. Model capability scores are subjective presentation ratings, not measured translation accuracy.

Android Debug/Release and unsigned iOS device/ARM64 Simulator Release builds have passed during this iteration. Android and Swift host tests cover dictionary snapshot/migration behavior and model-download cancellation boundaries. A bounded Xiaomi Release check exercised settings, language/theme selection, storage display, Replay Back, original preview/zoom and the published privacy-policy link. An iOS Simulator launch reached onboarding. These checks do not establish complete platform acceptance; the final artifact and source version are recorded in the private test matrix.

## Open blockers

- Strict 100% owned-code coverage is not passing. Native aggregate instrumentation is incomplete.
- Translation quality, complete end-to-end and large-document coverage, measured memory/runtime limits, and the full network/background/restart matrix remain open.
- A language-search ANR was observed once on an Android emulator. Its cause has not been established and remains a runtime concern pending trace/reproduction evidence.
- Accessibility, all 30 translations, tablet/rotation/RTL and Release animation performance are not fully accepted.
- Dependency advisories and the complete Android dependency inventory remain open. Main-only CI is configured but has not run on a merged implementation.
- A final iOS rebuild after the last UI refinements is blocked by local Xcode compiler-discovery pipe hangs. Earlier unsigned native-source builds and a Simulator onboarding launch passed; this does not close the final-source iOS build gate.
- The public privacy policy describes the current implementation. Store-specific disclosures and final release review remain separate requirements.

The release workflow stays blocked until the existing test matrix and handoff gates pass. No production signing, store submission, or release is represented here.
