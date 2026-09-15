# R7-M1 Changeset — v2.2.0 Source

## Added

- `com.alothmany.wa.r7.snapshot`: immutable structural node snapshots, signatures and action target evidence.
- `com.alothmany.wa.r7.accessibility`: tested resolution policy plus Android gateway/factory for synchronous live-node reacquisition before action dispatch.
- `com.alothmany.wa.r7.observe`: screen observation/classification and independent stability detector.
- `com.alothmany.wa.r7.adapter`: Consumer, Business and Generic WhatsApp adapter contracts.
- `com.alothmany.wa.r7.automation`: action generation/run guard and bounded circuit breaker.
- `com.alothmany.wa.r7.diagnostics`: bounded trace/metric buffers and shadow accessibility event telemetry.
- `tools/run_r7_m1_tests.sh`: pure-Kotlin R7 foundation test gate.
- `tools/r7_m1_contract.sh`: compatibility/security invariant gate.
- `docs/R7_BASELINE_ADAPTATION_v2.2.md`: maps the approved R7 design to the supplied v2.2 source.
- `docs/R7_M1_VERIFICATION.md`: evidence/status boundary.

## Modified

- `WaAccessibilityService.kt`: adds a non-invasive event-count/timestamp shadow hook only; workflow decisions are unchanged.
- `tools/verify_project.py`: requires R7-M1 foundation and CI gates.
- `.github/workflows/android-debug.yml`: adds legacy core, R7 core, project verify, compatibility, unit, lint, debug and release build gates.
- `README.md`: records R7-M1 status and verification boundary.

## Deliberately unchanged in M1

- Existing extraction state machine behavior.
- Existing invite action behavior.
- Existing controlled publisher behavior.
- Existing Room v3 schema/data.
- UI layout and control IDs.
- Manifest permissions (no INTERNET permission added).
- v2.2 build toolchain versions.
