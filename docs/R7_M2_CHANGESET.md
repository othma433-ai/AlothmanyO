# R7-M2 Changeset — Verified Group Synchronization

**Baseline:** WA Al-Othmany Android v2.2.0 + R7-M1

## Added

- `com.alothmany.wa.r7.sync.SyncModels`: synchronization state/action/provenance contracts.
- `TerminalConsensus`: requires repeated stable no-progress evidence before an end-of-list decision.
- `SyncController`: owns stable group deduplication and terminal-consensus decisions.
- `GroupGrabberController`: conservative All-Chats fallback classifier; it never assumes a private chat is a group without explicit group evidence.
- `tools/run_r7_m2_tests.sh`: pure-Kotlin synchronization regression gate.
- `tools/r7_m2_contract.sh`: source/compatibility gate for R7-M2 runtime integration.

## Runtime migration

`AutomationEngine.syncGroups()` is now routed through R7-M2 synchronization logic:

1. Activate the selected WhatsApp package.
2. Return to the Chats surface.
3. Dispatch and verify the Groups filter transition.
4. Reset toward list start.
5. Capture visible group rows using the existing v2.2 row semantics.
6. Persist each newly discovered stable group.
7. Scroll and observe the resulting viewport.
8. Accept end-of-list only after three matching no-progress confirmations with no new stable group keys.
9. If Groups filter verification fails, switch to `CHAT_GRABBER` fallback rather than reporting a false successful filter sync.

## Filter verification hardening

`WhatsAppUiBridge` now provides `navigateToGroupsFilterVerified()`.

A click return value is treated only as dispatch evidence. Verification prefers:

- selected state on the Groups control or clickable ancestor;
- selected `stateDescription` where Android exposes it;
- a guarded structural fallback requiring a changed list viewport, the semantic Groups control, and visible chat rows.

Decorated labels such as `Groups, 3 unread` are accepted only through a guarded filter-cluster fallback with known peer filters near the top of the WhatsApp surface.

## Conservative chat-grabber fallback

When the Groups filter cannot be verified, R7-M2 can traverse visible Chats rows and probe each currently visible conversation. It opens the conversation-info surface when possible and accepts a chat as a group only when explicit group markers are exposed, such as:

- `Group info`
- `Group permissions`
- `Add members`
- `Exit group`
- Arabic equivalents

Persisted fallback groups are tagged `classification = CHAT_GRABBER`; normal Groups-filter discoveries remain `GROUPS_FILTER`.

## Removed from sync completion logic

The old combined `quietRounds` / `bottomEvidence` completion heuristic has been removed from `AutomationEngine.syncGroups()`.

One failed/no-op scroll is not terminal evidence sufficient for completion.

## Deliberately deferred to R7-M3

- Full `TYPE_VIEW_SCROLLED` index/scrollY telemetry integration into semantic scrolling.
- Independent `SmartScrollController` strategy selection.
- Full `ConversationNavigator` / exact conversation verifier migration.
- Gesture-scroll fallback strategy ordering for list traversal.

R7-M2 accepts the existing v2.2 verified fingerprint progress signal and exposes a `telemetryAdvanced` input so real scroll telemetry can be wired in during M3 without changing terminal-consensus semantics.
