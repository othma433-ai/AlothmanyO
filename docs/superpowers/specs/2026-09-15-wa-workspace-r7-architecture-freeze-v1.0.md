# WA Workspace Android R7 — Architecture Freeze v1.0

**Date:** 2026-09-15  
**Status:** ARCHITECTURE FROZEN — IMPLEMENTATION BASELINE  
**Baseline:** WA Workspace Android v1.18.5 R6 — Al-Othmany Hardened  
**Decision:** Keep the existing WA Workspace UI and rebuild the internal automation engine around verified, event-driven execution.  
**Compatibility Rule:** Existing UI/bridge contracts remain compatible unless a documented migration is explicitly approved.  
**Release Rule:** R7 must not be described as Production Ready until Android build gates and real-device field gates pass.

## 1. Goal

R7 replaces the monolithic automation behavior in `ExtractionAccessibilityService` with a modular execution engine that can tolerate WhatsApp UI changes, Android accessibility inconsistencies, WhatsApp vs WhatsApp Business differences, and long-running extraction jobs without silently completing, drifting to the wrong chat, or losing progress.

The user-visible WA Workspace interface remains functionally and visually compatible. The change is primarily internal: synchronization, navigation, verification, scrolling, extraction, checkpointing, recovery, diagnostics, and adapters become independent components connected by explicit contracts.

The governing rule for every automation transition is:

> **Action → Observe → Verify → Commit State → Continue**

No transition is considered successful because a click/scroll API returned `true`, because a fixed delay elapsed, or because the screen merely resembles the expected screen.

## 2. Current R6 Constraints

The current R6 baseline already contains useful hardening from Watermelon and WA-Alothmany:

- WhatsApp and WhatsApp Business package awareness.
- Group-filter semantic detection.
- `ScrollTelemetryTracker` and `SemanticScrollPolicy`.
- Group identity heuristics / `identity_hint`.
- Gesture fallback and generation guards.
- Persistent SQLite queues/checkpoints.
- Action/verify behavior in several critical paths.
- Exact-title chat verification before extraction.

The main structural limitation is concentration of responsibilities in `ExtractionAccessibilityService` (~61 KB). It currently owns job orchestration, sync, group probing, chat navigation, extraction, invite flows, publishing, scroll actions, recovery, status, delays, and persistence coordination. This makes behavior difficult to isolate and test and causes changes in one WhatsApp path to affect unrelated paths.

R7 therefore treats R6 as a stable baseline but decomposes the runtime rather than adding another layer of conditions to the service.

## 3. Scope

### In scope

- WhatsApp (`com.whatsapp`).
- WhatsApp Business (`com.whatsapp.w4b`).
- Best-effort support for cloned/dual-app instances through package/profile adapters when Android exposes a distinct package or accessibility window.
- Group synchronization.
- Group selection and immutable execution queue creation.
- Deep extraction.
- New-only extraction.
- Pause/resume/stop/retry-failed.
- Durable checkpoints.
- Link parsing/deduplication.
- Automatic recovery from navigation drift and stale windows.
- Diagnostics and deterministic replay of captured accessibility snapshots.
- Existing export functions.
- Existing UI preserved.

### Out of scope for R7 core

- Reading WhatsApp private databases.
- Root-only access.
- Bypassing WhatsApp encryption or Android sandboxing.
- Treating unverified UI action completion as success.
- Guaranteed internal WhatsApp message IDs when they are not exposed through accessibility.
- Server-side delivery confirmation for publishing.

## 4. Architecture

R7 introduces five runtime layers.

### 4.1 Accessibility Host Layer

`ExtractionAccessibilityService` becomes a thin Android host rather than the automation engine.

Responsibilities:

- Receive `AccessibilityEvent`s.
- Expose `rootInActiveWindow` snapshots to the engine.
- Dispatch semantic node actions and gestures.
- Forward lifecycle / interruption events.
- Maintain the foreground overlay integration.
- Never decide high-level workflow state itself.

Target: reduce this class from the current monolith to a host/orchestrator with minimal feature logic.


