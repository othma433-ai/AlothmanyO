# R7-M3 Changeset — Verified Navigation + Semantic Smart Scroll

**Baseline:** WA Al-Othmany Android v2.2.0 + R7-M1 + R7-M2

## Added

- `r7/extraction/ConversationVerifier.kt`
  - exact normalized package/title verification
  - hard rejection for package/title mismatch
  - weak identity-signature mismatch for bounded reopen/recovery
  - verifier-issued `VerifiedConversation` token
- `r7/navigation/ConversationNavigator.kt`
  - bounded weak-identity reopen policy
  - one recovery tier before rejection
  - immediate rejection for hard title/package mismatch
- `r7/navigation/SmartScrollController.kt`
  - semantic directions: `OLDER`, `NEWER`, `LIST_FORWARD`, `LIST_BACKWARD`
  - strict strategy order: primary scrollable node -> alternate scrollable node -> safe gesture
  - postcondition verification by viewport change or scroll-specific accessibility telemetry
  - three-cycle terminal consensus by default
  - rejected gesture dispatch routes to recovery, never to false boundary completion
- `tools/run_r7_m3_tests.sh`
- `tools/r7_m3_contract.sh`

## Runtime migration

`AutomationEngine` now opens extraction targets through `openVerifiedConversation()`.

Extraction admission requires:

1. target package equality,
2. exact normalized conversation title equality,
3. when a group was discovered through `CHAT_GRABBER`, a matching strong identity signature when that evidence can be reproduced.

Only verifier `PASS` produces a `VerifiedConversation`. `extractGroup()` requires that token and performs a fresh exact snapshot verification before constructing the persistence repository or committing URLs.

The former loose `snapshotMatchesGroup()` contains/text fallback is removed from the extraction gate.

## Smart scroll integration

Deep/New-only extraction no longer calls the legacy `bridge.scrollOlder()` path. It uses:

`bridge.semanticScroll(SemanticScrollDirection.OLDER, SmartScrollController(...))`

A scroll attempt is verified only when either:

- the viewport fingerprint changes, or
- `AccessibilityEvent.TYPE_VIEW_SCROLLED` telemetry advances.

At a possible oldest boundary, a single no-progress action is insufficient. The complete primary/alternate/gesture chain must produce stable no-progress evidence three times before `BOUNDARY` is accepted.

## Compatibility

- R7-M2 synchronization remains intact.
- Existing invite/publish paths remain intact.
- Room schema remains v3; M3 adds no DB migration.
- No INTERNET permission is added.
- Legacy `scrollOlder()` remains available for compatibility but is no longer used by extraction.
