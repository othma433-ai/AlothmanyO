# R7 Baseline Adaptation — WA Al-Othmany Android v2.2.0

The approved R7 Architecture Freeze was written against an older WA Workspace/R6 naming and build baseline. The supplied implementation source is newer: WA Al-Othmany Android v2.2.0.

## Authoritative source baseline

- Package root: `com.alothmany.wa`
- Version: `2.2.0` / versionCode `20200`
- compileSdk / targetSdk: `35`
- JDK: `17`
- Gradle in CI: `8.11.1`
- AGP: `8.9.2`
- Kotlin plugin: `2.2.21`
- Room: `2.8.5`, schema v3
- Existing engines retained: extraction, verified invite actions, controlled publish, recovery, diagnostics.

## R7 migration rule

R7 is layered on top of v2.2.0. Existing verified v2.2 behavior remains authoritative until a later R7 phase has equivalent tests and build/device evidence. R7-M1 is intentionally shadow/foundation-only and does not replace synchronization, extraction, join, or publishing decisions.

New R7 code is namespaced under `com.alothmany.wa.r7.*` to avoid conflicting with the existing v2.2 `AutomationEngine` and related production classes during incremental migration.

## Build-version decision

R7-M1 does **not** opportunistically upgrade compileSdk/AGP/Gradle. Toolchain upgrades are independent risk and are deferred until the migration has a green Android build baseline. The CI workflow therefore preserves the v2.2 build toolchain while adding R7 verification gates.
