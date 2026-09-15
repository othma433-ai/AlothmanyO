# R7-M3 Verification Record

## Source-level gates

The package must pass:

```bash
./tools/run_core_tests.sh
./tools/run_r7_m1_tests.sh
./tools/run_r7_m2_tests.sh
./tools/run_r7_m3_tests.sh
bash tools/r7_m1_contract.sh
bash tools/r7_m2_contract.sh
bash tools/r7_m3_contract.sh
python tools/verify_project.py
```

## M3 regressions covered

- exact normalized title passes; contains-only title fails
- package mismatch is hard rejection
- identity mismatch is weak/bounded recovery evidence
- weak mismatch gets two reopen attempts, one recovery tier, then rejection
- primary -> alternate -> gesture scroll order is enforced
- viewport change verifies scroll progress
- TYPE_VIEW_SCROLLED telemetry can verify progress if viewport signature is stable
- one/two no-progress cycles cannot prove a boundary
- three stable full no-progress cycles prove a boundary
- rejected gesture dispatch cannot accumulate boundary evidence
- `extractGroup()` requires a verifier-issued `VerifiedConversation` token
- extraction source no longer uses loose `snapshotMatchesGroup()` or legacy `bridge.scrollOlder()`

## Build/field gate

This source package is not labeled APK-verified until GitHub/Android Studio completes:

- `:app:testDebugUnitTest`
- `:app:lintDebug`
- `:app:assembleDebug`
- `:app:assembleRelease`

Real-device validation remains required for WhatsApp Personal, Business, long-history extraction, same/similar group titles, and boundary behavior.
