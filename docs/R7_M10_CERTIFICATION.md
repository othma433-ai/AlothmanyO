# R7-M10 Build & Real-Device Certification

## Purpose

M10 is a certification gate, not another automation-behavior rewrite. R7 remains `SOURCE VERIFIED` until Android compilation succeeds, becomes `ANDROID BUILD VERIFIED` only after the CI build/artifact gates pass, and becomes `REAL-DEVICE VERIFIED / PRODUCTION CANDIDATE` only after the representative-device field gates are completed with evidence.

## Toolchain lock

- JDK 17
- Gradle 8.11.1
- Android Gradle Plugin 8.9.2
- Android SDK 35
- Android Build Tools 35.0.0
- Kotlin Gradle Plugin 2.2.21
- Room 2.8.5

The project intentionally does not vendor Gradle or Android SDK binaries. GitHub Actions provisions the pinned toolchain.

## Build gate

The CI workflow must pass, in order:

1. Legacy core tests.
2. R7 M1-M9 pure/source tests.
3. Database migration test.
4. M1-M10 compatibility contracts.
5. Project static verification.
6. `:app:testDebugUnitTest`.
7. `:app:lintDebug`.
8. `:app:assembleDebug`.
9. `:app:assembleRelease`.
10. `verify_r7_m10_artifacts.sh`.

The artifact verifier requires:

- Debug APK exists and has valid ZIP structure.
- Release **unsigned** APK exists and has valid ZIP structure.
- Both report `com.alothmany.wa`, versionCode `20200`, versionName `2.2.0` through `aapt2`.
- Debug APK signature verifies with `apksigner`.
- Release artifact remains explicitly unsigned until a production keystore/signing policy is configured outside source control.
- SHA-256 checksums are generated for both APKs.

A successful `assembleRelease` does **not** mean production signing is complete.

## Real-device smoke gate

Run on exactly one authorized Android device:

```bash
./tools/r7_m10_device_gate.sh app/build/outputs/apk/debug/app-debug.apk
```

This captures device/OS identity, install result, launch result, package state, initial memory snapshot and logcat, and fails on an immediate package-associated fatal exception.

This smoke gate is necessary but not sufficient for WhatsApp certification.

## WhatsApp field certification

Complete every row in `docs/R7_M10_FIELD_RESULTS_TEMPLATE.csv` with `PASS` or `FAIL` and a concrete evidence path/reference. Required coverage includes WhatsApp Personal, WhatsApp Business, long-list terminal consensus, duplicate titles, Deep, New-only, pause/resume, service recreation, failed-item continuation, Direct Join, approval-required Join, stale-target fail-closed behavior, export fidelity and a six-hour stability run.

### Production-candidate rule

The label `REAL-DEVICE VERIFIED / PRODUCTION CANDIDATE` is allowed only when:

- Android Build Artifact Gate = PASS.
- Device Smoke Gate = PASS.
- FG-01 through FG-17 = PASS with evidence.
- Zero wrong-chat extraction events.
- Zero false `COMPLETE` events.
- Zero queue-membership corruption events.
- Zero crashes during the six-hour stability run.
- No unbounded memory-growth pattern is observed.
- Production release signing is configured and verified separately before distribution as a release build.

## Current environment limitation

The source-verification environment used to prepare M10 has neither Android SDK nor Gradle installed and cannot resolve Gradle distribution hosts from the local container. Therefore local Android compilation cannot be claimed from source-level evidence. The GitHub Actions build gate is the authoritative next build step.
