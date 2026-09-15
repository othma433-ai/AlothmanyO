# R7-M7 Changeset — Runtime Profiles, Sanitized Diagnostics, Replay

## Scope
R7-M7 adds conservative versioned runtime learning and deterministic off-device replay without changing the extraction queue, New-only checkpoint schema, or UI layout.

## Runtime Profiles
- Added `RuntimeProfileKey` keyed by package, WhatsApp app version, locale, Android version, device model/class, and observable Android user/profile identity.
- A WhatsApp app-version change produces a different profile ID and sharply reduces applicability of older profiles.
- Learning requires three verified successes by default.
- Unverified Android dispatch success never trains the profile.
- Verified failures lower confidence.
- Recommendations always carry `requiresVerification=true`; they only prioritize strategies.
- Added durable private `AndroidRuntimeProfileStore` using SharedPreferences. Payloads contain UI hints only, not message text.
- Groups-filter view IDs are the first live learned hint integrated into the bridge.

## Sanitized Diagnostics
- Added `DiagnosticCapture`, `DiagnosticBundle`, and bounded private `DiagnosticArtifactStore`.
- Diagnostic node content uses `NodeSnapshot.textHash` / `contentDescriptionHash`; raw accessibility message text is not read by the diagnostic layer.
- Free-form classifier/action/verification/profile detail is hashed before persistence.
- Important automatic failures can capture a sanitized artifact.
- Long-pressing the existing Diagnostics text requests a manual capture without adding a new visual control.

## Deterministic Replay
- Added `ReplayFixture`, a deterministic line codec, and `ReplayEngine`.
- Replay can exercise `ScreenClassifier`, WhatsApp adapter selection/filter recognition, `GroupGrabberController`, `SmartScrollController`, and `TerminalConsensus` on JVM fixtures.
- Automatic diagnostic captures persist a `.diag` file plus a `.replay` fixture under private app storage with bounded retention.

## CI / Gates
- Added `tools/run_r7_m7_tests.sh`.
- Added `tools/r7_m7_contract.sh`.
- Updated `tools/verify_project.py` and GitHub Actions to R7-M7.
- Android build/lint remain GitHub gates; this source package is not labeled APK/build verified until those gates execute successfully.
