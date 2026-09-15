# R7-M8 Verification

## Source-level gates
R7-M8 is required to pass:

- Legacy core tests
- R7 M1 foundation tests
- R7 M2 sync tests
- R7 M3 navigation tests
- R7 M4 persistence tests
- R7 M4 migration test
- R7 M5 New-only tests
- R7 M6 recovery tests
- R7 M7 profile/replay tests
- R7 M8 verified-join tests
- M1–M8 compatibility contracts
- `tools/verify_project.py`
- Android XML parse
- GitHub Actions YAML parse
- shell syntax for tool scripts
- Python syntax for Python verification tools

## M8-specific assertions
- Classification produces a prepared action containing the exact target evidence.
- Misleading substring-only action labels are rejected.
- Approval, direct, community-join, and community-view target kinds are distinguished.
- Community Join is preferred over View when both are present.
- Accepted dispatch with an unchanged invite state is not success.
- Approval requires Request-sent or member/composer evidence.
- Direct/community Join requires member/composer evidence.
- Invalid state remains terminal invalid.
- First-stage Join execution consumes the prepared target; it does not re-search Join labels after classification.
- Same-target live-node resolution occurs before click dispatch.
- Final unresolved/unverified failures create sanitized diagnostic captures.

## Status semantics
Passing these source gates means **R7-M8 SOURCE VERIFIED** only. It does not prove `testDebugUnitTest`, `lintDebug`, `assembleDebug`, `assembleRelease`, or real-device WhatsApp field behavior until those gates execute in an Android-capable environment/device.
