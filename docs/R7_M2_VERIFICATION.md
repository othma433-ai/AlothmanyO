# R7-M2 Verification Record

**Baseline:** WA Al-Othmany Android v2.2.0 + R7-M1

## TDD evidence

R7-M2 was implemented test-first.

The initial synchronization test command failed because the R7-M2 sync source files did not yet exist. After the minimum implementation was added, the same test suite passed.

The pure synchronization suite proves:

1. one no-op scroll cannot complete synchronization;
2. terminal completion requires three matching confirmations by default;
3. verified progress resets terminal evidence;
4. discovery of a new stable group resets terminal evidence;
5. a changing viewport cannot accumulate terminal evidence;
6. group titles are stably deduplicated across case/whitespace variants;
7. provenance is preserved;
8. the chat fallback rejects unproven/private-chat evidence and accepts explicit group evidence only.

## Source-validation gates

The package contains these gates:

```text
./tools/run_core_tests.sh
./tools/run_r7_m1_tests.sh
./tools/run_r7_m2_tests.sh
bash ./tools/r7_m1_contract.sh
bash ./tools/r7_m2_contract.sh
python ./tools/verify_project.py
```

Expected successful labels are:

```text
CORE TESTS PASS
R7 M1 FOUNDATION TESTS PASS
R7 M2 SYNC TESTS PASS
R7_M1_COMPAT_PASS
R7_M2_COMPAT_PASS
PROJECT VERIFY PASS
```

## CI build gate

`.github/workflows/android-debug.yml` now executes:

1. legacy core tests;
2. R7-M1 tests;
3. R7-M2 synchronization tests;
4. project static verification;
5. M1 and M2 compatibility contracts;
6. Android unit tests;
7. `lintDebug`;
8. `assembleDebug`;
9. `assembleRelease`;
10. upload of debug/release APK artifacts.

## Status boundary

The local source-validation environment does not include an Android SDK or Gradle executable/wrapper. Therefore this package does **not** claim local Android compilation or a real-device pass.

Current evidence label:

**R7-M2 SOURCE VERIFIED / ANDROID BUILD VERIFICATION PENDING / FIELD VERIFICATION PENDING**

Real-device M2 acceptance must demonstrate:

- `SYNC_START`;
- `GROUP_FILTER_VERIFIED` or a controlled `SYNC_STRATEGY_SWITCH` to `CHAT_GRABBER`;
- real group titles persisted with correct provenance;
- `SYNC_TERMINAL_CONSENSUS` or `CHAT_GRABBER_TERMINAL_CONSENSUS` before normal completion;
- no false COMPLETE after one failed/no-op scroll;
- Personal WhatsApp and WhatsApp Business tested separately.
