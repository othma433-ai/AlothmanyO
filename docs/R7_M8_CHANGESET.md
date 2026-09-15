# R7-M8 Changeset — Verified Join Engine

## Scope
R7-M8 migrates the Join path from label re-search after classification to a prepared-target flow. Scan-only remains non-clicking. Publish remains on the v2.2 verified send path.

## Prepared target contract
- Added `JoinTargetResolver`, `JoinActionTarget`, `JoinPostconditionVerifier`, and `JoinTargetIdentityGuard` under `com.alothmany.wa.r7.join`.
- `prepareInviteAction()` now classifies the current invite surface and returns a `PreparedInviteAction` containing the exact `ActionTargetEvidence` selected during classification.
- Action labels are matched exactly after normalization. Misleading substring-only labels are not actionable.
- Multiple distinct exact targets fail closed as ambiguous.

## Same-target dispatch
- `ActionAutomationEngine` persists `ACTION_TARGET_VERIFIED` only when a prepared target exists.
- `performInviteAction(prepared)` does not re-search for the first Join/Request target by label.
- The prepared structural evidence is reacquired through `AndroidAccessibilityGateway` immediately before dispatch.
- Dispatch order is same live node -> clickable parent -> clickable child -> gesture at the freshly reacquired bounds.
- Stale, missing, window-mismatched, or ambiguous evidence is not treated as a successful click.

## Postcondition verification
- Android dispatch acceptance is dispatch evidence only.
- Approval joins succeed only after `REQUEST_SENT` or proven membership/composer state.
- Direct joins succeed only after proven membership/composer state.
- Invalid/expired invite state is terminal invalid.
- Community `View community` is treated as a navigation stage. A newly observed `Join community` target is prepared as a second target and independently reacquired before dispatch.
- No verified postcondition means `UNVERIFIED`, not success.

## Diagnostics
- Final unresolved-target and unverified-postcondition failures capture an M7 sanitized diagnostic/replay artifact.
- Raw invite URLs are not added to the diagnostic reason.

## CI / Gates
- Added `tools/run_r7_m8_tests.sh`.
- Added `tools/r7_m8_contract.sh`.
- Updated `tools/verify_project.py` and GitHub Actions through R7-M8.
- Android build/lint and real-device WhatsApp behavior remain separate gates.