#### 4.1.1 Node Snapshot Boundary

Raw `AccessibilityNodeInfo` objects are confined to the accessibility gateway and must never cross an asynchronous engine boundary. The gateway converts live nodes into immutable `NodeSnapshot` values before higher-level processing.

Minimum `NodeSnapshot` fields:

```text
NodeSnapshot
- viewId
- className
- textHash / redacted text when permitted
- contentDescriptionHash / redacted description when permitted
- bounds
- clickable
- scrollable
- selected
- enabled
- visibleToUser
- depth
- parentSignature
- childCount
- packageName
- windowId
- capturedAt
```

Rules:

1. `AutomationEngine`, classifiers, policies, repositories, and replay tests consume snapshots/observations, not live node references.
2. A live node may be reacquired only immediately before dispatching an action and must be revalidated against the expected snapshot/evidence.
3. Stale/recycled/window-mismatched nodes are treated as action-resolution failure, never as success.
4. Snapshot capture must be bounded and view-scoped to avoid full-tree churn on every accessibility event.

### 4.2 WhatsApp Adapter Layer

Introduce `WhatsAppAdapter` as a stable interface with concrete profiles:

- `ConsumerWhatsAppAdapter`
- `BusinessWhatsAppAdapter`
- `GenericWhatsAppAdapter` fallback

The adapter owns volatile WhatsApp-specific knowledge:

- package identity
- known view IDs
- semantic labels in Arabic/English
- group filter recognition
- chat-list recognition
- conversation header recognition
- message-list container recognition
- unread boundary recognition
- invite/join UI recognition where applicable

No extraction state machine directly contains WhatsApp view IDs or localized UI labels. Those are resolved through the adapter.

### 4.3 Observation and Verification Layer

Introduce immutable observation objects built from accessibility trees and events:

- `ScreenObservation`
- `ChatListObservation`
- `ConversationObservation`
- `ScrollObservation`
- `GroupRowObservation`
- `MessageViewportObservation`

`ScreenClassifier` classifies each observed window into states such as:

- `CHAT_LIST`
- `GROUPS_LIST`
- `CONVERSATION`
- `SEARCH`
- `INVITE`
- `COMMUNITY`
- `UNKNOWN`

Classification returns a confidence/evidence record rather than only a boolean.

Example:

```text
screen = GROUPS_LIST
confidence = 0.94
evidence = [package-match, groups-filter-selected, list-container, 8-valid-chat-rows]
```

This layer must not perform actions. It only observes and explains.


#### 4.3.1 Screen Stability Detector

`ScreenClassifier` answers **what the current observation appears to be**. `ScreenStabilityDetector` independently answers **whether the screen is stable enough to act on**.

A screen is not actionable merely because one observation crosses a confidence threshold. Transitional WhatsApp trees may briefly pass through `UNKNOWN`, partial `CHAT_LIST`, or partial `CONVERSATION` states. Stability requires a bounded series of mutually compatible observations.

Example:

```text
Observation #1: CONVERSATION confidence=0.73
Observation #2: CONVERSATION confidence=0.91
Observation #3: CONVERSATION confidence=0.95
Stable: YES
```

Default stability policy:

- at least 2 compatible observations for ordinary navigation;
- at least 3 compatible observations for destructive/high-risk state commits such as `COMPLETE`, target-chat acceptance, or terminal scroll consensus;
- package/window identity must remain compatible across the stability window;
- material evidence contradictions reset the stability counter;
- timeout produces `UNSTABLE_TIMEOUT`, which routes to recovery rather than silently continuing.

### 4.4 Execution Engine Layer

`AutomationEngine` owns the event-driven state machine.

Major controllers:

- `SyncController`
- `ExtractionController`
- `ConversationNavigator`
- `SmartScrollController`
- `RecoveryController`
- `CheckpointController`
- `QueueController`

The engine receives observations and emits desired actions. State transitions occur only after a verifier accepts the next observation.

Example synchronization states:

