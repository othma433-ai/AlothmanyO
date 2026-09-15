# R7-M5 Verification

Source verification gates:

- `tools/run_r7_m5_tests.sh`
  - deterministic checkpoint round-trip
  - privacy of serialized checkpoint
  - strong prior-checkpoint match
  - weak single-anchor rejection
  - unread-boundary stop
  - bounded historical no-progress stop
  - legacy anchor compatibility
  - sparse-evidence checkpoint round-trip
  - resume stops at prior high-water instead of intentionally replaying full history
- `tools/r7_m5_contract.sh`
  - legacy `anchorOverlap`/`anchorToken` logic absent
  - high-water commit ordered after final verification/completion gate
  - privacy invariant (`INTERNET` permission absent)
- `tools/verify_project.py`
  - M1–M5 source/CI invariants
- Existing R7-M4 migration test retained because M5 does not change Room schema.

Android build and real-device behavior remain separate gates in GitHub Actions / field testing.
