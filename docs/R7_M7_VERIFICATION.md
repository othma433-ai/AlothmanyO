# R7-M7 Verification

## Source-level gates
The M7 source is required to pass:

- Legacy core tests
- R7 M1 foundation tests
- R7 M2 sync tests
- R7 M3 navigation tests
- R7 M4 persistence tests
- R7 M4 migration test
- R7 M5 New-only tests
- R7 M6 recovery tests
- R7 M7 profile/replay tests
- M1–M7 compatibility contracts
- `tools/verify_project.py`
- Android XML parse
- GitHub Actions YAML parse
- shell syntax for tool scripts
- Python syntax for Python verification tools

## M7-specific assertions
- Runtime profile identity changes when WhatsApp version changes.
- Older-version applicability is downgraded.
- Two verified successes do not activate a candidate; the third does.
- Unverified success does not train a profile.
- Failure lowers confidence.
- Recommendations never bypass verification.
- Runtime profile codec round-trips deterministically.
- Diagnostics contain structural fields and hashes, not raw accessibility message text.
- Replay fixture codec is deterministic and lossless for fixture data.
- Replay reproduces classifier, adapter, group proof, semantic scroll, and terminal-consensus decisions.

## Status semantics
Passing these gates means **R7-M7 SOURCE VERIFIED** only.
It does not mean `assembleDebug`, `assembleRelease`, or real-device WhatsApp field gates have passed in this environment.