```text
START
→ ENSURE_TARGET_PACKAGE
→ ENSURE_CHAT_LIST
→ OPEN_GROUPS
→ VERIFY_GROUPS
→ RESET_LIST_TO_TOP
→ CAPTURE_VISIBLE_GROUPS
→ SCROLL_FORWARD
→ VERIFY_LIST_PROGRESS
→ CAPTURE_VISIBLE_GROUPS
→ ...
→ VERIFY_TERMINAL_CONSENSUS
→ COMPLETE
```

Example deep extraction states:

```text
LOAD_QUEUE_ITEM
→ OPEN_TARGET_GROUP
→ VERIFY_EXACT_GROUP
→ ENSURE_NEWEST_POSITION
→ CAPTURE_VISIBLE_MESSAGES
→ PERSIST_BATCH
→ SCROLL_OLDER
→ VERIFY_OLDER_CONTENT
→ CAPTURE_VISIBLE_MESSAGES
→ ...
→ VERIFY_CHAT_START_CONSENSUS
→ COMPLETE_ITEM
→ NEXT_ITEM
```

### 4.5 Persistence / Trace Layer

Existing `WorkspaceDb` remains the durable database but is split behind repositories instead of direct widespread calls.

Repositories:

- `GroupRepository`
- `LinkRepository`
- `OperationRepository`
- `CheckpointRepository`
- `DiagnosticRepository`
- `ProfileRepository`

Add a trace stream for each automation run. Each transition records:

- timestamp
- run/session ID
- operation/item ID
- current state
- requested action
- observation before action
- observation after action
- verification result
- failure/recovery reason
- scroll telemetry
- adapter/profile used

Trace records are bounded and summarized to avoid unlimited storage growth.

## 5. Device / WhatsApp Runtime Profiles

R7 adds a local `RuntimeProfile` per package/device combination.

A profile is keyed by a versioned runtime identity, not package name alone:

```text
RuntimeProfileKey =
packageName
+ appVersion
+ locale
+ androidVersion
+ deviceClass
+ androidProfile/instance identity when observable
```

A WhatsApp upgrade therefore cannot silently inherit a high-confidence selector/profile learned from an incompatible UI version. Older profiles may be retained for rollback/diagnostics but start with reduced applicability confidence after a version change.

The profile learns only non-sensitive UI characteristics that improve reliability, such as:

- working group-filter view IDs
- preferred list container class/view ID
- typical header bounds
- successful scroll strategy
- language/label variants observed
- known screen signatures
- adapter confidence history

Learning is conservative:

1. A candidate rule must succeed repeatedly.
2. It is stored with confidence and last-seen timestamp.
3. A failure lowers confidence rather than permanently poisoning the profile.
4. Profiles can be reset from diagnostics.

The profile never substitutes for verification. It only prioritizes strategies.

## 6. Smart Action Executor

Introduce `ActionExecutor` with typed actions instead of ad-hoc direct calls:

- `ClickNodeAction`
- `TapBoundsAction`
- `ScrollNodeAction`
- `GestureScrollAction`
- `GlobalBackAction`
- `SetTextAction`
- `LaunchPackageAction`

Every action has:

- preconditions
- execution result
- expected postcondition
- deadline
- verifier
- fallback policy

A successful Android API return means only **dispatched**, not **verified**.

Example:

```text
ScrollNodeAction dispatched = true
postcondition = viewport signature changed OR scroll telemetry advanced
verification = PASS
```

If postcondition fails, the executor can try a bounded fallback strategy.


### 6.1 Action Identity and Generation Contract

Every dispatched action carries immutable identity metadata:

```text
ActionContext
- runId
- operationId
- queueItemId
- stateGeneration
- actionId
- attemptNo
- requestedAt
```

`actionId` must be unique within a run. `stateGeneration` increments whenever the active run is stopped, superseded, reconstructed, or otherwise invalidated.

Before any asynchronous callback, gesture result, delayed verifier, or recovery continuation can mutate engine state, it must satisfy:

