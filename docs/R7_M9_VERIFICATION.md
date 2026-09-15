# R7-M9 Verification

## Source-level verification targets

R7-M9 is accepted at source level only when all of the following are true:

- Pure host coordinator tests pass.
- The Android accessibility service contains no direct runtime/telemetry/workflow logic.
- The Android host routes normal and scroll accessibility events correctly.
- `AutomationRuntime` no longer depends on `WaAccessibilityService`.
- `WhatsAppUiBridge` no longer depends on `WaAccessibilityService` or service-owned `lastEventAt`.
- M1-M8 compatibility contracts continue to pass with M3 updated to validate the relocated scroll telemetry hook.
- Project static verification passes.
- Modified host/runtime Kotlin files pass an independent stub-based type-check.
- Manifest still has no `INTERNET` permission.

## Android build status

Source verification is not equivalent to an Android build. Android unit tests, lint, debug assembly, release assembly, and real-device testing remain separate gates and are executed by the GitHub Actions workflow / representative Android device.
