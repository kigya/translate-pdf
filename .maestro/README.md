# Local Android UI checks

These flows require the app already foregrounded on Library, English UI, and
System appearance selected. They preserve documents, models and notification permissions. The appearance, replay and language flows do not relaunch the app. The document-colors flow requires an existing translated synthetic `DOC_NAME` fixture and restarts the app with `clearState: false` to check persistence. No flow clears app data, uninstalls the app, overrides permissions or removes models. Appearance finishes by restoring the required System setting.

Run with Maestro CLI 2.11.0, Java 17+, an explicitly selected device, analytics
disabled, and debug output in an ignored private directory. Validate every flow
with `maestro check-syntax` before `maestro --device DEVICE test FLOW`.

The flows describe three-scene replay Back/Next/Skip/swipes, twenty language
alias searches, an empty search result, and the appearance switches. They
do not prove first-run startup, PDF processing, translation quality, notification
permissions, reduced motion, screenshot quality or complete E2E acceptance.

Flows were updated for Expo/Kinetik. Execution on the current build is recorded separately; the YAML files alone are not passing E2E evidence.