```text
callback.runId == active.runId
AND callback.stateGeneration == active.stateGeneration
AND callback.actionId is still pending/expected
```

Otherwise the callback is recorded as `STALE_CALLBACK_DROPPED` and ignored. This rule is centralized in `ActionExecutor`/`AutomationEngine`; individual controllers must not reimplement stale-callback protection ad hoc.

## 7. Smart Scroll Controller

Scrolling becomes a dedicated subsystem with semantic direction.

Inputs:

- target semantic direction (`OLDER`, `NEWER`, `LIST_FORWARD`, `LIST_BACKWARD`)
- current observation
- adapter/profile
- recent scroll telemetry

Strategy order:

1. Accessibility node action on verified container.
2. Alternate verified ancestor/descendant container.
3. Relative gesture inside safe bounds.
4. Recovery if the viewport did not move.

A boundary is accepted only by terminal consensus, not one failed action.

For group-list end detection, consensus requires a configurable combination of:

- no new stable group keys
- unchanged first/last visible rows
- no index/scroll telemetry progress
- same viewport signature
- repeated confirmation (default 3)

For deep extraction start-of-chat detection, consensus similarly requires repeated no-progress with stable oldest-message/viewport evidence.

## 8. Group Synchronization

### 8.1 Primary path

1. Activate selected WhatsApp package.
2. Classify current screen.
3. Navigate to chat list if needed.
4. Locate semantic Groups filter through adapter.
5. Dispatch click.
6. Verify selected groups state.
7. Identify group-list container.
8. Reset toward list top with bounded terminal consensus.
9. Capture visible group rows.
10. Persist in a transaction.
11. Scroll forward and verify progress.
12. Repeat until terminal consensus.

### 8.2 Grabber fallback

If the Groups filter is unavailable or unreliable, retain the R5/R6 Watermelon-inspired grabber, but implement it as an explicit fallback controller rather than mixing it into the primary sync code.

It can inspect chat rows/conversations, score group evidence, capture `identity_hint`, return to the list, and continue.

The fallback result is tagged with provenance so diagnostics know whether a group was discovered through `GROUPS_FILTER` or `CHAT_GRABBER`.

### 8.3 Stable group identity

Use a multi-field stable identity strategy:

1. WhatsApp-exposed stable identifier, if ever observable.
2. normalized title + strong identity hint.
3. normalized title + package + profile evidence.
4. title-only provisional key when no better evidence exists.

Provisional records merge into stronger identities later when enough evidence appears.

## 9. Immutable Execution Queue

When the user starts extraction, selected groups are copied into a durable operation queue.

The active queue is immutable in membership/order during the run. Sync changes do not mutate it.

Each item records:

- order index
- stable group key
- expected title
- expected identity hint when available
- mode (`DEEP` / `NEW_ONLY`)
- status
- retry count
- last state
- checkpoint ID
- failure reason

Allowed item states:

```text
PENDING
OPENING
VERIFYING
EXTRACTING
PAUSED
COMPLETED
FAILED
```

Transitions are persisted transactionally.

## 10. Conversation Verification

Before extraction, `ConversationVerifier` validates the opened chat.

Evidence can include:

- exact normalized title
- package
- conversation screen classification
- identity hint / participant summary when available
- expected header bounds

A title mismatch is a hard failure. Weak identity hint mismatch may trigger re-open/recovery rather than immediate extraction.

No URL is persisted under a group until conversation verification passes.

## 11. Deep Extraction

Deep extraction starts from the newest reachable viewport, then walks toward older messages.

Per cycle:

1. Build viewport observation.
2. Parse visible HTTP/HTTPS URLs.
3. Normalize and deduplicate.
4. Persist links/occurrences in one transaction.
5. Persist extraction checkpoint.
6. Request semantic `OLDER` scroll.
7. Verify older content loaded.
8. Repeat.

Deduplication does not rely exclusively on visible text order. Store:

- `original_url`
- `normalized_url`
- `conversation_key`
- contextual fingerprint
- first seen / last seen
- occurrence count

The engine must remain idempotent across restart/replay.

## 12. New-Only Extraction

New-only uses per-group durable high-water checkpoints.

Because accessibility may not expose stable WhatsApp message IDs, the checkpoint is evidence-based, potentially including:

- normalized visible message/context fingerprint
- viewport signature
- timestamp-like visible context when useful
- prior known URL/context fingerprints

The run begins from newest content and moves older until:

- the prior checkpoint is strongly matched, or
- the unread boundary is reached, or
- bounded no-progress evidence indicates that the historical boundary has been crossed.

The new checkpoint is committed only after the current run's extracted data is durably stored.

## 13. Recovery and Watchdog

`RecoveryController` handles navigation drift rather than each controller improvising recovery.

Recovery tiers:

1. Re-observe current window.
2. Reclassify screen.
3. Retry current verified action.
4. Navigate back to a known screen.
5. Relaunch selected WhatsApp package.
6. Re-enter current queue item from durable state.
7. Mark item failed after bounded retries and continue to next item.

A run-level watchdog detects:

- no accessibility events for excessive time
- same state/observation loop without progress
- stale root/window
- gesture stuck/in-flight timeout
- service recreation
- WhatsApp package unexpectedly changed

A failed group never terminates the whole extraction unless the global service/package is unusable.


### 13.1 Circuit Breaker

Recovery is bounded by a centralized `CircuitBreaker`; the engine must not loop indefinitely on the same failing transition.

Default policy:

```text
same transition failure x3  -> switch to alternate strategy
same transition failure x5  -> quarantine current queue item / controlled failover
run-wide critical failures  -> degrade run health and block unsafe new actions
```

The breaker key includes operation, queue item, state, failure category, and adapter/profile version. Successful verified progress decays/reset relevant breaker counters.

Circuit-breaker events are persisted in diagnostics and must explain which threshold fired and which fallback was selected.

## 14. Pause / Resume / Stop Semantics

### Pause

Pause is cooperative at atomic boundaries:

- finish or roll back the current DB transaction
- save current state/checkpoint
- transition item/run to PAUSED
- dispatch no new UI actions

### Resume

Resume reconstructs the state from durable queue/checkpoint and re-verifies the current WhatsApp screen. It does not blindly continue from an old in-memory node reference.

### Stop

Stop increments generation/cancellation token, prevents stale callbacks from mutating current state, saves final durable status, releases node references, and clears active run ownership.

## 15. Diagnostics and Replay

R7 adds a user-triggerable **Diagnostic Capture** mode and automatic capture on important failures.

A diagnostic bundle contains sanitized structural data only:

- package
- screen classifier result/evidence
- node class/view ID/bounds/action flags
- redacted/hashed text where full content is unnecessary
- list-row decisions and rejection reasons
- scroll telemetry
- state transitions
- action dispatch/verification results
- runtime profile decisions

Sensitive message text should not be written to diagnostics by default.

### Replay tests

Captured `NodeSnapshot`/observation fixtures can be replayed in JVM tests to verify:

- screen classification
- group-row parsing
- filter recognition
- scroll container selection
- group identity matching
- terminal-consensus behavior

This is the main mechanism for adapting to future WhatsApp UI changes without repeatedly guessing on-device.


### Runtime Metrics

Long-run stability is measured, not judged subjectively. Each run should expose bounded periodic metrics including:

- process memory / PSS when available
- accessibility events per minute
- snapshot builds per minute
- actions and verified actions per minute
- verification-failure rate
- recovery count and recovery success count
- gesture/node-action fallback count
- database batches/writes per minute
- average and p95 state-transition latency
- queue throughput
- circuit-breaker activations
- stale callbacks dropped

Metric storage is sampled and bounded. Metrics must never require storing message bodies.

## 16. UI Compatibility

The existing `popup.html`, `popup.css`, icon set, and overall interaction model remain unchanged unless a minimal diagnostic control is required.

Existing Native bridge command names should remain backward compatible where practical.

Internal commands can be routed through an `AutomationFacade` so the WebView is insulated from the new engine architecture.

No visual redesign is part of R7.

## 17. Performance Rules

- No full accessibility tree walk on every heartbeat unless classification requires it.
- Prefer adapter-specific fast paths and cached observation hints.
- Never retain `AccessibilityNodeInfo` across asynchronous state transitions.
- Recycle/close node copies deterministically.
- Database writes are batched and transactional.
- UI snapshots are rate-limited and view-scoped.
- Trace retention is bounded by count/age/size.
- Long-running jobs must not accumulate unbounded lists in memory.
- No controller may retain an unbounded history of `NodeSnapshot`, observation, URL, group, or trace objects.
- Metrics and traces use ring buffers / size-age retention limits plus durable summaries.
- After a one-hour warm-up in long-duration field testing, process memory must show a stable plateau rather than monotonic growth; the acceptance gate is defined in Section 22.

## 18. Safety / Correctness Invariants

The following invariants are mandatory:

1. Never extract before exact target-chat verification.
2. Never mark sync complete after one failed/no-op scroll.
3. Never mark scroll successful from dispatch result alone.
4. Never mutate the active queue membership during extraction.
5. Never lose a completed DB batch because the next scroll/recovery failed.
6. Never reuse accessibility nodes across asynchronous ticks.
7. Never allow stale gesture/action callbacks to alter a newer run.
8. Never let one failed group terminate all remaining groups.
9. Never label field behavior as verified without an actual device run.
10. Existing exports must read complete DB-backed results, not only the displayed UI subset.
11. Never let a stale callback mutate a newer `stateGeneration`.
12. Never act on a screen until `ScreenStabilityDetector` accepts it for the requested risk level.
13. Never let runtime-profile confidence bypass an explicit verifier.
14. Never retry the same failing transition indefinitely; every retry path is circuit-bounded.
15. Never promote `UNKNOWN` or unstable transitional UI to a successful workflow state.
16. Never persist sensitive message text in diagnostics unless an explicit diagnostic mode requires it and the user opts in.

## 19. Proposed Package Structure

```text
com.waworkspace.android
├── accessibility/
│   ├── ExtractionAccessibilityService.java
│   ├── AccessibilityGateway.java
│   ├── AccessibilityEventBuffer.java
│   └── LiveNodeResolver.java
├── snapshot/
│   ├── NodeSnapshot.java
│   ├── NodeSnapshotFactory.java
│   └── SnapshotSignature.java
├── automation/
│   ├── AutomationEngine.java
│   ├── AutomationFacade.java
│   ├── AutomationState.java
│   ├── ActionContext.java
│   ├── ActionExecutor.java
│   ├── VerificationResult.java
│   └── CircuitBreaker.java
├── adapter/
│   ├── WhatsAppAdapter.java
│   ├── ConsumerWhatsAppAdapter.java
│   ├── BusinessWhatsAppAdapter.java
│   └── GenericWhatsAppAdapter.java
├── observe/
│   ├── ScreenClassifier.java
│   ├── ScreenStabilityDetector.java
│   ├── ScreenObservation.java
│   ├── GroupRowObservation.java
│   ├── ConversationObservation.java
│   └── MessageViewportObservation.java
├── sync/
│   ├── SyncController.java
│   └── GroupGrabberController.java
├── extraction/
│   ├── ExtractionController.java
│   ├── DeepExtractionStrategy.java
│   ├── NewOnlyExtractionStrategy.java
│   └── ConversationVerifier.java
├── navigation/
│   ├── ConversationNavigator.java
│   ├── SmartScrollController.java
│   └── RecoveryController.java
├── join/
│   ├── JoinController.java
│   ├── InviteClassifier.java
│   ├── JoinTargetResolver.java
│   └── JoinResultVerifier.java
├── persistence/
│   ├── WorkspaceDb.java
│   ├── GroupRepository.java
│   ├── LinkRepository.java
│   ├── OperationRepository.java
│   ├── CheckpointRepository.java
│   ├── DiagnosticRepository.java
│   └── MetricRepository.java
├── profile/
│   ├── RuntimeProfileKey.java
│   ├── RuntimeProfile.java
│   └── RuntimeProfileRepository.java
├── diagnostics/
│   ├── TraceRecorder.java
│   ├── MetricRecorder.java
│   ├── DiagnosticCapture.java
│   └── ReplayFixture.java
└── existing UI/export/util components
```

The migration remains incremental. The new package boundaries are contracts, not a requirement to rewrite proven R6 behavior all at once.

### 19.1 Join Engine Rule

Join/invite automation remains outside the first extraction-core migration, but when moved it must use a dedicated verified flow:

```text
OPEN_INVITE
→ CLASSIFY_INVITE
→ RESOLVE_ACTION_TARGET
→ SNAPSHOT_TARGET
→ REACQUIRE_AND_VERIFY_SAME_TARGET
→ DISPATCH_CLICK
→ OBSERVE
→ VERIFY_JOIN_RESULT
```

The engine must not discover one candidate node and later click an unrelated re-searched node merely because its label is similar. Parent fallback is allowed only when the candidate relationship is preserved and postcondition verification succeeds.

## 20. Testing Strategy

### Pure JVM tests

- state transition legality
- terminal consensus
- retry/recovery policy
- queue immutability
- checkpoint matching
- URL normalization/deduplication
- runtime profile confidence
- screen classification from replay fixtures
- WhatsApp/Business adapter behavior
- NodeSnapshot signature/reacquisition behavior
- screen stability acceptance/reset/timeout
- stale action callback rejection by generation/action ID
- circuit-breaker threshold/fallback behavior
- runtime-profile version-key compatibility
- metric retention/bounding

### Android/static tests

- manifest/accessibility configuration
- bridge command compatibility
- no network permission regression
- R8 preservation of JavascriptInterface
- database migration checks
- AccessibilityNodeInfo lifecycle/static leak checks where feasible

### Build gates

- JDK 17
- Gradle 8.13
- compileSdk 36
- `testDebugUnitTest`
- `lintDebug`
- `assembleDebug`
- `assembleRelease`
- artifact upload

### Field gates

On a real Android phone, test separately:

1. WhatsApp consumer sync.
2. WhatsApp Business sync.
3. long list synchronization to terminal consensus.
4. duplicate group names.
5. Deep extraction over long chat history.
6. New-only after a known checkpoint.
7. pause/resume mid-group.
8. service killed/recreated mid-run.
9. one deliberately failing group followed by successful next group.
10. long-duration run for memory/performance stability.
11. injected navigation-drift recovery scenarios.
12. stale delayed callback after stop/restart to prove generation isolation.
13. WhatsApp app-version/profile change to prove profile revalidation.
14. join-request flow only after extraction-core gates pass, verifying actual post-click state rather than dispatch success.

R7 is not declared production-ready until these field gates pass on at least one representative device and the failures are captured through the new trace system.

## 21. Migration Strategy

R7 implementation should be staged to avoid replacing proven R6 behavior all at once:

1. Introduce observation, adapter, action, trace interfaces behind R6 behavior.
2. Move sync into `SyncController` and validate parity.
3. Move navigation/verification/scroll into independent controllers.
4. Move Deep extraction.
5. Move New-only extraction/checkpoints.
6. Add recovery/watchdog.
7. Add runtime profiles and replay diagnostics.
8. Move remaining invite/publish flows only after extraction core is stable.
9. Shrink `ExtractionAccessibilityService` to host responsibilities.
10. Complete regression + field testing.


Implementation must preserve a green baseline at each migration phase. A phase is not merged forward if it breaks UI/bridge compatibility, persistence migrations, or the verified R6 behavior it is replacing.

This sequencing prioritizes the user's current failure mode—group synchronization and extraction—before less relevant automation paths.

## 22. Acceptance Criteria

R7 core is accepted only when all qualitative invariants and the measurable gates below pass.

### 22.1 Functional Correctness

- Existing R6 UI/bridge contract: **100% preserved** for unchanged commands.
- Wrong-chat extraction: **0 tolerated cases** in the controlled field suite.
- False `COMPLETE` after no-op/failed scroll: **0 tolerated cases**.
- Queue membership/order mutation after extraction starts: **0 tolerated cases**.
- Completed DB batch lost after subsequent scroll/recovery failure: **0 tolerated cases**.
- Restart/resume duplication corruption: **0 tolerated cases**.
- Failed queue item blocking later healthy item: **0 tolerated cases**.
- Stale callback mutating a newer run/generation: **0 tolerated cases**.

### 22.2 Synchronization Coverage

For a controlled reference account whose group list is manually enumerated:

- if reference set contains fewer than 100 groups: **100% expected groups discovered**;
- if reference set contains 100 or more groups: **>=99% discovery**, with every miss explicitly diagnosed and no non-group rows falsely committed as verified groups;
- terminal consensus must be observed repeatedly; one failed/no-op scroll can never end sync.

### 22.3 Recovery

Across at least 20 deliberately injected recoverable navigation-drift scenarios:

- verified recovery success: **>=95%**;
- any unrecovered item must fail in a bounded manner and allow the queue to continue;
- infinite/repeating recovery loops: **0**.

### 22.4 Long-Run Stability

A representative real-device run must complete **at least 6 hours** with:

- process crash / ANR attributable to R7: **0**;
- unbounded queue/trace/snapshot accumulation: **0**;
- after the first-hour warm-up, median process PSS over the final 30 minutes must be **<=1.25x** the median PSS of the first 30 minutes after warm-up, unless a documented workload-size increase explains the delta;
- no monotonic memory-growth trend that continues after workload plateaus;
- trace/metric stores remain within configured retention bounds.

### 22.5 Build and Static Gates

The authoritative CI must pass:

- JDK 17 environment
- `testDebugUnitTest`
- `lintDebug`
- `assembleDebug`
- `assembleRelease`
- database migration tests
- bridge compatibility tests
- no unintended INTERNET permission / private WhatsApp database access regression

### 22.6 Production-Ready Gate

R7 may be labeled **Production Candidate** only after Sections 22.1–22.5 pass on a representative Android device with both the selected WhatsApp variant(s) under test and exported diagnostic evidence.

It may be labeled **Production Ready** only after repeated device runs show the same acceptance results without unresolved critical/high defects.

## 23. Architecture Freeze Decisions

The following decisions are frozen for R7 core and require an explicit architecture change to reverse:

1. **Action → Observe → Verify → Commit State → Continue** is mandatory.
2. Raw `AccessibilityNodeInfo` never survives an asynchronous engine transition.
3. State-changing callbacks are generation/action-ID guarded.
4. Screen classification and screen stability are separate concerns.
5. WhatsApp-specific selectors/labels live behind adapters/runtime profiles, not extraction state machines.
6. Runtime profiles are versioned by app/device/runtime identity and never replace verification.
7. Retry/recovery is circuit-bounded.
8. Queue membership/order is immutable during an active extraction operation.
9. Persistence is incremental and idempotent; progress is durable before the next risky UI step.
10. Field behavior is never called verified without a real-device run.
11. Existing UI is preserved; R7 is primarily an internal-engine migration.
12. Join/publish migration follows extraction-core stabilization, not the reverse.

## 24. Implementation Entry Gate

Before code migration begins, the implementation workspace must contain the actual R6 source baseline (not only this design document), including at minimum:

- `ExtractionAccessibilityService`
- `WorkspaceDb` and current persistence schema/migrations
- WebView/native bridge implementation
- `popup.html`, `popup.css`, `android-ui.js`
- Gradle settings/build files
- existing R6 tests/contract scripts
- GitHub Actions workflow used for Android build validation

The first implementation milestone is **R7-M1: Foundation Contracts**, which introduces `NodeSnapshot`, observation/stability contracts, adapter interfaces, `ActionContext`, action-generation guarding, trace/metric interfaces, and tests while preserving R6 behavior. No synchronization or extraction behavior is rewritten in M1.
